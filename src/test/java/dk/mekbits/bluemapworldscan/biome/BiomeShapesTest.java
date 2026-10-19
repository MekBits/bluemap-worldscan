package dk.mekbits.bluemapworldscan.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;

class BiomeShapesTest {

    private static final String BIOME = "minecraft:plains";

    /** A state whose region files hold the given chunk indexes (x + 32 * z) of one biome. */
    private static ScanState state(String region, int... chunkIndexes) {
        BitSet bits = new BitSet(1024);
        for (int index : chunkIndexes) {
            bits.set(index);
        }
        int[] timestamps = new int[1024];
        Arrays.fill(timestamps, -1);
        TreeMap<String, long[]> biomes = new TreeMap<>();
        biomes.put(BIOME, Arrays.copyOf(bits.toLongArray(), 16));
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        regions.put(region, new RegionEntry(timestamps, List.of(), biomes));
        return new ScanState(ScanState.FORMAT_VERSION, "world-1", regions);
    }

    private static List<Polygon> plains(ScanState state, int cellSizeChunks) {
        Map<String, List<Polygon>> shapes = BiomeShapes.build(state, cellSizeChunks);
        return shapes.get(BIOME);
    }

    @Test
    void twoByTwoCellBlockIsOnePolygonWithFourCorners() {
        // Chunks (0,0), (1,0), (0,1) and (1,1) of r.0.0.mca, cells of one chunk.
        List<Polygon> polygons = plains(state("r.0.0.mca", 0, 1, 32, 33), 1);

        assertEquals(1, polygons.size());
        Polygon polygon = polygons.get(0);
        assertEquals(0, polygon.getNumInteriorRing());
        assertEquals(4, polygon.getExteriorRing().getNumPoints() - 1, "corners of the closed ring");
        assertEquals(32 * 32, polygon.getArea(), 1e-9);
    }

    @Test
    void ringOfCellsIsOnePolygonWithOneHole() {
        // The eight chunks around (1,1) of r.0.0.mca, cells of one chunk.
        List<Polygon> polygons = plains(state("r.0.0.mca", 0, 1, 2, 32, 34, 64, 65, 66), 1);

        assertEquals(1, polygons.size());
        Polygon polygon = polygons.get(0);
        assertEquals(1, polygon.getNumInteriorRing());
        assertEquals(8 * 16 * 16, polygon.getArea(), 1e-9);
        // Four corners and the closing point each: no collinear vertices on the shell or the hole.
        assertEquals(5, polygon.getExteriorRing().getNumPoints());
        assertEquals(5, polygon.getInteriorRingN(0).getNumPoints());
    }

    @Test
    void twoDiagonalCellsAreTwoPolygons() {
        // Chunks (0,0) and (1,1) of r.0.0.mca: they touch only at a corner.
        List<Polygon> polygons = plains(state("r.0.0.mca", 0, 33), 1);

        assertEquals(2, polygons.size());
        assertEquals(0, polygons.get(0).getNumInteriorRing());
        assertEquals(0, polygons.get(1).getNumInteriorRing());
    }

    @Test
    void cellOfFourChunksWithOneChunkSetIsASixtyFourBlockSquare() {
        // Chunk (0,0) of r.0.0.mca in cells of four chunks: the cell covers blocks 0 to 63 both ways.
        List<Polygon> polygons = plains(state("r.0.0.mca", 0), 4);

        assertEquals(1, polygons.size());
        Envelope envelope = polygons.get(0).getEnvelopeInternal();
        assertEquals(0, envelope.getMinX(), 1e-9);
        assertEquals(0, envelope.getMinY(), 1e-9);
        assertEquals(64, envelope.getMaxX(), 1e-9);
        assertEquals(64, envelope.getMaxY(), 1e-9);
        assertEquals(64 * 64, polygons.get(0).getArea(), 1e-9);
    }

    @Test
    void chunksAcrossARegionBorderMergeIntoOnePolygon() {
        // Chunk 31 of r.0.0.mca (x 31) and chunk 0 of r.1.0.mca (x 32) are neighbours.
        Map<String, RegionEntry> regions = new TreeMap<>();
        ScanState west = state("r.0.0.mca", 31);
        ScanState east = state("r.1.0.mca", 0);
        regions.putAll(west.regions());
        regions.putAll(east.regions());
        ScanState both = new ScanState(ScanState.FORMAT_VERSION, "world-1", new TreeMap<>(regions));

        List<Polygon> polygons = plains(both, 1);

        assertEquals(1, polygons.size());
        Envelope envelope = polygons.get(0).getEnvelopeInternal();
        assertEquals(31 * 16, envelope.getMinX(), 1e-9);
        assertEquals(33 * 16, envelope.getMaxX(), 1e-9);
        assertEquals(2 * 16 * 16, polygons.get(0).getArea(), 1e-9);
    }
}
