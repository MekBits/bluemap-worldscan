package dk.mekbits.bluemapworldscan.chunk;

import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Turns the biome ids stored in a chunk into the current Minecraft ids.
 *
 * <p>Chunks below DataVersion 2832 store a number per 4x4(x4) cell; later ones store names in a
 * palette. Names and numbers are brought to the names of Minecraft 26.3 with the renames that
 * Minecraft's data fixers apply after the chunk's DataVersion. The tables are id pairs only; the
 * numeric ones are the biome ids valid at DataVersion 2831, checked against Minecraft 26.3's client
 * jar.
 */
public final class BiomeIds {

    /** The DataVersion the numeric table is valid at; later renames apply to its names. */
    private static final int NUMERIC_TABLE_DATA_VERSION = 2831;
    /** Caves &amp; Cliffs part II renamed 38 biomes. */
    private static final int CAVES_AND_CLIFFS_RENAMES = 2838;
    /** Deep warm ocean was removed. */
    private static final int DEEP_WARM_OCEAN_REMOVED = 2843;

    private static final Map<Integer, String> NUMERIC = Map.ofEntries(
            Map.entry(0, "minecraft:ocean"),
            Map.entry(1, "minecraft:plains"),
            Map.entry(2, "minecraft:desert"),
            Map.entry(3, "minecraft:mountains"),
            Map.entry(4, "minecraft:forest"),
            Map.entry(5, "minecraft:taiga"),
            Map.entry(6, "minecraft:swamp"),
            Map.entry(7, "minecraft:river"),
            Map.entry(8, "minecraft:nether_wastes"),
            Map.entry(9, "minecraft:the_end"),
            Map.entry(10, "minecraft:frozen_ocean"),
            Map.entry(11, "minecraft:frozen_river"),
            Map.entry(12, "minecraft:snowy_tundra"),
            Map.entry(13, "minecraft:snowy_mountains"),
            Map.entry(14, "minecraft:mushroom_fields"),
            Map.entry(15, "minecraft:mushroom_field_shore"),
            Map.entry(16, "minecraft:beach"),
            Map.entry(17, "minecraft:desert_hills"),
            Map.entry(18, "minecraft:wooded_hills"),
            Map.entry(19, "minecraft:taiga_hills"),
            Map.entry(20, "minecraft:mountain_edge"),
            Map.entry(21, "minecraft:jungle"),
            Map.entry(22, "minecraft:jungle_hills"),
            Map.entry(23, "minecraft:jungle_edge"),
            Map.entry(24, "minecraft:deep_ocean"),
            Map.entry(25, "minecraft:stone_shore"),
            Map.entry(26, "minecraft:snowy_beach"),
            Map.entry(27, "minecraft:birch_forest"),
            Map.entry(28, "minecraft:birch_forest_hills"),
            Map.entry(29, "minecraft:dark_forest"),
            Map.entry(30, "minecraft:snowy_taiga"),
            Map.entry(31, "minecraft:snowy_taiga_hills"),
            Map.entry(32, "minecraft:giant_tree_taiga"),
            Map.entry(33, "minecraft:giant_tree_taiga_hills"),
            Map.entry(34, "minecraft:wooded_mountains"),
            Map.entry(35, "minecraft:savanna"),
            Map.entry(36, "minecraft:savanna_plateau"),
            Map.entry(37, "minecraft:badlands"),
            Map.entry(38, "minecraft:wooded_badlands_plateau"),
            Map.entry(39, "minecraft:badlands_plateau"),
            Map.entry(40, "minecraft:small_end_islands"),
            Map.entry(41, "minecraft:end_midlands"),
            Map.entry(42, "minecraft:end_highlands"),
            Map.entry(43, "minecraft:end_barrens"),
            Map.entry(44, "minecraft:warm_ocean"),
            Map.entry(45, "minecraft:lukewarm_ocean"),
            Map.entry(46, "minecraft:cold_ocean"),
            Map.entry(47, "minecraft:deep_warm_ocean"),
            Map.entry(48, "minecraft:deep_lukewarm_ocean"),
            Map.entry(49, "minecraft:deep_cold_ocean"),
            Map.entry(50, "minecraft:deep_frozen_ocean"),
            Map.entry(127, "minecraft:the_void"),
            Map.entry(129, "minecraft:sunflower_plains"),
            Map.entry(130, "minecraft:desert_lakes"),
            Map.entry(131, "minecraft:gravelly_mountains"),
            Map.entry(132, "minecraft:flower_forest"),
            Map.entry(133, "minecraft:taiga_mountains"),
            Map.entry(134, "minecraft:swamp_hills"),
            Map.entry(140, "minecraft:ice_spikes"),
            Map.entry(149, "minecraft:modified_jungle"),
            Map.entry(151, "minecraft:modified_jungle_edge"),
            Map.entry(155, "minecraft:tall_birch_forest"),
            Map.entry(156, "minecraft:tall_birch_hills"),
            Map.entry(157, "minecraft:dark_forest_hills"),
            Map.entry(158, "minecraft:snowy_taiga_mountains"),
            Map.entry(160, "minecraft:giant_spruce_taiga"),
            Map.entry(161, "minecraft:giant_spruce_taiga_hills"),
            Map.entry(162, "minecraft:modified_gravelly_mountains"),
            Map.entry(163, "minecraft:shattered_savanna"),
            Map.entry(164, "minecraft:shattered_savanna_plateau"),
            Map.entry(165, "minecraft:eroded_badlands"),
            Map.entry(166, "minecraft:modified_wooded_badlands_plateau"),
            Map.entry(167, "minecraft:modified_badlands_plateau"),
            Map.entry(168, "minecraft:bamboo_jungle"),
            Map.entry(169, "minecraft:bamboo_jungle_hills"),
            Map.entry(170, "minecraft:soul_sand_valley"),
            Map.entry(171, "minecraft:crimson_forest"),
            Map.entry(172, "minecraft:warped_forest"),
            Map.entry(173, "minecraft:basalt_deltas"),
            Map.entry(174, "minecraft:dripstone_caves"),
            Map.entry(175, "minecraft:lush_caves"),
            Map.entry(177, "minecraft:meadow"),
            Map.entry(178, "minecraft:grove"),
            Map.entry(179, "minecraft:snowy_slopes"),
            Map.entry(180, "minecraft:snowcapped_peaks"),
            Map.entry(181, "minecraft:lofty_peaks"),
            Map.entry(182, "minecraft:stony_peaks")
    );

