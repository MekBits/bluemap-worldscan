package dk.mekbits.bluemapworldscan.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.layer.ServedSize;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
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

    @Test
    void aRegionFarFromTheOriginKeepsItsPlaceInsteadOfWrappingAround() {
        // Region x 70,000,000 is chunk x 2,240,000,000, which an int cannot hold.
        List<Polygon> polygons = plains(state("r.70000000.-70000000.mca", 0), 1);

        Envelope envelope = polygons.get(0).getEnvelopeInternal();
        assertEquals(70_000_000d * 512, envelope.getMinX(), 1e-3);
        assertEquals(70_000_000d * 512 + 16, envelope.getMaxX(), 1e-3);
        assertEquals(-70_000_000d * 512, envelope.getMinY(), 1e-3);
    }

    @Test
    void cellsOfARegionFarFromTheOriginStillMergeWithTheirNeighbours() {
        List<Polygon> polygons = plains(state("r.70000000.0.mca", 0, 1), 1);

        assertEquals(1, polygons.size());
        assertEquals(2 * 16 * 16, polygons.get(0).getArea(), 1e-3);
    }

    private static List<Polygon> plains(ScanState state, int cellSizeChunks, long maxCells, long maxBytes) {
        return BiomeShapes.build(state, cellSizeChunks, biome -> true, maxCells, maxBytes).get(BIOME);
    }

    @Test
    void regionsTooFarApartForTheGridGetNoPolygonsAndAWarningThatALargerCellFixes() {
        // Region x 70,000,000 is 2,240,000,000 chunks from region x 0: more than a packed cell holds.
        Map<String, RegionEntry> regions = new TreeMap<>();
        regions.putAll(state("r.0.0.mca", 0).regions());
        regions.putAll(state("r.70000000.0.mca", 0).regions());
        ScanState farApart = new ScanState(ScanState.FORMAT_VERSION, "world-1", new TreeMap<>(regions));

        try (LogCapture log = new LogCapture()) {
            assertTrue(BiomeShapes.build(farApart, 1).isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("biomes.cellSizeChunks")
                    && message.contains("too far apart") && message.contains("world-1")), log.warnings().toString());
        }
        assertEquals(2, BiomeShapes.build(farApart, 32).get(BIOME).size(), "a larger cell fixes it");
    }

    @Test
    void aWorldWithExactlyTheMostCellsIsBuilt() {
        assertEquals(1, plains(state("r.0.0.mca", 0, 1, 2), 1, 3, Long.MAX_VALUE).size());
    }

    @Test
    void aWorldWithOneCellMoreThanTheMostGetsNoPolygonsAndAWarningThatALargerCellFixes() {
        try (LogCapture log = new LogCapture()) {
            Map<String, List<Polygon>> shapes = BiomeShapes.build(state("r.0.0.mca", 0, 1, 2), 1, biome -> true, 2, Long.MAX_VALUE);

            assertTrue(shapes.isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("biomes.cellSizeChunks")
                    && message.contains("larger") && message.contains("world-1")), log.warnings().toString());
        }
    }

    @Test
    void aLargerCellBringsTheSameChunksUnderTheCellLimit() {
        assertEquals(1, plains(state("r.0.0.mca", 0, 1, 2, 32, 33, 34), 2, 2, Long.MAX_VALUE).size());
    }

    @Test
    void polygonsEstimatedAtExactlyTheMostBytesAreKept() {
        // A square of four corners: one area of 365 bytes and four corners of 21.
        long square = ServedSize.AREA_BYTES + 4L * ServedSize.CORNER_BYTES;

        assertEquals(1, plains(state("r.0.0.mca", 0, 1, 32, 33), 1, Long.MAX_VALUE, square).size());
    }

    @Test
    void polygonsEstimatedAtOneByteMoreThanTheMostGetNoPolygonsAndAWarningThatALargerCellFixes() {
        long square = ServedSize.AREA_BYTES + 4L * ServedSize.CORNER_BYTES;

        try (LogCapture log = new LogCapture()) {
            Map<String, List<Polygon>> shapes = BiomeShapes.build(
                    state("r.0.0.mca", 0, 1, 32, 33), 1, biome -> true, Long.MAX_VALUE, square - 1);

            assertTrue(shapes.isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("biomes.cellSizeChunks") && message.contains("larger")),
                    log.warnings().toString());
        }
    }

    @Test
    void theCornersOfHolesCountTowardsTheEstimate() {
        // The ring of eight chunks: eight corners with its hole.
        ScanState ring = state("r.0.0.mca", 0, 1, 2, 32, 34, 64, 65, 66);
        long withHole = ServedSize.AREA_BYTES + 8L * ServedSize.CORNER_BYTES;

        assertEquals(1, plains(ring, 1, Long.MAX_VALUE, withHole).size());
        assertTrue(BiomeShapes.build(ring, 1, biome -> true, Long.MAX_VALUE, withHole - 1).isEmpty());
    }

    @Test
    void theEstimateAddsUpOverTheBiomes() {
        long square = ServedSize.AREA_BYTES + 4L * ServedSize.CORNER_BYTES;
        ScanState one = state("r.0.0.mca", 0, 1, 32, 33);
        TreeMap<String, long[]> biomes = new TreeMap<>(one.regions().get("r.0.0.mca").biomes());
        biomes.put("minecraft:forest", biomes.get(BIOME));
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        regions.put("r.0.0.mca", new RegionEntry(one.regions().get("r.0.0.mca").timestamps(), List.of(), biomes));
        ScanState two = new ScanState(ScanState.FORMAT_VERSION, "world-1", regions);

        assertEquals(2, BiomeShapes.build(two, 1, biome -> true, Long.MAX_VALUE, 2 * square).size());
        assertTrue(BiomeShapes.build(two, 1, biome -> true, Long.MAX_VALUE, 2 * square - 1).isEmpty());
    }

    @Test
    void theCellLimitLeavesRoomForTheMeasuredOverworldAtFourChunksPerCell() {
        assertTrue(BiomeShapes.MAX_CELLS >= 2 * 617_761L, "617,761 pairs measured at 4 chunks per cell");
    }
}
