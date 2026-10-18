package dk.mekbits.bluemapworldscan.bluemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryBlueMapMap;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

class MapLayersTest {

    private final InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
    private final MapLayers layers = new MapLayers(map, map.storage(), new LayerPublisher());

    private void store(long generation, String... setIds) throws IOException {
        List<LayerSet> sets = Arrays.stream(setIds)
                .map(id -> new LayerSet(id, id, false, 0,
                        List.of(new Poi("1_70_1", id, 1, 70, 1, "d", null, 11, 11, 5000))))
                .toList();
        LayerStore.write(map.storage(), new LayersFile(1, sets), new Manifest(1, generation, "scanner-1", "1.0.0", "sha"));
    }

    @Test
    void nothingStoredPublishesNothing() {
        assertFalse(layers.refreshIfNewer());
        assertTrue(map.getMarkerSets().isEmpty());
    }

    @Test
    void aStoredGenerationIsPublished() throws IOException {
        store(1, "village");

        assertTrue(layers.refreshIfNewer());

        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void theSameGenerationAgainDoesNothing() throws IOException {
        store(1, "village");
        layers.refreshIfNewer();
        map.getMarkerSets().remove("village");

        assertFalse(layers.refreshIfNewer());

        assertTrue(map.getMarkerSets().isEmpty(), "the set was not published again");
    }

    @Test
    void aNewerGenerationReplacesTheSets() throws IOException {
        store(1, "village", "igloo");
        layers.refreshIfNewer();

        store(2, "village");

        assertTrue(layers.refreshIfNewer());
        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void aGenerationThatCannotBeReadKeepsTheSetsAndIsLoggedOnce() throws IOException {
        store(1, "village");
        layers.refreshIfNewer();
        store(2, "village", "igloo");
        map.storage().put("bluemap-worldscan-layers.json.gz", new byte[] {1, 2, 3});

        try (LogCapture log = new LogCapture()) {
            assertFalse(layers.refreshIfNewer());
            assertFalse(layers.refreshIfNewer());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void aGenerationThatCanBeReadLaterIsPublished() throws IOException {
        store(1, "village");
        layers.refreshIfNewer();
        store(2, "village", "igloo");
        byte[] good = map.storage().bytes("bluemap-worldscan-layers.json.gz");
        map.storage().put("bluemap-worldscan-layers.json.gz", new byte[] {1, 2, 3});
        layers.refreshIfNewer();

        map.storage().put("bluemap-worldscan-layers.json.gz", good);

        assertTrue(layers.refreshIfNewer());
        assertEquals(Set.of("village", "igloo"), map.getMarkerSets().keySet());
    }

    @Test
    void aManifestThatCannotBeReadKeepsTheSetsAndIsLoggedOnce() throws IOException {
        store(1, "village");
        layers.refreshIfNewer();
        map.storage().put("bluemap-worldscan-manifest.json", "garbage".getBytes(StandardCharsets.UTF_8));

        try (LogCapture log = new LogCapture()) {
            assertFalse(layers.refreshIfNewer());
            assertFalse(layers.refreshIfNewer());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void aGenerationOfAnUnknownFormatTellsToUpdateTheAddon() {
        map.storage().put("bluemap-worldscan-manifest.json",
                "{\"formatVersion\":2,\"generation\":5,\"scannerId\":\"s\",\"addonVersion\":\"2.0.0\",\"layersSha1\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8));

        try (LogCapture log = new LogCapture()) {
            assertFalse(layers.refreshIfNewer());
            assertTrue(log.warnings().getFirst().contains("update bluemap-worldscan"), log.warnings().toString());
        }
    }

    @Test
    void aMapThatBlueMapReloadedIsSkippedAndTriedAgainLater() throws IOException {
        store(1, "village");
        map.reload();

        try (LogCapture log = new LogCapture()) {
            assertFalse(layers.refreshIfNewer());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
        }
    }
}