    private static final Map<String, String> CAVES_AND_CLIFFS = Map.ofEntries(
            Map.entry("minecraft:badlands_plateau", "minecraft:badlands"),
            Map.entry("minecraft:bamboo_jungle_hills", "minecraft:bamboo_jungle"),
            Map.entry("minecraft:birch_forest_hills", "minecraft:birch_forest"),
            Map.entry("minecraft:dark_forest_hills", "minecraft:dark_forest"),
            Map.entry("minecraft:desert_hills", "minecraft:desert"),
            Map.entry("minecraft:desert_lakes", "minecraft:desert"),
            Map.entry("minecraft:giant_spruce_taiga_hills", "minecraft:old_growth_spruce_taiga"),
            Map.entry("minecraft:giant_spruce_taiga", "minecraft:old_growth_spruce_taiga"),
            Map.entry("minecraft:giant_tree_taiga_hills", "minecraft:old_growth_pine_taiga"),
            Map.entry("minecraft:giant_tree_taiga", "minecraft:old_growth_pine_taiga"),
            Map.entry("minecraft:gravelly_mountains", "minecraft:windswept_gravelly_hills"),
            Map.entry("minecraft:jungle_edge", "minecraft:sparse_jungle"),
            Map.entry("minecraft:jungle_hills", "minecraft:jungle"),
            Map.entry("minecraft:modified_badlands_plateau", "minecraft:badlands"),
            Map.entry("minecraft:modified_gravelly_mountains", "minecraft:windswept_gravelly_hills"),
            Map.entry("minecraft:modified_jungle_edge", "minecraft:sparse_jungle"),
            Map.entry("minecraft:modified_jungle", "minecraft:jungle"),
            Map.entry("minecraft:modified_wooded_badlands_plateau", "minecraft:wooded_badlands"),
            Map.entry("minecraft:mountain_edge", "minecraft:windswept_hills"),
            Map.entry("minecraft:mountains", "minecraft:windswept_hills"),
            Map.entry("minecraft:mushroom_field_shore", "minecraft:mushroom_fields"),
            Map.entry("minecraft:shattered_savanna", "minecraft:windswept_savanna"),
            Map.entry("minecraft:shattered_savanna_plateau", "minecraft:windswept_savanna"),
            Map.entry("minecraft:snowy_mountains", "minecraft:snowy_plains"),
            Map.entry("minecraft:snowy_taiga_hills", "minecraft:snowy_taiga"),
            Map.entry("minecraft:snowy_taiga_mountains", "minecraft:snowy_taiga"),
            Map.entry("minecraft:snowy_tundra", "minecraft:snowy_plains"),
            Map.entry("minecraft:stone_shore", "minecraft:stony_shore"),
            Map.entry("minecraft:swamp_hills", "minecraft:swamp"),
            Map.entry("minecraft:taiga_hills", "minecraft:taiga"),
            Map.entry("minecraft:taiga_mountains", "minecraft:taiga"),
            Map.entry("minecraft:tall_birch_forest", "minecraft:old_growth_birch_forest"),
            Map.entry("minecraft:tall_birch_hills", "minecraft:old_growth_birch_forest"),
            Map.entry("minecraft:wooded_badlands_plateau", "minecraft:wooded_badlands"),
            Map.entry("minecraft:wooded_hills", "minecraft:forest"),
            Map.entry("minecraft:wooded_mountains", "minecraft:windswept_forest"),
            Map.entry("minecraft:lofty_peaks", "minecraft:jagged_peaks"),
            Map.entry("minecraft:snowcapped_peaks", "minecraft:frozen_peaks")
    );

