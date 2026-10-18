package dk.mekbits.bluemapworldscan.chunk;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the addon takes from one chunk.
 *
 * @param dataVersion the chunk's DataVersion, -1 when it has none
 * @param kind whether the chunk could be read
 * @param chunkX the chunk's own x position, {@link Integer#MIN_VALUE} when it has none
 * @param chunkZ the chunk's own z position, {@link Integer#MIN_VALUE} when it has none
 * @param structures the structure starts, in the order of their keys
 * @param biomes the current ids of the generated biomes
 * @param unknownStructureIds structure ids that are not structures of the supported Minecraft version
 * @param unknownBiomeIds biome ids that are not biomes of the supported Minecraft version (a
 *     numeric id is given in decimal)
 */
public record ChunkInfo(
        int dataVersion,
        Kind kind,
        int chunkX,
        int chunkZ,
        List<FoundStructure> structures,
        Set<String> biomes,
        Set<String> unknownStructureIds,
        Set<String> unknownBiomeIds) {

    /** Whether the chunk is in a format the addon reads. */
    public enum Kind {
        /** A chunk from Minecraft 1.13 on. */
        MODERN,
        /** A chunk saved before 1.13, or without a DataVersion; nothing else of it is read. */
        PRE_1_13
    }

    public ChunkInfo {
        structures = List.copyOf(structures);
        biomes = sorted(biomes);
        unknownStructureIds = sorted(unknownStructureIds);
        unknownBiomeIds = sorted(unknownBiomeIds);
    }

    private static Set<String> sorted(Set<String> ids) {
        return Collections.unmodifiableSet(new TreeSet<>(ids));
    }
}
