package dk.mekbits.bluemapworldscan.biome;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * Turns the biome bits of a scan state into polygons, one set per biome.
 *
 * <p>The world is cut into square cells of {@code cellSizeChunks} chunks each way. A cell counts
 * for a biome when any chunk in it has the biome. The outlines of the cells are traced on the grid,
 * so a biome area is one polygon per connected piece with its holes as interior rings, and only the
 * corners are kept. Cells that share a side are one piece; cells that touch only at a corner are
 * two, and a hole may touch its shell or another hole at such a corner, as in a JTS union of the
 * cells. Coordinates are block coordinates: x is the block x and y is the block z.
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
     * The most (biome, cell) pairs held before the outlines are traced. A pair takes 16 to 32 bytes
     * in the set the cells are collected in, so this allows at most 64 MB of heap. The measured overworld has 617,761
     * pairs at 4 chunks per cell, 222,876 at 8, 1,890,859 at 2 and 6,301,195 at 1.
     */
    public static final long MAX_CELLS = 2_000_000L;

    private static final int REGION_CHUNKS = 32;
    private static final int BLOCKS_PER_CHUNK = 16;
    /** A cell is packed as {@code z << 32 | x} from the world's lowest cell, so neither may reach 2^31. */
    private static final long MAX_SPAN = Integer.MAX_VALUE - 2L;

    private static final int EAST = 0;
    private static final int NORTH = 1;
    private static final int WEST = 2;
    private static final int SOUTH = 3;

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
     * anything is traced, which bounds the heap; and polygons that are estimated at more than
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
        Grid grid = Grid.of(state, biomes, cellSizeChunks);
        if (grid == null) {
            return Map.of();
        }
        if (grid.spanX > MAX_SPAN || grid.spanZ > MAX_SPAN) {
            Log.warn("the biome layers of world " + state.worldId() + " are skipped: its region files are too far apart "
                    + "to be laid out in cells of " + cellSizeChunks + " chunks. A larger biomes.cellSizeChunks fixes this");
            return Map.of();
        }

        Map<String, LongSet> cellsByBiome = new TreeMap<>();
        long pairs = 0;
        long[] regionCells = new long[REGION_CHUNKS * REGION_CHUNKS];
        for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
            int[] origin = regionOrigin(region.getKey());
            for (Map.Entry<String, long[]> biome : region.getValue().biomes().entrySet()) {
                if (!biomes.contains(biome.getKey())) {
                    continue;
                }
                LongSet cells = cellsByBiome.computeIfAbsent(biome.getKey(), key -> new LongSet());
                int count = grid.cellsOf(origin, biome.getValue(), regionCells);
                for (int i = 0; i < count; i++) {
                    if (cells.add(regionCells[i]) && ++pairs > maxCells) {
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
        for (Map.Entry<String, LongSet> biome : cellsByBiome.entrySet()) {
            if (biome.getValue().size() == 0) {
                continue;
            }
            long[] cells = biome.getValue().sorted();
            biome.setValue(null);
            List<Polygon> polygons = new Tracer(cells, grid, factory).polygons();
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

    /**
     * The cells of a world, packed as {@code (z - minZ) << 32 | (x - minX)}: sorted keys are in row
     * order, and the cell east of a key is the key plus one.
     */
    private static final class Grid {
        final int cellSizeChunks;
        final long minX;
        final long minZ;
        final long spanX;
        final long spanZ;

        private Grid(int cellSizeChunks, long minX, long minZ, long spanX, long spanZ) {
            this.cellSizeChunks = cellSizeChunks;
            this.minX = minX;
            this.minZ = minZ;
            this.spanX = spanX;
            this.spanZ = spanZ;
        }

        /** The grid that holds every region with one of the biomes, or null when there is none. */
        static Grid of(ScanState state, Set<String> biomes, int cellSizeChunks) {
            long minX = Long.MAX_VALUE;
            long minZ = Long.MAX_VALUE;
            long maxX = Long.MIN_VALUE;
            long maxZ = Long.MIN_VALUE;
            for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
                if (region.getValue().biomes().keySet().stream().noneMatch(biomes::contains)) {
                    continue;
                }
                int[] origin = regionOrigin(region.getKey());
                // Long: a region file name may hold any int, and times 32 that does not fit one.
                long chunkX = (long) origin[0] * REGION_CHUNKS;
                long chunkZ = (long) origin[1] * REGION_CHUNKS;
                minX = Math.min(minX, Math.floorDiv(chunkX, (long) cellSizeChunks));
                minZ = Math.min(minZ, Math.floorDiv(chunkZ, (long) cellSizeChunks));
                maxX = Math.max(maxX, Math.floorDiv(chunkX + REGION_CHUNKS - 1, (long) cellSizeChunks));
                maxZ = Math.max(maxZ, Math.floorDiv(chunkZ + REGION_CHUNKS - 1, (long) cellSizeChunks));
            }
            if (minX == Long.MAX_VALUE) {
                return null;
            }
            return new Grid(cellSizeChunks, minX, minZ, maxX - minX, maxZ - minZ);
        }

        /** The distinct cells of the chunks set in a region's biome bits, written to {@code out}; how many. */
        int cellsOf(int[] origin, long[] bits, long[] out) {
            int count = 0;
            long baseX = (long) origin[0] * REGION_CHUNKS;
            long baseZ = (long) origin[1] * REGION_CHUNKS;
            for (int word = 0; word < bits.length; word++) {
                for (long rest = bits[word]; rest != 0; rest &= rest - 1) {
                    int index = word * Long.SIZE + Long.numberOfTrailingZeros(rest);
                    long cellX = Math.floorDiv(baseX + index % REGION_CHUNKS, (long) cellSizeChunks);
                    long cellZ = Math.floorDiv(baseZ + index / REGION_CHUNKS, (long) cellSizeChunks);
                    long key = pack(cellX - minX, cellZ - minZ);
                    // A region's chunks give their cells in row order, so a repeat is mostly the cell just written.
                    if (count == 0 || out[count - 1] != key) {
                        out[count++] = key;
                    }
                }
            }
            return count;
        }

        double blockX(long packedX) {
            return (double) (packedX + minX) * cellSizeChunks * BLOCKS_PER_CHUNK;
        }

        double blockZ(long packedZ) {
            return (double) (packedZ + minZ) * cellSizeChunks * BLOCKS_PER_CHUNK;
        }
    }

    private static long pack(long x, long z) {
        return z << 32 | x;
    }

    private static long xOf(long key) {
        return key & 0xFFFFFFFFL;
    }

    private static long zOf(long key) {
        return key >>> 32;
    }

    /**
     * Traces the outlines of one biome's cells. Every side of a cell that has no cell beside it is
     * an edge, directed so that the cell is on its left; following the edges gives counter-clockwise
     * shells and clockwise holes. Where two cells touch only at a corner, two edges leave that
     * corner: the trace turns left, around its own cell, when the two cells belong to different
     * pieces, and right, joining them, when they belong to the same piece. That is what keeps a
     * shell from touching itself, as JTS requires.
     */
    private static final class Tracer {
        private final long[] cells;
        private final Grid grid;
        private final GeometryFactory factory;
        private final int[] parent;

        private long[] edgeStart = new long[64];
        private int[] edgeCell = new int[64];
        private byte[] edgeDirection = new byte[64];
        private int edges;
        /** The edges by start corner: at most two, at a corner where two cells touch diagonally. */
        private final LongIntMap edgesByStart = new LongIntMap();
        private int[] secondEdge = new int[64];

        Tracer(long[] cells, Grid grid, GeometryFactory factory) {
            this.cells = cells;
            this.grid = grid;
            this.factory = factory;
            this.parent = new int[cells.length];
        }

        List<Polygon> polygons() {
            joinPieces();
            collectEdges();

            boolean[] used = new boolean[edges];
            Map<Integer, LinearRing> shells = new TreeMap<>();
            Map<Integer, List<LinearRing>> holes = new TreeMap<>();
            for (int first = 0; first < edges; first++) {
                if (used[first]) {
                    continue;
                }
                List<Long> corners = new ArrayList<>();
                long area2 = trace(first, used, corners);
                int piece = find(edgeCell[first]);
                LinearRing ring = ring(corners);
                if (area2 > 0) {
                    shells.put(piece, ring);
                } else {
                    holes.computeIfAbsent(piece, key -> new ArrayList<>()).add(ring);
                }
            }

            // Pieces in the order of their first cell, so the same cells always give the same list.
            List<Integer> pieces = new ArrayList<>(shells.keySet());
            pieces.sort((a, b) -> Long.compare(firstCellOf(a), firstCellOf(b)));
            List<Polygon> polygons = new ArrayList<>(pieces.size());
            for (int piece : pieces) {
                List<LinearRing> pieceHoles = holes.getOrDefault(piece, List.of());
                polygons.add(factory.createPolygon(shells.get(piece), pieceHoles.toArray(LinearRing[]::new)));
            }
            return List.copyOf(polygons);
        }

        private long firstCellOf(int piece) {
            return cells[piece];
        }

        /** Union-find over cells that share a side; a piece is named by its root, the index of its first cell. */
        private void joinPieces() {
            for (int i = 0; i < cells.length; i++) {
                parent[i] = i;
            }
            for (int i = 0; i < cells.length; i++) {
                if (i + 1 < cells.length && cells[i + 1] == cells[i] + 1 && xOf(cells[i]) + 1 == xOf(cells[i + 1])) {
                    union(i, i + 1);
                }
                int north = indexOf(cells[i] + (1L << 32));
                if (north >= 0) {
                    union(i, north);
                }
            }
        }

        private void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA != rootB) {
                // The lower index stays the root, so a piece's root is its first cell in row order.
                parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
            }
        }

        private int find(int cell) {
            int root = cell;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[cell] != root) {
                int next = parent[cell];
                parent[cell] = root;
                cell = next;
            }
            return root;
        }

        private int indexOf(long key) {
            int index = Arrays.binarySearch(cells, key);
            return index >= 0 ? index : -1;
        }

        private boolean has(long x, long z) {
            return x >= 0 && z >= 0 && indexOf(pack(x, z)) >= 0;
        }

        /** The sides of each cell with no cell beside them, as edges with the cell on their left. */
        private void collectEdges() {
            for (int i = 0; i < cells.length; i++) {
                long x = xOf(cells[i]);
                long z = zOf(cells[i]);
                if (!has(x, z - 1)) {
                    addEdge(pack(x, z), i, EAST);
                }
                if (!has(x + 1, z)) {
                    addEdge(pack(x + 1, z), i, NORTH);
                }
                if (!has(x, z + 1)) {
                    addEdge(pack(x + 1, z + 1), i, WEST);
                }
                if (!has(x - 1, z)) {
                    addEdge(pack(x, z + 1), i, SOUTH);
                }
            }
        }

        private void addEdge(long start, int cell, int direction) {
            if (edges == edgeStart.length) {
                int size = edges * 2;
                edgeStart = Arrays.copyOf(edgeStart, size);
                edgeCell = Arrays.copyOf(edgeCell, size);
                edgeDirection = Arrays.copyOf(edgeDirection, size);
                secondEdge = Arrays.copyOf(secondEdge, size);
            }
            edgeStart[edges] = start;
            edgeCell[edges] = cell;
            edgeDirection[edges] = (byte) direction;
            secondEdge[edges] = -1;
            int existing = edgesByStart.putIfAbsent(start, edges);
            if (existing >= 0) {
                secondEdge[existing] = edges;
            }
            edges++;
        }

        /**
         * Follows the edges from {@code first} back to it, adding the corners where the direction
         * changes to {@code corners}.
         *
         * @return twice the signed area: positive for a shell, negative for a hole
         */
        private long trace(int first, boolean[] used, List<Long> corners) {
            long area2 = 0;
            int edge = first;
            do {
                used[edge] = true;
                long start = edgeStart[edge];
                long end = end(start, edgeDirection[edge]);
                // Shoelace in packed coordinates; the offsets cancel out over a closed ring.
                area2 += (long) xOf(start) * (long) zOf(end) - (long) xOf(end) * (long) zOf(start);
                int next = next(edge, end);
                if (edgeDirection[next] != edgeDirection[edge]) {
                    corners.add(end);
                }
                edge = next;
            } while (edge != first);
            return area2;
        }

        /** The edge after {@code edge}, which ends at {@code corner}. */
        private int next(int edge, long corner) {
            int one = edgesByStart.get(corner);
            int two = secondEdge[one];
            if (two < 0) {
                return one;
            }
            int direction = edgeDirection[edge];
            boolean samePiece = diagonalCellsInOnePiece(corner, direction);
            int wanted = samePiece ? (direction + 3) % 4 : (direction + 1) % 4;
            return edgeDirection[one] == wanted ? one : two;
        }

        /** At a corner where two cells touch diagonally, whether they are one piece; the incoming direction tells which two. */
        private boolean diagonalCellsInOnePiece(long corner, int incoming) {
            long x = xOf(corner);
            long z = zOf(corner);
            int a;
            int b;
            if (incoming == NORTH || incoming == SOUTH) {
                // The cells north-east and south-west of the corner.
                a = indexOf(pack(x, z));
                b = indexOf(pack(x - 1, z - 1));
            } else {
                // The cells north-west and south-east of the corner.
                a = indexOf(pack(x - 1, z));
                b = indexOf(pack(x, z - 1));
            }
            return find(a) == find(b);
        }

        private static long end(long start, int direction) {
            return switch (direction) {
                case EAST -> start + 1;
                case NORTH -> start + (1L << 32);
                case WEST -> start - 1;
                default -> start - (1L << 32);
            };
        }

        private LinearRing ring(List<Long> corners) {
            Coordinate[] coordinates = new Coordinate[corners.size() + 1];
            for (int i = 0; i < corners.size(); i++) {
                long corner = corners.get(i);
                coordinates[i] = new Coordinate(grid.blockX(xOf(corner)), grid.blockZ(zOf(corner)));
            }
            coordinates[corners.size()] = coordinates[0].copy();
            return factory.createLinearRing(coordinates);
        }
    }

    /** A set of non-negative longs with open addressing, about 16 bytes an element. */
    private static final class LongSet {
        private static final long EMPTY = -1;
        private long[] slots = filled(16);
        private int size;

        boolean add(long key) {
            if (size * 2 >= slots.length) {
                grow();
            }
            int mask = slots.length - 1;
            for (int slot = mix(key) & mask; ; slot = (slot + 1) & mask) {
                if (slots[slot] == EMPTY) {
                    slots[slot] = key;
                    size++;
                    return true;
                }
                if (slots[slot] == key) {
                    return false;
                }
            }
        }

        int size() {
            return size;
        }

        long[] sorted() {
            long[] keys = new long[size];
            int count = 0;
            for (long slot : slots) {
                if (slot != EMPTY) {
                    keys[count++] = slot;
                }
            }
            Arrays.sort(keys);
            return keys;
        }

        private void grow() {
            long[] old = slots;
            slots = filled(old.length * 2);
            size = 0;
            for (long key : old) {
                if (key != EMPTY) {
                    add(key);
                }
            }
        }

        private static long[] filled(int length) {
            long[] slots = new long[length];
            Arrays.fill(slots, EMPTY);
            return slots;
        }
    }

    /** A map of non-negative longs to non-negative ints with open addressing. */
    private static final class LongIntMap {
        private static final long EMPTY = -1;
        private long[] keys = filled(64);
        private int[] values = new int[64];
        private int size;

        /** The value of the key, or -1. */
        int get(long key) {
            int mask = keys.length - 1;
            for (int slot = mix(key) & mask; ; slot = (slot + 1) & mask) {
                if (keys[slot] == key) {
                    return values[slot];
                }
                if (keys[slot] == EMPTY) {
                    return -1;
                }
            }
        }

        /** Puts the value when the key has none; the value it had, or -1. */
        int putIfAbsent(long key, int value) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            for (int slot = mix(key) & mask; ; slot = (slot + 1) & mask) {
                if (keys[slot] == EMPTY) {
                    keys[slot] = key;
                    values[slot] = value;
                    size++;
                    return -1;
                }
                if (keys[slot] == key) {
                    return values[slot];
                }
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = filled(oldKeys.length * 2);
            values = new int[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) {
                    putIfAbsent(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static long[] filled(int length) {
            long[] slots = new long[length];
            Arrays.fill(slots, EMPTY);
            return slots;
        }
    }

    /** Spreads the bits of a packed cell, whose low bits alone would cluster in a table. */
    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
