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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import dk.mekbits.bluemapworldscan.util.Sha1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs {@link ClientJar} against a local server that plays Mojang's version manifest. */
class ClientJarTest {

    private static final byte[] JAR_9_9 = "client jar 9.9".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAR_10_0 = "client jar 10.0".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path folder;

    private HttpServer server;
    private final AtomicInteger jarDownloads = new AtomicInteger();
    private volatile String latest = "9.9";
    private volatile byte[] served = JAR_9_9;
    private volatile String published = Sha1.hex(JAR_9_9);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/manifest.json", exchange -> reply(exchange, 200, manifest().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/version.json", exchange -> reply(exchange, 200, version().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/client.jar", exchange -> {
            jarDownloads.incrementAndGet();
            reply(exchange, 200, served);
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
        return "{\"downloads\":{\"client\":{\"sha1\":\"" + published + "\",\"url\":\"" + base() + "/client.jar\"}}}";
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private ClientJar clientJar() {
        return new ClientJar(folder, URI.create(base() + "/manifest.json"));
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
    void aReleaseNamePointingOutOfTheFolderIsRejected() {
        latest = "../escape";

        assertTrue(clientJar().ensure().isEmpty());
        assertFalse(Files.exists(folder.resolveSibling("escape.jar")));
    }
}
