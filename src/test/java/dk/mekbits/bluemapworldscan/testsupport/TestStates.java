package dk.mekbits.bluemapworldscan.testsupport;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;

/** Scan states made by hand, for tests of what is built from a state. */
public final class TestStates {

    private TestStates() {
    }

    /** A structure with a box {minX, minY, minZ, maxX, maxY, maxZ} in the chunk with the given index. */
    public static StructureEntry structure(int chunkIndex, String structureId, int... box) {
        return new StructureEntry(chunkIndex, structureId, box, null);
    }

    /** An end city with the box of its ship. */
    public static StructureEntry endCity(int chunkIndex, int[] box, int[] shipBox) {
        return new StructureEntry(chunkIndex, "minecraft:end_city", box, shipBox);
    }

    /** A state of the given structures by region file name. */
    public static ScanState state(Map<String, List<StructureEntry>> structuresByRegion) {
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        structuresByRegion.forEach((name, structures) -> {
            int[] timestamps = new int[1024];
            Arrays.fill(timestamps, -1);
            regions.put(name, new RegionEntry(timestamps, List.copyOf(structures), new TreeMap<>()));
        });
        return new ScanState(ScanState.FORMAT_VERSION, "world-1", regions);
    }

    /** A state of the given structures in one region. */
    public static ScanState state(StructureEntry... structures) {
        return state(Map.of("r.0.0.mca", List.of(structures)));
    }

    /**
     * A state of one region, {@code r.0.0.mca}, whose chunks have the given biomes.
     *
     * @param chunkIndexesByBiome the chunk indexes (x + 32 * z) of each biome id
     */
    public static ScanState biomes(Map<String, int[]> chunkIndexesByBiome) {
        TreeMap<String, long[]> biomes = new TreeMap<>();
        chunkIndexesByBiome.forEach((biome, indexes) -> {
            BitSet bits = new BitSet(1024);
            for (int index : indexes) {
                bits.set(index);
            }
            biomes.put(biome, Arrays.copyOf(bits.toLongArray(), 16));
        });
        int[] timestamps = new int[1024];
        Arrays.fill(timestamps, -1);
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        regions.put("r.0.0.mca", new RegionEntry(timestamps, List.of(), biomes));
        return new ScanState(ScanState.FORMAT_VERSION, "world-1", regions);
    }
}
