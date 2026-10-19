package dk.mekbits.bluemapworldscan.chunk;

import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Whether the biomes stored in a chunk were generated. A chunk that has not reached the biome step
 * still stores a palette filled with plains, which must not be mistaken for the world's biomes.
 *
 * <p>This is a deny-list, so status names added after the biome step (such as
 * {@code initialize_light} or 26.3's {@code terrain}) need no table entry.
 */
public final class ChunkStatuses {

    /** From this DataVersion a chunk with the status {@code biomes} has its biomes stored. */
    private static final int BIOMES_STATUS_HAS_BIOMES_FROM = 1911;

    private static final Set<String> BEFORE_BIOMES = Set.of("empty", "structure_starts", "structure_references");

    private ChunkStatuses() {
    }

    /**
     * @param status the chunk's status, with or without {@code minecraft:}, or null when it has none
     * @param retrogenTargetStatus {@code below_zero_retrogen.target_status}, or null; an upgraded
     *     pre-1.18 chunk keeps its real status there and has {@code empty} as its status
     * @param dataVersion the DataVersion of the chunk
     */
    public static boolean biomesGenerated(@Nullable String status, @Nullable String retrogenTargetStatus, int dataVersion) {
        String effective = retrogenTargetStatus != null ? retrogenTargetStatus : status;
        if (effective == null) {
            return false;
        }
        String name = ResourceNames.withoutNamespace(effective);
        if (BEFORE_BIOMES.contains(name)) {
            return false;
        }
        // Below 1911 the data fixer treats a chunk with the status "biomes" like an "empty" one.
        return dataVersion >= BIOMES_STATUS_HAS_BIOMES_FROM || !name.equals("biomes");
    }
}
