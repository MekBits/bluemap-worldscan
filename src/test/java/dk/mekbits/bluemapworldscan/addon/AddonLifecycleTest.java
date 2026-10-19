package dk.mekbits.bluemapworldscan.addon;

import static dk.mekbits.bluemapworldscan.testsupport.LoadedAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import de.bluecolored.bluemap.api.markers.POIMarker;
import dk.mekbits.bluemapworldscan.bluemap.LayerStore;
import dk.mekbits.bluemapworldscan.bluemap.Manifest;
import dk.mekbits.bluemapworldscan.config.AddonFolder;
import dk.mekbits.bluemapworldscan.icon.ClientAssetIcons;
import dk.mekbits.bluemapworldscan.testsupport.Await;
import dk.mekbits.bluemapworldscan.testsupport.FakeBlueMap;
import dk.mekbits.bluemapworldscan.testsupport.FakeBlueMap.FakeWorld;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryAssetStorage;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryBlueMapMap;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import dk.mekbits.bluemapworldscan.testsupport.TestWorlds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddonLifecycleTest {

    private static final String MONUMENT_SET = "worldscan-structure-monument";
    private static final String VILLAGE_SET = "worldscan-structure-village";
    private static final String LAYERS_ASSET = "bluemap-worldscan-layers.json.gz";
    private static final String MANIFEST_ASSET = "bluemap-worldscan-manifest.json";

    @TempDir
    Path temp;

    private final List<AddonLifecycle> lifecycles = new ArrayList<>();

    @AfterEach
    void stopScans() {
        lifecycles.forEach(AddonLifecycle::cancelScan);
    }

    private AddonLifecycle lifecycle(Path addonFolder) {
        AddonLifecycle lifecycle = new AddonLifecycle(addonFolder, "test", TimeUnit.MILLISECONDS, new ClientAssetIcons(Optional::empty));
        lifecycles.add(lifecycle);
        return lifecycle;
    }

    private static FakeBlueMap api(boolean plugin, Path dimension, InMemoryBlueMapMap... maps) {
        return new FakeBlueMap(plugin, new FakeWorld("world", dimension, List.of(maps)));
    }

    private Path addonFolder(String name, String configJson) throws IOException {
        Path folder = Files.createDirectories(temp.resolve(name));
        if (configJson != null) {
            Files.writeString(folder.resolve("config.json"), configJson, StandardCharsets.UTF_8);
        }
        return folder;
    }

    private static boolean has(InMemoryBlueMapMap map, String setId) {
        return map.getMarkerSets().containsKey(setId);
    }

    @Test
    void aScanningInstanceShowsAndStoresTheLayersOfEveryMapOfTheWorld() throws IOException {
        Path folder = addonFolder("addon", null);
        InMemoryBlueMapMap first = new InMemoryBlueMapMap("overworld");
        InMemoryBlueMapMap second = new InMemoryBlueMapMap("overworld-b");

        lifecycle(folder).onEnable(api(true, TestWorlds.withMonument(temp.resolve("world")), first, second));

        Await.until("the layers on both maps", () -> has(first, MONUMENT_SET) && has(second, MONUMENT_SET));
        POIMarker monument = (POIMarker) first.getMarkerSets().get(MONUMENT_SET).getMarkers().values().iterator().next();
        assertEquals("Ocean monument", monument.getLabel());
        assertTrue(monument.getDetail().contains("/tp @s"), monument.getDetail());
        for (InMemoryBlueMapMap map : List.of(first, second)) {
            Manifest manifest = assertOk(LayerStore.readManifest(map.storage()));
            assertEquals(AddonFolder.scannerId(folder), manifest.scannerId());
            assertEquals("test", manifest.addonVersion());
        }
        assertTrue(Files.exists(folder.resolve("config.json")), "the default configuration was written");
        assertTrue(Files.isDirectory(folder.resolve("state")), "the scan state is kept in the addon folder");
        assertEquals(first.getMarkerSets(), second.getMarkerSets());
    }

    @Test
    void anUnchangedWorldStoresNoNewGenerationOnTheNextEnable() throws IOException {
        Path folder = addonFolder("addon", null);
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        AddonLifecycle lifecycle = lifecycle(folder);
        FakeBlueMap commandLine = api(false, dimension, map);
        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);
        Manifest first = assertOk(LayerStore.readManifest(map.storage()));

        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);

        assertEquals(first, assertOk(LayerStore.readManifest(map.storage())));
        assertEquals(1, map.storage().completedWrites().stream().filter(LAYERS_ASSET::equals).count());
    }

    @Test
    void changingADisplaySettingStoresANewGeneration() throws IOException {
        Path folder = addonFolder("addon", null);
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        AddonLifecycle lifecycle = lifecycle(folder);
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), map);
        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);
        Manifest first = assertOk(LayerStore.readManifest(map.storage()));

        Files.writeString(folder.resolve("config.json"), "{\"structures\": {\"disabledLayers\": [\"monument\"]}}");
        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);

        Manifest second = assertOk(LayerStore.readManifest(map.storage()));
        assertTrue(second.generation() > first.generation());
        assertNotEquals(first.layersSha1(), second.layersSha1());
        assertFalse(has(map, MONUMENT_SET), "a layer that is no longer built disappears");
    }

    @Test
    void aSecondInstanceThatDoesNotScanFollowsTheScannersGenerations() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        InMemoryBlueMapMap scannerMap = new InMemoryBlueMapMap("overworld", shared);
        InMemoryBlueMapMap viewerMap = new InMemoryBlueMapMap("overworld", shared);
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        Path scannerFolder = addonFolder("scanner", null);
        Path viewerFolder = addonFolder("viewer", "{\"scan\": false, \"refreshMinutes\": 50}");
        lifecycle(viewerFolder).onEnable(api(true, dimension, viewerMap));

        AddonLifecycle scanner = lifecycle(scannerFolder);
        scanner.onEnable(api(true, dimension, scannerMap));
        Await.until("the scanner's layers on the viewer", () -> has(viewerMap, MONUMENT_SET));
        assertEquals(scannerMap.getMarkerSets(), viewerMap.getMarkerSets());

        Files.writeString(scannerFolder.resolve("config.json"), "{\"structures\": {\"disabledLayers\": [\"monument\"]}}");
        scanner.onDisable(api(true, dimension, scannerMap));
        scanner.onEnable(api(true, dimension, scannerMap));
        Await.until("the new generation on the viewer", () -> !has(viewerMap, MONUMENT_SET));
        assertFalse(Files.exists(viewerFolder.resolve("state")), "the viewer never scanned");
        assertFalse(Files.exists(viewerFolder.resolve("scanner-id")));
    }

    @Test
    void aCommandLineThatDoesNotScanKeepsFollowingNewGenerationsAfterItsRenderRunEnds() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        InMemoryBlueMapMap viewerMap = new InMemoryBlueMapMap("overworld", shared);
        InMemoryBlueMapMap scannerMap = new InMemoryBlueMapMap("overworld", shared);
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        AddonLifecycle viewer = lifecycle(addonFolder("viewer", "{\"scan\": false, \"refreshMinutes\": 20}"));
        FakeBlueMap commandLine = api(false, dimension, viewerMap);
        viewer.onEnable(commandLine);
        viewer.onDisable(commandLine);
        assertTrue(viewerMap.getMarkerSets().isEmpty(), "nothing is stored yet");

        lifecycle(addonFolder("scanner", null)).onEnable(api(true, dimension, scannerMap));

        Await.until("the layers on the command line's web server", () -> has(viewerMap, MONUMENT_SET));
    }

    @Test
    void aStoredGenerationShowsAtOnceOnStartWithoutAScan() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        FakeBlueMap commandLine = api(false, dimension, new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle scanner = lifecycle(addonFolder("scanner", null));
        scanner.onEnable(commandLine);
        scanner.onDisable(commandLine);

        InMemoryBlueMapMap restarted = new InMemoryBlueMapMap("overworld", shared);
        Path viewerFolder = addonFolder("viewer", "{\"scan\": false, \"refreshMinutes\": 0}");
        FakeBlueMap serverStart = api(true, dimension, restarted);
        AddonLifecycle viewer = lifecycle(viewerFolder);
        viewer.onEnable(serverStart);

        Await.until("the stored layers", () -> has(restarted, MONUMENT_SET));
    }

    @Test
    void aWorldWithoutARegionFolderIsSkippedAndKeepsItsStoredLayers() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle first = lifecycle(addonFolder("first", null));
        first.onEnable(commandLine);
        first.onDisable(commandLine);
        byte[] layers = shared.bytes(LAYERS_ASSET);

        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld", shared);
        Path unmounted = Files.createDirectories(temp.resolve("unmounted"));
        AddonLifecycle second = lifecycle(addonFolder("second", null));
        FakeBlueMap secondApi = api(false, unmounted, map);
        try (LogCapture log = new LogCapture()) {
            second.onEnable(secondApi);
            second.onDisable(secondApi);

            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("no region folder")), log.warnings().toString());
        }
        assertTrue(has(map, MONUMENT_SET), "the stored layers are shown");
        assertArrayEquals(layers, shared.bytes(LAYERS_ASSET), "the stored layers are untouched");
        assertFalse(Files.exists(temp.resolve("second").resolve("state")), "no state is stored for a world that was not read");
    }

    @Test
    void aFailedWriteOnOneMapDoesNotStopTheOthers() throws IOException {
        InMemoryBlueMapMap broken = new InMemoryBlueMapMap("broken");
        InMemoryBlueMapMap fine = new InMemoryBlueMapMap("fine");
        broken.storage().failWritesOf(LAYERS_ASSET);
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), broken, fine);
        AddonLifecycle lifecycle = lifecycle(addonFolder("addon", null));

        try (LogCapture log = new LogCapture()) {
            lifecycle.onEnable(commandLine);
            lifecycle.onDisable(commandLine);

            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("broken")), log.warnings().toString());
        }
        assertTrue(has(fine, MONUMENT_SET));
        assertFalse(has(broken, MONUMENT_SET));
    }

    @Test
    void aSecondScannerOnTheSameStorageIsWarnedAbout() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        FakeBlueMap commandLine = api(false, dimension, new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle first = lifecycle(addonFolder("first", null));
        first.onEnable(commandLine);
        first.onDisable(commandLine);
        AddonLifecycle second = lifecycle(addonFolder("second", null));

        try (LogCapture log = new LogCapture()) {
            second.onEnable(commandLine);
            second.onDisable(commandLine);

            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("another scanner")), log.warnings().toString());
        }
    }

    @Test
    void identicalLayersStoredByAnotherScannerAreTakenOverSoTheWarningStopsOnceTheRolesHaveMoved() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        Path dimension = TestWorlds.withMonument(temp.resolve("world"));
        FakeBlueMap commandLine = api(false, dimension, new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle first = lifecycle(addonFolder("first", null));
        first.onEnable(commandLine);
        first.onDisable(commandLine);
        Manifest storedByFirst = assertOk(LayerStore.readManifest(shared));
        Path secondFolder = addonFolder("second", null);
        AddonLifecycle second = lifecycle(secondFolder);

        try (LogCapture log = new LogCapture()) {
            second.onEnable(commandLine);
            second.onDisable(commandLine);

            assertEquals(1, log.warnings().stream().filter(message -> message.contains("another scanner")).count(),
                    "the roles moved: one warning, " + log.warnings());
        }
        Manifest takenOver = assertOk(LayerStore.readManifest(shared));
        assertEquals(AddonFolder.scannerId(secondFolder), takenOver.scannerId());
        assertEquals(storedByFirst.layersSha1(), takenOver.layersSha1(), "the layers themselves are the same");
        assertTrue(takenOver.generation() > storedByFirst.generation());

        try (LogCapture log = new LogCapture()) {
            second.onEnable(commandLine);
            second.onDisable(commandLine);

            assertTrue(log.warnings().isEmpty(), "the second scanner is the only one now: " + log.warnings());
        }
        assertEquals(takenOver, assertOk(LayerStore.readManifest(shared)), "the same scanner with the same layers stores nothing");
    }

    @Test
    void layersStoredByANewerAddonAreKeptAndTheScannerSaysSo() throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        byte[] manifest = "{\"formatVersion\":2,\"generation\":5,\"scannerId\":\"s\",\"addonVersion\":\"2.0.0\",\"layersSha1\":\"x\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] layers = gzip("{\"formatVersion\":2,\"sets\":[]}");
        shared.put(MANIFEST_ASSET, manifest);
        shared.put(LAYERS_ASSET, layers);
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle lifecycle = lifecycle(addonFolder("addon", null));

        try (LogCapture log = new LogCapture()) {
            lifecycle.onEnable(commandLine);
            lifecycle.onDisable(commandLine);

            assertEquals(1, log.warnings().stream().filter(message -> message.contains("newer bluemap-worldscan")).count(),
                    log.warnings().toString());
        }
        assertArrayEquals(manifest, shared.bytes(MANIFEST_ASSET));
        assertArrayEquals(layers, shared.bytes(LAYERS_ASSET));
        assertEquals(Set.of(MANIFEST_ASSET, LAYERS_ASSET), shared.names(), "no icons were written either");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"formatVersion\":0,\"generation\":5,\"scannerId\":\"s\",\"addonVersion\":\"0.1.0\",\"layersSha1\":\"x\"}"})
    void aDamagedOrOlderManifestIsReplacedByTheScan(String storedManifest) throws IOException {
        InMemoryAssetStorage shared = new InMemoryAssetStorage("overworld");
        shared.put(MANIFEST_ASSET, storedManifest.getBytes(StandardCharsets.UTF_8));
        Path folder = addonFolder("addon", null);
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), new InMemoryBlueMapMap("overworld", shared));
        AddonLifecycle lifecycle = lifecycle(folder);

        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);

        assertEquals(AddonFolder.scannerId(folder), assertOk(LayerStore.readManifest(shared)).scannerId());
        assertOk(LayerStore.readLayers(shared));
    }

    @Test
    void aResetFileMakesTheNextScanStartFromZero() throws IOException {
        Path folder = addonFolder("addon", null);
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), new InMemoryBlueMapMap("overworld"));
        AddonLifecycle lifecycle = lifecycle(folder);
        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);
        Files.createFile(folder.resolve("reset"));

        try (LogCapture log = new LogCapture()) {
            lifecycle.onEnable(commandLine);
            lifecycle.onDisable(commandLine);

            assertTrue(log.messages(Level.INFO).stream().anyMatch(message -> message.contains("reset requested")));
            assertTrue(log.messages(Level.INFO).stream().anyMatch(message -> message.contains("read 1")),
                    "the chunk was read again: " + log.messages(Level.INFO));
        }
        assertFalse(Files.exists(folder.resolve("reset")));
    }

    @Test
    void anInstanceThatDoesNotScanReadsNoWorldAndWritesNoState() throws IOException {
        Path folder = addonFolder("addon", "{\"scan\": false}");
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        FakeBlueMap commandLine = api(false, TestWorlds.withMonument(temp.resolve("world")), map);
        AddonLifecycle lifecycle = lifecycle(folder);

        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);

        assertTrue(map.getMarkerSets().isEmpty());
        assertTrue(map.storage().names().isEmpty(), "nothing is written to the storage");
        assertEquals(Set.of("config.json"), listing(folder));
    }

    @Test
    void onEnableHandsOffAtOnceAndThePluginStopsTheScanOnDisable() throws IOException {
        Path folder = addonFolder("addon", "{\"maxChunksPerSecond\": 20}");
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        // The first region is read in a fraction of a second, the second takes 20 s.
        Path dimension = TestWorlds.withChunks(TestWorlds.withChunks(temp.resolve("world"), 0, 1), 1, 400);
        FakeBlueMap plugin = api(true, dimension, map);
        AddonLifecycle lifecycle = lifecycle(folder);

        try (LogCapture log = new LogCapture()) {
            long start = System.nanoTime();
            lifecycle.onEnable(plugin);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000, "onEnable only hands the work over");
            Await.until("the scan to start", () -> log.messages(Level.INFO).stream()
                    .anyMatch(message -> message.contains("scanning world")));

            start = System.nanoTime();
            lifecycle.onDisable(plugin);

            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "disable waits for the scan to stop, at most 5 s");
        }
        assertTrue(map.getMarkerSets().isEmpty(), "a cancelled scan stores no layers");
        assertTrue(map.storage().names().isEmpty());
        assertTrue(Files.isDirectory(folder.resolve("state")), "the regions the cancelled scan finished are kept");
    }

    @Test
    void markerSetsStayWhenThePluginDisables() throws IOException {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        FakeBlueMap plugin = api(true, TestWorlds.withMonument(temp.resolve("world")), map);
        AddonLifecycle lifecycle = lifecycle(addonFolder("addon", null));
        lifecycle.onEnable(plugin);
        Await.until("the layers", () -> has(map, MONUMENT_SET));

        lifecycle.onDisable(plugin);

        assertTrue(has(map, MONUMENT_SET), "BlueMap saves the marker sets after disable: they must still be there");
    }

    @Test
    void theCommandLineWaitsForTheScanSoThatItsFinalSaveHoldsTheLayers() throws IOException {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        FakeBlueMap commandLine = api(false, TestWorlds.withChunks(temp.resolve("world"), 60), map);
        AddonLifecycle lifecycle = lifecycle(addonFolder("addon", "{\"maxChunksPerSecond\": 40}"));

        lifecycle.onEnable(commandLine);
        lifecycle.onDisable(commandLine);

        assertTrue(has(map, VILLAGE_SET), "the layers are published when disable returns");
        assertTrue(map.storage().names().contains(LAYERS_ASSET));
    }

    @Test
    void stoppingTheProcessCancelsTheScanThatTheCommandLineWaitsFor() throws Exception {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        FakeBlueMap commandLine = api(false, TestWorlds.withChunks(temp.resolve("world"), 400), map);
        AddonLifecycle lifecycle = lifecycle(addonFolder("addon", "{\"maxChunksPerSecond\": 20}"));

        try (LogCapture log = new LogCapture()) {
            lifecycle.onEnable(commandLine);
            Await.until("the scan to start", () -> log.messages(Level.INFO).stream()
                    .anyMatch(message -> message.contains("scanning world")));
            Thread waiting = new Thread(() -> lifecycle.onDisable(commandLine));
            waiting.start();
            Await.until("the wait to be logged", () -> log.messages(Level.INFO).stream()
                    .anyMatch(message -> message.contains("waiting for the scan")));
            assertTrue(waiting.isAlive(), "the command line is still waiting");

            lifecycle.cancelScan();

            waiting.join(Duration.ofSeconds(5));
            assertFalse(waiting.isAlive(), "the wait ended after the current chunk");
        }
        assertTrue(map.getMarkerSets().isEmpty());
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static Set<String> listing(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
