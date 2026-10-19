package dk.mekbits.bluemapworldscan.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import org.junit.jupiter.api.Test;

class ScanPassTest {

    @Test
    void theSizeWarningNamesTheSettingsThatShrinkTheLayers() {
        String message = ScanPass.oversizedLayersMessage("overworld", 9L << 20);

        assertTrue(message.contains("overworld"), message);
        assertTrue(message.contains("9 MB"), message);
        assertTrue(message.contains("structures.disabledLayers"), message);
        assertTrue(message.contains("biomes.cellSizeChunks"), message);
        assertTrue(message.contains("biomes.disabled"), message);
    }

    @Test
    void theEstimateCountsPointMarkersShapesAndTheirCorners() {
        Poi poi = new Poi("1_70_1", "Village", 1, 70, 1, "d", null, 11, 11, 5000);
        Area area = new Area("0", "Plains", 63f, List.of(new int[] {0, 0}, new int[] {0, 64}, new int[] {64, 64}, new int[] {64, 0}),
                List.of(List.of(new int[] {16, 16}, new int[] {32, 16}, new int[] {32, 32})), 0x73336699);
        LayersFile layers = new LayersFile(LayersFile.FORMAT_VERSION, List.of(
                new LayerSet("a", "A", false, 0, List.of(poi, poi)),
                new LayerSet("b", "B", true, 1, List.of(), List.of(area))));

        assertEquals(2 * 500 + 330 + 23 * 7, ScanPass.estimatedServedBytes(layers));
    }
}
