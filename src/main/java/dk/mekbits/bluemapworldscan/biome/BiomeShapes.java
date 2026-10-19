package dk.mekbits.bluemapworldscan.biome;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import dk.mekbits.bluemapworldscan.layer.ServedSize;
import dk.mekbits.bluemapworldscan.region.RegionFile;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.util.Log;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

/**
 * Turns the biome bits of a scan state into polygons, one set per biome.
 *
 * <p>The world is cut into square cells of {@code cellSizeChunks} chunks each way. A cell counts
 * for a biome when any chunk in it has the biome. The cells become rectangles (horizontal runs
 * of cells first), which are unioned with JTS, so a biome area is one polygon per connected piece
 * with its holes as interior rings. Coordinates are block coordinates: x is the block x and y is
 * the block z.
 */
public final class BiomeShapes {

    /** The most biomes one world gets layers for; vanilla has about 65 and a data pack adds few. */
    public static final int MAX_BIOMES = 512;
    /**
     * The most the biome areas of one world may take in {@code live/markers.json}, estimated from the
     * finished polygons with {@link ServedSize}.
     */
    public static final long MAX_ESTIMATED_BYTES = 100_000_000L;
    /**
     * The most (biome, cell) pairs held before the union. A pair in a {@code TreeSet} takes 72
     * bytes (measured), so this allows about 144 MB of heap. Wildwood's overworld has 617,761
     * pairs at 4 chunks per cell, 222,876 at 8, 1,890,859 at 2 and 6,301,195 at 1.
     */
    public static final long MAX_CELLS = 2_000_000L;

    private static final int REGION_CHUNKS = 32;
    private static final int BLOCKS_PER_CHUNK = 16;

    private BiomeShapes() {
    }

    /** The polygons of every biome in the state; see {@link #build(ScanState, int, Predicate)}. */
    public static Map<String, List<Polygon>> build(ScanState state, int cellSizeChunks) {
        return build(state, cellSizeChunks, biome -> true);
    }

    /** {@link #build(ScanState, int, Predicate, long, long)} with the limits of {@link #MAX_CELLS} and {@link #MAX_ESTIMATED_BYTES}. */
    public static Map<String, List<Polygon>> build(ScanState state, int cellSizeChunks, Predicate<String> wanted) {
        return build(state, cellSizeChunks, wanted, MAX_CELLS, MAX_ESTIMATED_BYTES);
    }

