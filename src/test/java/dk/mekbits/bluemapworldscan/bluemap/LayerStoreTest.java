package dk.mekbits.bluemapworldscan.bluemap;

import static dk.mekbits.bluemapworldscan.testsupport.LoadedAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Area area = new Area("a", "Plains", 63f, List.of(new int[] {0, 0}, new int[] {64, 0}, new int[] {64, 64}),
                List.of(List.of(new int[] {10, 10}, new int[] {20, 10}, new int[] {20, 20})), 0x73abcdef);
        return new LayersFile(1, List.of(
                new LayerSet("worldscan-structure-village", "Village", false, 0, List.of(poi, withoutIcon), List.of()),
                new LayerSet("worldscan-biome-plains", "Biome: Plains", true, 1, List.of(), List.of(area))));
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
        assertEquals(written.sets().getFirst(), read.sets().getFirst());
        Area area = read.sets().get(1).areas().getFirst();
        assertEquals("Plains", area.label());
        assertEquals(63f, area.y());
        assertEquals(0x73abcdef, area.fillArgb());
        assertArrayEquals(new int[] {64, 64}, area.outline().get(2));
        assertArrayEquals(new int[] {20, 20}, area.holes().getFirst().get(2));
        assertEquals(MANIFEST, assertOk(LayerStore.readManifest(storage)));
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
        storage.put("bluemap-worldscan-layers.json.gz", gzip("{\"formatVersion\":2,\"sets\":[]}"));
        storage.put("bluemap-worldscan-manifest.json",
                "{\"formatVersion\":2,\"generation\":5,\"scannerId\":\"s\",\"addonVersion\":\"2.0.0\",\"layersSha1\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8));

        Loaded.Failed<LayersFile> layers = assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
        Loaded.Failed<Manifest> manifest = assertInstanceOf(Loaded.Failed.class, LayerStore.readManifest(storage));

        assertTrue(layers.reason().contains("update bluemap-worldscan"), layers.reason());
        assertTrue(manifest.reason().contains("update bluemap-worldscan"), manifest.reason());
    }

    @Test
    void anEmptyLayersFileIsFailed() throws IOException {
        storage.put("bluemap-worldscan-layers.json.gz", gzip(""));

        assertInstanceOf(Loaded.Failed.class, LayerStore.readLayers(storage));
    }
}
