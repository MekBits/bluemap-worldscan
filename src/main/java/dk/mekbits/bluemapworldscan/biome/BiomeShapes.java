package dk.mekbits.bluemapworldscan.biome;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import dk.mekbits.bluemapworldscan.region.RegionFile;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
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

    private static final int REGION_CHUNKS = 32;
    private static final int BLOCKS_PER_CHUNK = 16;

    private BiomeShapes() {
    }

    /**
     * The polygons of each biome in the state, keyed by biome id in sorted order. A biome with no
     * chunk in the state has no entry.
     *
     * @param state the scan state, its regions named {@code r.<x>.<z>.mca}
     * @param cellSizeChunks the side of a cell in chunks, at least 1
     * @throws IllegalArgumentException when {@code cellSizeChunks} is below 1 or a region name is
     *     not a region file name
     */
    public static Map<String, List<Polygon>> build(ScanState state, int cellSizeChunks) {
        if (cellSizeChunks < 1) {
            throw new IllegalArgumentException("cellSizeChunks must be at least 1: " + cellSizeChunks);
        }
        Map<String, TreeSet<Cell>> cellsByBiome = new TreeMap<>();
        for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
            int[] origin = regionOrigin(region.getKey());
            for (Map.Entry<String, long[]> biome : region.getValue().biomes().entrySet()) {
                TreeSet<Cell> cells = cellsByBiome.computeIfAbsent(biome.getKey(), key -> new TreeSet<>());
                BitSet bits = BitSet.valueOf(biome.getValue());
                for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
                    int chunkX = origin[0] * REGION_CHUNKS + index % REGION_CHUNKS;
                    int chunkZ = origin[1] * REGION_CHUNKS + index / REGION_CHUNKS;
                    cells.add(new Cell(Math.floorDiv(chunkX, cellSizeChunks), Math.floorDiv(chunkZ, cellSizeChunks)));
                }
            }
        }

        GeometryFactory factory = new GeometryFactory();
        SortedMap<String, List<Polygon>> shapes = new TreeMap<>();
        cellsByBiome.forEach((biome, cells) -> {
            if (!cells.isEmpty()) {
                shapes.put(biome, polygons(cells, cellSizeChunks, factory));
            }
        });
        return shapes;
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
    private record Cell(int x, int z) implements Comparable<Cell> {

        @Override
        public int compareTo(Cell other) {
            return z != other.z ? Integer.compare(z, other.z) : Integer.compare(x, other.x);
        }
    }
}
