package dk.mekbits.bluemapworldscan.bluemap;

import static dk.mekbits.bluemapworldscan.testsupport.LoadedAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryAssetStorage;
import dk.mekbits.bluemapworldscan.util.Loaded;
import org.junit.jupiter.api.Test;

class LayerStoreTest {

    private static final Manifest MANIFEST = new Manifest(1, 1_700_000_000_000L, "scanner-1", "1.0.0", "abc123");

    private final InMemoryAssetStorage storage = new InMemoryAssetStorage("overworld");

    private static LayersFile layers() {
        Poi poi = new Poi("4_71_4", "Village", 4, 71, 4, "<div>\"quoted\" & <code>/tp @s 4 71 4</code></div>",
                "maps/overworld/assets/v.png", 11, 11, 5000);
        Poi withoutIcon = new Poi("9_60_9", "Igloo", 9, 60, 9, "x", null, 11, 11, 5000);
        return new LayersFile(LayersFile.FORMAT_VERSION, List.of(
                new LayerSet("worldscan-structure-village", "Village", false, 0, List.of(poi, withoutIcon)),
                new LayerSet("worldscan-structure-igloo", "Igloo", true, 1, List.of(withoutIcon))));
    }

    private static LayersFile layersWithAnArea() {
        Area area = new Area("0", "Plains", 63f, List.of(new int[] {0, 0}, new int[] {64, 0}, new int[] {64, 64}),
                List.of(List.of(new int[] {10, 10}, new int[] {20, 10}, new int[] {20, 20})), 0x73abcdef);
        return new LayersFile(LayersFile.FORMAT_VERSION, List.of(
                layers().sets().getFirst(),
                new LayerSet("worldscan-biome-minecraft-plains", "Biome: Plains", true, 1, List.of(), List.of(area))));
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void theLayersAndTheManifestRoundTrip() throws IOException {
        LayersFile written = layers();

        LayerStore.write(storage, written, MANIFEST);

        LayersFile read = assertOk(LayerStore.readLayers(storage));
        assertEquals(written, read);
        assertEquals(MANIFEST, assertOk(LayerStore.readManifest(storage)));
    }

    @Test
    void anAreaRoundTripsWithItsRingsAndColour() throws IOException {
        LayerStore.write(storage, layersWithAnArea(), MANIFEST);

        LayersFile read = assertOk(LayerStore.readLayers(storage));

        assertEquals(layersWithAnArea().sets().getFirst(), read.sets().getFirst());
        Area area = read.sets().get(1).areas().getFirst();
        assertEquals("Plains", area.label());
        assertEquals(63f, area.y());
        assertEquals(0x73abcdef, area.fillArgb());
        assertArrayEquals(new int[] {64, 64}, area.outline().get(2));
        assertArrayEquals(new int[] {20, 20}, area.holes().getFirst().get(2));
        assertEquals(List.of(), read.sets().get(1).pois());
    }

    @Test
    void layersOfFormatVersionOneFromRelease100AreReadWithoutAreas() throws IOException {
        // Written by release 1.0.0: version 1 and sets without an "areas" key.
        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":1,\"sets\":[{\"id\":\"worldscan-structure-igloo\","
                + "\"label\":\"Igloo\",\"defaultHidden\":true,\"sorting\":0,\"pois\":[{\"id\":\"9_60_9\",\"label\":\"Igloo\","
                + "\"x\":9,\"y\":60,\"z\":9,\"detail\":\"x\",\"anchorX\":11,\"anchorY\":11,\"maxDistance\":5000.0}]}]}"));

        LayersFile read = assertOk(LayerStore.readLayers(storage));