    private static final Map<String, String> DEEP_WARM_OCEAN = Map.of("minecraft:deep_warm_ocean", "minecraft:warm_ocean");

    /** The biomes of Minecraft 26.3 (its {@code data/minecraft/worldgen/biome}), also the minecraft.wiki's list. */
    private static final Set<String> KNOWN = Set.of(
            "minecraft:badlands", "minecraft:bamboo_jungle", "minecraft:basalt_deltas",
            "minecraft:beach", "minecraft:birch_forest", "minecraft:cherry_grove",
            "minecraft:cold_ocean", "minecraft:crimson_forest", "minecraft:dappled_forest",
            "minecraft:dark_forest", "minecraft:deep_cold_ocean", "minecraft:deep_dark",
            "minecraft:deep_frozen_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:deep_ocean",
            "minecraft:desert", "minecraft:dripstone_caves", "minecraft:end_barrens",
            "minecraft:end_highlands", "minecraft:end_midlands", "minecraft:eroded_badlands",
            "minecraft:flower_forest", "minecraft:forest", "minecraft:frozen_ocean",
            "minecraft:frozen_peaks", "minecraft:frozen_river", "minecraft:grove",
            "minecraft:ice_spikes", "minecraft:jagged_peaks", "minecraft:jungle",
            "minecraft:lukewarm_ocean", "minecraft:lush_caves", "minecraft:mangrove_swamp",
            "minecraft:meadow", "minecraft:mushroom_fields", "minecraft:nether_wastes",
            "minecraft:ocean", "minecraft:old_growth_birch_forest", "minecraft:old_growth_pine_taiga",
            "minecraft:old_growth_spruce_taiga", "minecraft:pale_garden", "minecraft:plains",
            "minecraft:river", "minecraft:savanna", "minecraft:savanna_plateau",
            "minecraft:small_end_islands", "minecraft:snowy_beach", "minecraft:snowy_plains",
            "minecraft:snowy_slopes", "minecraft:snowy_taiga", "minecraft:soul_sand_valley",
            "minecraft:sparse_jungle", "minecraft:stony_peaks", "minecraft:stony_shore",
            "minecraft:sulfur_caves", "minecraft:sunflower_plains", "minecraft:swamp",
            "minecraft:taiga", "minecraft:the_end", "minecraft:the_void",
            "minecraft:warm_ocean", "minecraft:warped_forest", "minecraft:windswept_forest",
            "minecraft:windswept_gravelly_hills", "minecraft:windswept_hills", "minecraft:windswept_savanna",
            "minecraft:wooded_badlands"
    );

    private BiomeIds() {
    }

    /**
     * The current id of a stored biome.
     *
     * @param name the palette name of a chunk from DataVersion 2832, or null for a numeric id
     * @param numericId the id stored in a chunk below DataVersion 2832; ignored when {@code name} is set
     * @param dataVersion the DataVersion of the chunk
     * @return the id in Minecraft 26.3's naming; a name not in the tables is passed through
     *     (namespaced); null for a numeric id the table does not hold
     */
    public static @Nullable String current(@Nullable String name, int numericId, int dataVersion) {
        if (name != null) {
            return renamed(ResourceNames.qualified(name), dataVersion);
        }
        String legacy = NUMERIC.get(numericId);
        return legacy == null ? null : renamed(legacy, NUMERIC_TABLE_DATA_VERSION);
    }

    /** Whether the id is a biome of Minecraft 26.3. */
    public static boolean known(String id) {
        return KNOWN.contains(id);
    }

    /** The biomes of Minecraft 26.3. */
    public static Set<String> knownIds() {
        return KNOWN;
    }

    private static String renamed(String id, int dataVersion) {
        String renamed = id;
        if (dataVersion < CAVES_AND_CLIFFS_RENAMES) {
            renamed = CAVES_AND_CLIFFS.getOrDefault(renamed, renamed);
        }
        if (dataVersion < DEEP_WARM_OCEAN_REMOVED) {
            renamed = DEEP_WARM_OCEAN.getOrDefault(renamed, renamed);
        }
        return renamed;
    }
}
