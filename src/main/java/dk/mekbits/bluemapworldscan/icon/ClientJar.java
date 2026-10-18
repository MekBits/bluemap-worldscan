package dk.mekbits.bluemapworldscan.icon;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dk.mekbits.bluemapworldscan.util.Log;
import dk.mekbits.bluemapworldscan.util.Sha1;

/**
 * The Minecraft client jar, which holds the textures the layer icons are made from. An addon has
 * no Minecraft version of its own, so this takes the newest release in Mojang's version manifest
 * and keeps the jar in a folder, checked against the sha1 Mojang publishes (the download follows
 * a24k/bluemap-structures-paper, MIT, see THIRD_PARTY_NOTICES.md).
 *
 * <p>The version manifest names the URLs of the next two downloads, so each URL, redirects
 * included, must be https under {@code mojang.com}, and the jar is cut off at 200 MB.
 *
 * <p>Only the scanning instance uses it. When the download fails the newest jar kept from an
 * earlier run is used, and without one there is no jar.
 */
public final class ClientJar {

    static final URI VERSION_MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    /** The Minecraft 26.3 client jar is 41 MB. */
    static final long MAX_JAR_BYTES = 200L << 20;

    private static final String PREFIX = "client-";
    private static final String SUFFIX = ".jar";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JAR_TIMEOUT = Duration.ofSeconds(180);
    private static final String MOJANG_DOMAIN = ".mojang.com";
    private static final int MAX_REDIRECTS = 3;

    private final Path folder;
    private final URI versionManifest;
    private final Predicate<URI> trusted;
    private final long maxJarBytes;

    public ClientJar(Path folder) {
        this(folder, VERSION_MANIFEST, ClientJar::isMojangUrl, MAX_JAR_BYTES);
    }

    /** The trust check and the size limit are parameters so that tests can use a local server and a small jar. */
    ClientJar(Path folder, URI versionManifest, Predicate<URI> trusted, long maxJarBytes) {
        this.folder = folder;
        this.versionManifest = versionManifest;
        this.trusted = trusted;
        this.maxJarBytes = maxJarBytes;
    }

    /** Whether the URL is https and its host is under {@code mojang.com}. */
    static boolean isMojangUrl(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && host.toLowerCase(Locale.ROOT).endsWith(MOJANG_DOMAIN);
    }

    /** The jar of the newest release; blocking, and slow when it has to be downloaded. */
    public Optional<Path> ensure() {
        try {
            return Optional.of(download());
        } catch (IOException | RuntimeException e) {
            return keptJar("cannot get the Minecraft client jar (" + e + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return keptJar("interrupted while getting the Minecraft client jar");
        }
    }

    private Path download() throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            return download(http);
        }
    }

    private Path download(HttpClient http) throws IOException, InterruptedException {
        JsonObject manifest = getJson(http, versionManifest);
        String version = manifest.getAsJsonObject("latest").get("release").getAsString();
        if (!version.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("unexpected version name " + version);
        }
        JsonObject client = getJson(http, versionUrl(manifest.getAsJsonArray("versions"), version))
                .getAsJsonObject("downloads").getAsJsonObject("client");
        String sha1 = client.get("sha1").getAsString();

        Path jar = folder.resolve(PREFIX + version + SUFFIX);
        if (Files.isRegularFile(jar) && sha1.equalsIgnoreCase(sha1Of(jar))) {
            return jar;
        }

        Files.createDirectories(folder);
        Path temporary = Files.createTempFile(folder, PREFIX + version + "-", ".tmp");
        try {
            HttpResponse<Path> response = get(
                    http, URI.create(client.get("url").getAsString()), JAR_TIMEOUT, cappedFile(temporary), temporary);
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading the client jar");
            }
            if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maxJarBytes
                    || Files.size(temporary) > maxJarBytes) {
                throw new IOException("the client jar is larger than the " + maxJarBytes + " bytes allowed");
            }
            String actual = sha1Of(temporary);
            if (!sha1.equalsIgnoreCase(actual)) {
                throw new IOException("the client jar has sha1 " + actual + ", Mojang publishes " + sha1);
            }
            Files.move(temporary, jar, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        deleteOtherJars(jar);
        Log.info("downloaded the Minecraft " + version + " client jar for the layer icons");
        return jar;
    }

    private static URI versionUrl(JsonArray versions, String version) throws IOException {
        for (JsonElement element : versions) {
            JsonObject entry = element.getAsJsonObject();
            if (version.equals(entry.get("id").getAsString())) {
                return URI.create(entry.get("url").getAsString());
            }
        }
        throw new IOException("version " + version + " is not in Mojang's version manifest");
    }

    private JsonObject getJson(HttpClient http, URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = get(http, uri, MANIFEST_TIMEOUT, BodyHandlers.ofString(), "");
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * Gets the URL and follows its redirects, each of which must be trusted too. The body is kept for
     * a 200 and replaced by {@code noBody} for any other status.
     */
    private <T> HttpResponse<T> get(HttpClient http, URI uri, Duration timeout, BodyHandler<T> body, T noBody)
            throws IOException, InterruptedException {
        URI current = uri;
        for (int redirects = 0; ; redirects++) {
            if (!trusted.test(current)) {
                throw new IOException("refusing to download from " + current + ": Mojang serves its downloads over https from mojang.com");
            }
            HttpResponse<T> response = http.send(
                    HttpRequest.newBuilder(current).timeout(timeout).GET().build(),
                    info -> info.statusCode() == 200 ? body.apply(info) : BodyHandlers.replacing(noBody).apply(info));
            String location = response.statusCode() / 100 == 3 ? response.headers().firstValue("Location").orElse(null) : null;
            if (location == null) {
                return response;
            }
            if (redirects == MAX_REDIRECTS) {
                throw new IOException("too many redirects from " + uri);
            }
            current = current.resolve(location);
        }
    }

    /** Writes the body to the file, but not past one byte over the limit, and nothing when it declares more than the limit. */
    private BodyHandler<Path> cappedFile(Path target) {
        BodyHandler<Path> capped = BodyHandlers.limiting(BodyHandlers.ofFile(target), maxJarBytes + 1);
        return info -> info.headers().firstValueAsLong("Content-Length").orElse(0) > maxJarBytes
                ? BodyHandlers.replacing(target).apply(info)
                : capped.apply(info);
    }

    private Optional<Path> keptJar(String problem) {
        Path newest = null;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, PREFIX + "*" + SUFFIX)) {
            for (Path jar : jars) {
                if (newest == null || Files.getLastModifiedTime(jar).compareTo(Files.getLastModifiedTime(newest)) > 0) {
                    newest = jar;
                }
            }
        } catch (IOException e) {
            newest = null;
        }
        Log.warn(problem + "; " + (newest != null ? "using the kept " + newest.getFileName() : "the layers get no icons"));
        return Optional.ofNullable(newest);
    }

    private void deleteOtherJars(Path keep) throws IOException {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, PREFIX + "*" + SUFFIX)) {
            for (Path jar : jars) {
                if (!jar.equals(keep)) {
                    Files.deleteIfExists(jar);
                }
            }
        }
    }

    private static String sha1Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Sha1.hex(in);
        }
    }
}
