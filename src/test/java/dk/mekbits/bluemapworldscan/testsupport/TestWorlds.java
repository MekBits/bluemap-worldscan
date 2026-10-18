package dk.mekbits.bluemapworldscan.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import dk.mekbits.bluemapworldscan.testsupport.TestRegions.TestChunk;

/** Dimension folders with region files, for tests that scan a world. */
public final class TestWorlds {

    private static final int TIMESTAMP = 1_700_000_000;
    private static final String MONUMENT = "overworld-1.18.2plus-struct-monument-dv4903-c.-440.-27";

    private TestWorlds() {
    }

    /** A world whose only chunk is a real ocean monument (chunk -440, -27) in region {@code r.-14.-1}. */
    public static Path withMonument(Path dimension) throws IOException {
        // Chunk -440, -27 sits at x 8, z 5 of its region.
        TestRegions.write(regionFile(dimension, "r.-14.-1.mca"),
                Map.of(8 + 32 * 5, new TestChunk(ChunkFixtures.bytes(MONUMENT), TIMESTAMP, 2, false)));
        return dimension;
    }

    /**
     * Adds region {@code r.<regionX>.0} of {@code chunkCount} plains chunks to a world, the first
     * of which holds a village; with a low {@code maxChunksPerSecond} its scan takes a known time.
     */
    public static Path withChunks(Path dimension, int regionX, int chunkCount) throws IOException {
        Map<Integer, TestChunk> chunks = new HashMap<>();
        for (int index = 0; index < chunkCount; index++) {
            String[] structures = index == 0 ? new String[] {"minecraft:village_plains"} : new String[0];
            byte[] nbt = SyntheticChunks.chunk(4903, regionX * 32 + index % 32, index / 32, "minecraft:plains", structures);
            chunks.put(index, new TestChunk(nbt, TIMESTAMP, 2, false));
        }
        TestRegions.write(regionFile(dimension, "r." + regionX + ".0.mca"), chunks);
        return dimension;
    }

    /** A world of {@code chunkCount} plains chunks in region {@code r.0.0}. */
    public static Path withChunks(Path dimension, int chunkCount) throws IOException {
        return withChunks(dimension, 0, chunkCount);
    }

    private static Path regionFile(Path dimension, String name) throws IOException {
        return Files.createDirectories(dimension.resolve("region")).resolve(name);
    }
}