    /**
     * The polygons of each wanted biome in the state, keyed by biome id in sorted order. A biome
     * with no chunk in the state has no entry.
     *
     * <p>A world with more than {@link #MAX_BIOMES} wanted biomes gets the first ones by id and a
     * warning. Two guards give no polygons and a warning that names {@code biomes.cellSizeChunks}:
     * more than {@code maxCells} (biome, cell) pairs, counted while they are collected and before
     * anything is unioned, which bounds the heap; and polygons that are estimated at more than
     * {@code maxBytes} of markers, which bounds the payload.
     *
     * @param state the scan state, its regions named {@code r.<x>.<z>.mca}
     * @param cellSizeChunks the side of a cell in chunks, at least 1
     * @param wanted which biome ids to build
     * @param maxCells the most (biome, cell) pairs
     * @param maxBytes the most estimated bytes of markers
     * @throws IllegalArgumentException when {@code cellSizeChunks} is below 1 or a region name is
     *     not a region file name
     */
    public static Map<String, List<Polygon>> build(
            ScanState state, int cellSizeChunks, Predicate<String> wanted, long maxCells, long maxBytes) {
        if (cellSizeChunks < 1) {
            throw new IllegalArgumentException("cellSizeChunks must be at least 1: " + cellSizeChunks);
        }
        Set<String> biomes = firstBiomes(state, wanted);
        Map<String, TreeSet<Cell>> cellsByBiome = new TreeMap<>();
        long pairs = 0;
        for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
            int[] origin = regionOrigin(region.getKey());
            for (Map.Entry<String, long[]> biome : region.getValue().biomes().entrySet()) {
                if (!biomes.contains(biome.getKey())) {
                    continue;
                }
                TreeSet<Cell> cells = cellsByBiome.computeIfAbsent(biome.getKey(), key -> new TreeSet<>());
                BitSet bits = BitSet.valueOf(biome.getValue());
                for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
                    // Long: a region file name may hold any int, and times 32 that does not fit one.
                    long chunkX = (long) origin[0] * REGION_CHUNKS + index % REGION_CHUNKS;
                    long chunkZ = (long) origin[1] * REGION_CHUNKS + index / REGION_CHUNKS;
                    if (cells.add(new Cell(Math.floorDiv(chunkX, (long) cellSizeChunks), Math.floorDiv(chunkZ, (long) cellSizeChunks)))
                            && ++pairs > maxCells) {
                        Log.warn("the biome layers of world " + state.worldId() + " are skipped: at biomes.cellSizeChunks "
                                + cellSizeChunks + " there are more than " + maxCells + " cells to work out. "
                                + "A larger biomes.cellSizeChunks fixes this");
                        return Map.of();
                    }
                }
            }
        }

        GeometryFactory factory = new GeometryFactory();
        SortedMap<String, List<Polygon>> shapes = new TreeMap<>();
        long estimatedBytes = 0;
        for (Map.Entry<String, TreeSet<Cell>> biome : cellsByBiome.entrySet()) {
            if (biome.getValue().isEmpty()) {
                continue;
            }
            List<Polygon> polygons = polygons(biome.getValue(), cellSizeChunks, factory);
            estimatedBytes += servedBytes(polygons);
            if (estimatedBytes > maxBytes) {
                Log.warn("the biome layers of world " + state.worldId() + " are skipped: at biomes.cellSizeChunks "
                        + cellSizeChunks + " they would take more than " + maxBytes / 1_000_000
                        + " MB of markers. A larger biomes.cellSizeChunks fixes this");
                return Map.of();
            }
            shapes.put(biome.getKey(), polygons);
        }
        return shapes;
    }

    /** About how many bytes the polygons take in {@code live/markers.json}, as one area each. */
    private static long servedBytes(List<Polygon> polygons) {
        long bytes = 0;
        for (Polygon polygon : polygons) {
            long corners = polygon.getExteriorRing().getNumPoints() - 1;
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                corners += polygon.getInteriorRingN(i).getNumPoints() - 1;
            }
            bytes += ServedSize.AREA_BYTES + corners * ServedSize.CORNER_BYTES;
        }
        return bytes;
    }

    /** The first {@link #MAX_BIOMES} wanted biome ids of the state in id order; a longer list is logged. */
    private static Set<String> firstBiomes(ScanState state, Predicate<String> wanted) {
        TreeSet<String> all = new TreeSet<>();
        for (RegionEntry region : state.regions().values()) {
            for (String biome : region.biomes().keySet()) {
                if (wanted.test(biome)) {
                    all.add(biome);
                }
            }
        }
        if (all.size() <= MAX_BIOMES) {
            return all;
        }
        Set<String> first = new TreeSet<>();
        for (String biome : all) {
            if (first.size() == MAX_BIOMES) {
                break;
            }
            first.add(biome);
        }
        Log.warn("world " + state.worldId() + " has " + all.size() + " biomes; only the first " + MAX_BIOMES
                + " by id get a layer, " + (all.size() - MAX_BIOMES) + " are left out");
        return first;
    }

    /** The region's origin in region coordinates, {x, z}. */
    private static int[] regionOrigin(String fileName) {
        int[] coords = RegionFile.coords(fileName);
        if (coords == null) {
            throw new IllegalArgumentException("not a region file name: " + fileName);
        }
        return coords;
    }

    /** The union of the rectangles of the cells, one polygon per piece. The cells are in row order. */
    private static List<Polygon> polygons(TreeSet<Cell> cells, int cellSizeChunks, GeometryFactory factory) {
        List<Geometry> rectangles = new ArrayList<>();
        Cell runStart = null;
        Cell previous = null;
        for (Cell cell : cells) {
            boolean continuesRun = previous != null && previous.z() == cell.z() && previous.x() + 1 == cell.x();
            if (!continuesRun) {
                if (runStart != null) {
                    rectangles.add(rectangle(runStart, previous, cellSizeChunks, factory));
                }
                runStart = cell;
            }
            previous = cell;
        }
        rectangles.add(rectangle(runStart, previous, cellSizeChunks, factory));

        // Tolerance 0 drops only the collinear vertices the union leaves on shared edges; no vertex moves.
        Geometry union = TopologyPreservingSimplifier.simplify(CascadedPolygonUnion.union(rectangles), 0.0);
        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < union.getNumGeometries(); i++) {
            if (union.getGeometryN(i) instanceof Polygon polygon) {
                polygons.add(polygon);
            }
        }
        return List.copyOf(polygons);
    }

    /** The rectangle of the run of cells from {@code first} to {@code last} in one row, in block coordinates. */
    private static Geometry rectangle(Cell first, Cell last, int cellSizeChunks, GeometryFactory factory) {
        double span = (double) cellSizeChunks * BLOCKS_PER_CHUNK;
        return factory.toGeometry(new Envelope(
                first.x() * span,
                (last.x() + 1) * span,
                first.z() * span,
                (first.z() + 1) * span));
    }

    /** A square of cells by cell coordinates, ordered by row (z) and then column (x). */
    private record Cell(long x, long z) implements Comparable<Cell> {

        @Override
        public int compareTo(Cell other) {
            return z != other.z ? Long.compare(z, other.z) : Long.compare(x, other.x);
        }
    }
}
