package dk.mekbits.bluemapworldscan.chunk;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns the structure start keys stored in a chunk into current structure ids.
 *
 * <p>Up to DataVersion 2969 a start is keyed by the old structure name in any case ({@code Village},
 * {@code Jungle_Pyramid}, {@code endcity}); from 2970 (the structures became configured structures)
 * the key is the id. An old name maps to the id that Minecraft's data fixer falls back to. The
 * variants the fixer picks by biome (village, ocean ruin, shipwreck, mineshaft, ruined portal)
 * all fall in grouped layers, so the fallback changes nothing a user sees. The tables are id pairs
 * only.
 */
public final class StructureIds {

    public static final String END_CITY = "minecraft:end_city";

    private static final int CONFIGURED_STRUCTURES = 2970;

    private static final Map<String, String> LEGACY_KEYS = Map.ofEntries(
            Map.entry("bastion_remnant", "minecraft:bastion_remnant"),
            Map.entry("buried_treasure", "minecraft:buried_treasure"),
            Map.entry("desert_pyramid", "minecraft:desert_pyramid"),
            Map.entry("endcity", END_CITY),
            Map.entry("fortress", "minecraft:fortress"),
            Map.entry("igloo", "minecraft:igloo"),
            Map.entry("jungle_pyramid", "minecraft:jungle_pyramid"),
            Map.entry("mansion", "minecraft:mansion"),
            Map.entry("mineshaft", "minecraft:mineshaft"),
            Map.entry("monument", "minecraft:monument"),
            Map.entry("nether_fossil", "minecraft:nether_fossil"),
            Map.entry("ocean_ruin", "minecraft:ocean_ruin_cold"),
            Map.entry("pillager_outpost", "minecraft:pillager_outpost"),
            Map.entry("ruined_portal", "minecraft:ruined_portal"),
            Map.entry("shipwreck", "minecraft:shipwreck"),
            Map.entry("stronghold", "minecraft:stronghold"),
            Map.entry("swamp_hut", "minecraft:swamp_hut"),
            Map.entry("village", "minecraft:village_plains"));

    /** The structures of Minecraft 26.3 except the abandoned camps (its {@code data/minecraft/worldgen/structure}). */
    private static final List<String> STRUCTURES = List.of(
            "ancient_city", "bastion_remnant", "buried_treasure", "desert_pyramid", "end_city", "fortress",
            "igloo", "jungle_pyramid", "mansion", "mineshaft", "mineshaft_mesa", "monument", "nether_fossil",
            "ocean_ruin_cold", "ocean_ruin_warm", "pillager_outpost", "ruined_portal", "ruined_portal_desert",
            "ruined_portal_jungle", "ruined_portal_mountain", "ruined_portal_nether", "ruined_portal_ocean",
            "ruined_portal_swamp", "shipwreck", "shipwreck_beached", "stronghold", "swamp_hut", "trail_ruins",
            "trial_chambers", "village_desert", "village_plains", "village_savanna", "village_snowy",
            "village_taiga");

    /** Minecraft 26.3 added one abandoned camp structure per biome, {@code abandoned_camp_<biome>}. */
    private static final List<String> ABANDONED_CAMP_BIOMES = List.of(
            "bamboo_jungle", "birch_forest", "cherry_grove", "dappled_forest", "flower_forest", "forest",
            "meadow", "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "pale_garden", "savanna", "snowy_taiga", "sparse_jungle", "swamp", "taiga", "windswept_forest",
            "wooded_badlands");

    private static final Set<String> KNOWN = Stream.concat(
                    STRUCTURES.stream(),
                    ABANDONED_CAMP_BIOMES.stream().map(biome -> "abandoned_camp_" + biome))
            .map(path -> "minecraft:" + path)
            .collect(Collectors.toUnmodifiableSet());

    private StructureIds() {
    }

    /**
     * The current id of a structure start.
     *
     * @param key the key of the start in the chunk's structure starts
     * @param dataVersion the DataVersion of the chunk
     * @return the id; a key not in the tables is passed through (lower-cased below 2970, and
     *     namespaced)
     */
    public static String current(String key, int dataVersion) {
        if (dataVersion >= CONFIGURED_STRUCTURES) {
            return ResourceNames.qualified(key);
        }
        String lowerCased = key.toLowerCase(Locale.ROOT);
        String legacy = LEGACY_KEYS.get(lowerCased);
        return legacy != null ? legacy : ResourceNames.qualified(lowerCased);
    }

    /** Whether the id is a structure of Minecraft 26.3. */
    public static boolean known(String id) {
        return KNOWN.contains(id);
    }
}
