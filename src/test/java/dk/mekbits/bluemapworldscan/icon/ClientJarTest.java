package dk.mekbits.bluemapworldscan.icon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import dk.mekbits.bluemapworldscan.util.Sha1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Runs {@link ClientJar} against a local server that plays Mojang's version manifest. */
class ClientJarTest {

    private static final byte[] JAR_9_9 = "client jar 9.9".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAR_10_0 = "client jar 10.0".getBytes(StandardCharsets.UTF_8);
    /** How much a server that is not stopped sends; the socket buffers hold only a few MB of it. */
    private static final long FLOOD_BYTES = 64L << 20;
    private static final long SMALL_LIMIT = 1024;

    @TempDir
    Path folder;

    private HttpServer server;
    private final AtomicInteger versionRequests = new AtomicInteger();
    private final AtomicInteger jarDownloads = new AtomicInteger();
    private volatile String jarLocation = "/client.jar";
    private volatile String redirectTo;
    private volatile boolean chunked;
    private volatile int floodStatus;
    private volatile boolean floodDeclaresItsLength;
    private final AtomicLong floodWritten = new AtomicLong();
    private final CountDownLatch floodEnded = new CountDownLatch(1);
    private volatile String latest = "9.9";
    private volatile byte[] served = JAR_9_9;
    private volatile String published = Sha1.hex(JAR_9_9);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/manifest.json", exchange -> reply(exchange, 200, manifest().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/version.json", exchange -> {
            versionRequests.incrementAndGet();
            reply(exchange, 200, version().getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/client.jar", exchange -> {
            jarDownloads.incrementAndGet();
            if (floodStatus != 0) {
                flood(exchange);
            } else if (redirectTo != null) {
                exchange.getResponseHeaders().add("Location", redirectTo);
                reply(exchange, 302, new byte[0]);
            } else if (chunked) {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(served);
                }
            } else {
                reply(exchange, 200, served);
            }
        });
        server.createContext("/moved.jar", exchange -> {
            exchange.getResponseHeaders().add("Location", "/client.jar");
            reply(exchange, 302, new byte[0]);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String manifest() {
        return "{\"latest\":{\"release\":\"" + latest + "\"},\"versions\":["
                + "{\"id\":\"snapshot\",\"url\":\"" + base() + "/nowhere\"},"
                + "{\"id\":\"" + latest + "\",\"url\":\"" + base() + "/version.json\"}]}";
    }

    private String version() {
        String url = jarLocation.startsWith("/") ? base() + jarLocation : jarLocation;
        return "{\"downloads\":{\"client\":{\"sha1\":\"" + published + "\",\"url\":\"" + url + "\"}}}";
    }

    /** Sends FLOOD_BYTES of body and counts what was written before the client stopped reading and closed the connection. */
    private void flood(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(floodStatus, floodDeclaresItsLength ? FLOOD_BYTES : 0);
        byte[] chunk = new byte[8192];
        try (OutputStream out = exchange.getResponseBody()) {
            for (long sent = 0; sent < FLOOD_BYTES; sent += chunk.length) {
                out.write(chunk);
                floodWritten.addAndGet(chunk.length);
            }
        } catch (IOException clientClosedTheConnection) {
            // That is the outcome these tests look for; the count says how far the client read.
        } finally {
            floodEnded.countDown();
        }
    }

    private void assertTheClientStoppedReadingEarly() throws InterruptedException {
        assertTrue(floodEnded.await(30, TimeUnit.SECONDS), "the server never saw the client stop");
        assertTrue(floodWritten.get() < FLOOD_BYTES / 4,
                "the client read " + floodWritten.get() + " of " + FLOOD_BYTES + " bytes");
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Trusts the local server on top of what Mojang's URLs need, so the tests need no network. */
    private ClientJar clientJar(long maxJarBytes) {
        return new ClientJar(
                folder, URI.create(base() + "/manifest.json"),
                uri -> "127.0.0.1".equals(uri.getHost()) || ClientJar.isMojangUrl(uri), maxJarBytes);
    }

    private ClientJar clientJar() {
        return clientJar(ClientJar.MAX_JAR_BYTES);
    }

    private List<String> filesInFolder() throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theJarOfTheLatestReleaseIsDownloadedAndKept() throws IOException {
        Optional<Path> jar = clientJar().ensure();

        assertEquals(folder.resolve("client-9.9.jar"), jar.orElseThrow());
        assertArrayEquals(JAR_9_9, Files.readAllBytes(jar.get()));
        assertEquals(List.of("client-9.9.jar"), filesInFolder(), "no temporary file is left");
    }

    @Test
    void aKeptJarWithTheRightSha1IsNotDownloadedAgain() {
        clientJar().ensure();
        clientJar().ensure();

        assertEquals(1, jarDownloads.get());
    }

    @Test
    void aJarThatDoesNotMatchTheSha1MojangPublishesIsRejected() throws IOException {
        published = Sha1.hex("something else");

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar().ensure().isEmpty());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aNewerReleaseReplacesTheOlderJar() throws IOException {
        clientJar().ensure();
        latest = "10.0";
        served = JAR_10_0;
        published = Sha1.hex(JAR_10_0);

        Optional<Path> jar = clientJar().ensure();

        assertEquals(folder.resolve("client-10.0.jar"), jar.orElseThrow());
        assertEquals(List.of("client-10.0.jar"), filesInFolder());
    }

    @Test
    void whenMojangCannotBeReachedTheKeptJarIsUsed() {
        clientJar().ensure();
        server.stop(0);

        try (LogCapture log = new LogCapture()) {
            assertEquals(folder.resolve("client-9.9.jar"), clientJar().ensure().orElseThrow());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
    }

    @Test
    void whenMojangCannotBeReachedAndNothingIsKeptThereIsNoJar() {
        server.stop(0);

        assertFalse(clientJar().ensure().isPresent());
    }

    @Test
    void aReleaseNamePointingOutOfTheFolderIsRejectedBeforeAnythingIsDownloaded() throws IOException {
        latest = "../../escape";

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar().ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("unexpected version name")), log.warnings().toString());
        }
        assertEquals(0, versionRequests.get(), "the version file of such a release is not even requested");
        assertEquals(0, jarDownloads.get());
        assertEquals(List.of(), filesInFolder());
        assertFalse(Files.exists(folder.resolve("escape.jar")));
        assertFalse(Files.exists(folder.getParent().resolve("escape.jar")));
    }

    @ParameterizedTest
    @CsvSource({
        "https://piston-data.mojang.com/v1/objects/abc/client.jar, true",
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json, true",
        "HTTPS://Piston-Data.Mojang.com/client.jar, true",
        "http://piston-data.mojang.com/client.jar, false",
        "https://example.com/client.jar, false",
        "https://mojang.com.example.com/client.jar, false",
        "https://evil-mojang.com/client.jar, false",
        "https://piston-data.mojang.com@example.com/client.jar, false",
        "file:///etc/passwd, false",
        "client.jar, false"})
    void onlyHttpsUrlsUnderMojangDotComAreTrusted(String url, boolean trusted) {
        assertEquals(trusted, ClientJar.isMojangUrl(URI.create(url)));
    }

    @ParameterizedTest
    @CsvSource({
        "http://piston-data.mojang.com/client.jar",
        "https://example.com/client.jar"})
    void aJarUrlThatIsNotMojangsIsNotRequested(String url) throws IOException {
        jarLocation = url;

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar().ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("refusing to download")), log.warnings().toString());
        }
        assertEquals(1, versionRequests.get());
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aVersionFileUrlThatIsNotTrustedIsNotRequested() {
        ClientJar strict = new ClientJar(
                folder, URI.create(base() + "/manifest.json"),
                uri -> "127.0.0.1".equals(uri.getHost()) && uri.getPath().equals("/manifest.json"), ClientJar.MAX_JAR_BYTES);

        assertTrue(strict.ensure().isEmpty());

        assertEquals(0, versionRequests.get());
    }

    @Test
    void aRedirectToAForeignHostIsNotFollowed() throws IOException {
        redirectTo = "https://example.com/client.jar";

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar().ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("refusing to download")), log.warnings().toString());
        }
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aRedirectToATrustedUrlIsFollowed() {
        jarLocation = "/moved.jar";

        assertEquals(folder.resolve("client-9.9.jar"), clientJar().ensure().orElseThrow());
    }

    @Test
    void aJarOneByteOverTheLimitThatDeclaresItIsRejected() throws IOException {
        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar(JAR_9_9.length - 1).ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("larger than")), log.warnings().toString());
        }
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aJarOneByteOverTheLimitThatDoesNotDeclareItIsRejected() throws IOException {
        chunked = true;

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar(JAR_9_9.length - 1).ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("larger than")), log.warnings().toString());
        }
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aJarFarOverTheLimitThatDeclaresItIsRejectedWhenTheLimitIsPassed() throws Exception {
        floodStatus = 200;
        floodDeclaresItsLength = true;

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar(SMALL_LIMIT).ensure().isEmpty());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
        assertTheClientStoppedReadingEarly();
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void aJarFarOverTheLimitThatDoesNotDeclareItIsRejectedWhenTheLimitIsPassed() throws Exception {
        floodStatus = 200;

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar(SMALL_LIMIT).ensure().isEmpty());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
        assertTheClientStoppedReadingEarly();
        assertEquals(List.of(), filesInFolder());
    }

    @Test
    void theBodyOfAnErrorResponseIsNotReadToTheEnd() throws Exception {
        floodStatus = 500;
        floodDeclaresItsLength = true;

        try (LogCapture log = new LogCapture()) {
            assertTrue(clientJar().ensure().isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("HTTP 500")), log.warnings().toString());
        }
        assertTheClientStoppedReadingEarly();
    }

    @Test
    void aJarOfExactlyTheLimitIsAccepted() {
        chunked = true;

        assertTrue(clientJar(JAR_9_9.length).ensure().isPresent());
    }
}
