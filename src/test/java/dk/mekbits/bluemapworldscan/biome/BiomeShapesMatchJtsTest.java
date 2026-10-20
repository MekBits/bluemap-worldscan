package dk.mekbits.bluemapworldscan.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Polygon;

/**
 * The traced polygons are the polygons the JTS union of the cells gave up to 1.1.0, corner for
 * corner once both are in normal form. Random worlds of a few regions, dense enough to have holes,
 * holes that touch their shell at a corner and pieces that touch diagonally.
 */
class BiomeShapesMatchJtsTest {

    private static final int[] CELL_SIZES = {1, 2, 3, 5, 16, 33};

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void tracedPolygonsAreTheJtsUnion(int seed) {
        Random random = new Random(seed);
        ScanState state = randomWorld(random);
        for (int cellSize : CELL_SIZES) {
            Map<String, List<Polygon>> traced = BiomeShapes.build(state, cellSize);
            Map<String, List<Polygon>> unioned = JtsBiomeShapes.build(state, cellSize);

            assertEquals(normalForm(unioned), normalForm(traced), "seed " + seed + ", cells of " + cellSize + " chunks");
        }
    }

    private static ScanState randomWorld(Random random) {
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        String[] biomes = {"minecraft:plains", "minecraft:forest", "minecraft:river"};
        double[] densities = {0.15, 0.45, 0.7};
        int[] timestamps = new int[1024];
        Arrays.fill(timestamps, -1);
        for (int regionX = -1; regionX <= 1; regionX++) {
            for (int regionZ = -1; regionZ <= 0; regionZ++) {
                TreeMap<String, long[]> bits = new TreeMap<>();
                for (int b = 0; b < biomes.length; b++) {
                    long[] words = new long[16];
                    for (int index = 0; index < 1024; index++) {
                        if (random.nextDouble() < densities[b]) {
                            words[index / 64] |= 1L << (index % 64);
                        }
                    }
                    bits.put(biomes[b], words);
                }
                regions.put("r." + regionX + "." + regionZ + ".mca", new RegionEntry(timestamps, List.of(), bits));
            }
        }
        return new ScanState(ScanState.FORMAT_VERSION, "random", regions);
    }

    /** Each biome's polygons as sorted WKT of their normal form: rings from their lowest corner, holes in order. */
    private static Map<String, List<String>> normalForm(Map<String, List<Polygon>> shapes) {
        Map<String, List<String>> texts = new TreeMap<>();
        shapes.forEach((biome, polygons) -> {
            List<String> normalized = new ArrayList<>();
            for (Polygon polygon : polygons) {
                Polygon copy = (Polygon) polygon.copy();
                copy.normalize();
                normalized.add(copy.toText());
            }
            normalized.sort(null);
            texts.put(biome, normalized);
        });
        return texts;
    }
}