        assertEquals(1, read.formatVersion());
        assertEquals(List.of(new Poi("9_60_9", "Igloo", 9, 60, 9, "x", null, 11, 11, 5000)), read.sets().getFirst().pois());
        assertEquals(List.of(), read.sets().getFirst().areas());
    }

    @Test
    void layersAreWrittenInFormatVersionTwo() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);

        assertEquals(2, LayersFile.FORMAT_VERSION);
        assertEquals(2, assertOk(LayerStore.readLayers(storage)).formatVersion());
    }

    @Test
    void theLayersAreGzipJsonAndTheManifestIsPlainJson() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);

        assertEquals(0x1f, storage.bytes("bluemap-worldscan-layers.json.gz")[0] & 0xff);
        assertTrue(new String(storage.bytes("bluemap-worldscan-manifest.json"), StandardCharsets.UTF_8)
                .contains("\"layersSha1\":\"abc123\""));
    }

    @Test
    void theManifestIsWrittenAfterTheLayers() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);

        assertEquals(List.of("bluemap-worldscan-layers.json.gz", "bluemap-worldscan-manifest.json"), storage.completedWrites());
    }

    @Test
    void whenTheLayersCannotBeWrittenTheManifestIsLeftAsItWas() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);
        byte[] oldManifest = storage.bytes("bluemap-worldscan-manifest.json");
        storage.failWritesOf("bluemap-worldscan-layers.json.gz");

        assertThrows(IOException.class, () -> LayerStore.write(
                storage, layers(), new Manifest(1, MANIFEST.generation() + 1, "scanner-1", "1.0.0", "def456")));

        assertArrayEquals(oldManifest, storage.bytes("bluemap-worldscan-manifest.json"));
    }

    @Test
    void nothingStoredIsMissing() {
        assertInstanceOf(Loaded.Missing.class, LayerStore.readLayers(storage));
        assertInstanceOf(Loaded.Missing.class, LayerStore.readManifest(storage));
    }

    @Test
    void garbageIsFailedNotAnException() {
        storage.put("bluemap-worldscan-layers.json.gz", new byte[] {1, 2, 3, 4});
        storage.put("bluemap-worldscan-manifest.json", "not json".getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
        assertInstanceOf(Loaded.Failed.class, LayerStore.readManifest(storage));
    }

    @Test
    void aTruncatedLayersFileIsFailed() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);
        byte[] whole = storage.bytes("bluemap-worldscan-layers.json.gz");
        storage.put("bluemap-worldscan-layers.json.gz", Arrays.copyOf(whole, whole.length / 2));

        assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
    }

    @Test
    void aManifestWithMissingFieldsIsFailed() {
        storage.put("bluemap-worldscan-manifest.json", "{\"formatVersion\":1,\"generation\":5}".getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(Loaded.Failed.class, LayerStore.readManifest(storage));
    }

    @Test
    void aFormatVersionThisAddonDoesNotKnowIsFailedWithAnUpdateHint() throws IOException {
        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":3,\"sets\":[]}"));
        storage.put("bluemap-worldscan-manifest.json",
                "{\"formatVersion\":2,\"generation\":5,\"scannerId\":\"s\",\"addonVersion\":\"2.0.0\",\"layersSha1\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8));

        Loaded.Failed<LayersFile> layers = assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
        Loaded.Failed<Manifest> manifest = assertInstanceOf(Loaded.Failed.class, LayerStore.readManifest(storage));

        assertTrue(layers.reason().contains("update bluemap-worldscan"), layers.reason());
        assertTrue(manifest.reason().contains("update bluemap-worldscan"), manifest.reason());
    }

    @Test
    void onlyAFormatNewerThanThisAddonsCountsAsNewer() throws IOException {
        assertFalse(LayerStore.holdsNewerFormat(storage), "nothing stored");

        LayerStore.write(storage, layers(), MANIFEST);
        assertFalse(LayerStore.holdsNewerFormat(storage), "this addon's own files");

        storage.put("bluemap-worldscan-manifest.json", "not json".getBytes(StandardCharsets.UTF_8));
        assertFalse(LayerStore.holdsNewerFormat(storage), "a damaged manifest");

        storage.put("bluemap-worldscan-manifest.json", "{\"formatVersion\":0}".getBytes(StandardCharsets.UTF_8));
        assertFalse(LayerStore.holdsNewerFormat(storage), "an older manifest");

        storage.put("bluemap-worldscan-manifest.json", "{\"formatVersion\":2,\"shape\":\"changed\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(LayerStore.holdsNewerFormat(storage), "a newer manifest, even with other fields");
    }

    @Test
    void newerLayersUnderAnOwnManifestCountAsNewer() throws IOException {
        LayerStore.write(storage, layers(), MANIFEST);
        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":3,\"sets\":[]}"));

        assertTrue(LayerStore.holdsNewerFormat(storage));
    }

    @Test
    void layersOfFormatVersionOneAreNotNewerAndLayersOfVersionZeroAreFailed() throws IOException {
        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":1,\"sets\":[]}"));
        assertFalse(LayerStore.holdsNewerFormat(storage), "a scanner replaces the layers of release 1.0.0");
        assertOk(LayerStore.readLayers(storage));

        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":0,\"sets\":[]}"));
        assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
    }

    @Test
    void anEmptyLayersFileIsFailed() throws IOException {
        storage.put("bluemap-worldscan-layers.json.gz", gzip(""));

        assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
    }
}
