package dk.mekbits.bluemapworldscan.biome;

import java.awt.Color;
import java.util.Map;

/**
 * The fill colour of each biome area on the map.
 *
 * <p>The table covers the 67 biomes of Minecraft 26.3, in the colours a player associates with
 * them (water blues, sand yellow, nether red, end purple), kept apart from each other so that two
 * neighbouring biomes can be told apart; {@code BiomeColorsTest} checks both. A biome that is not
 * in the table (a datapack's, or one added after 26.3) gets a colour derived from its id, which is
 * the same on every instance and in every run.
 */
public final class BiomeColors {

    /** How opaque the areas are, so the map shows through. */
    public static final float FILL_ALPHA = 0.45f;

    private static final Map<String, Integer> TABLE = Map.ofEntries(
            // Overworld land
            Map.entry("minecraft:plains", 0x8DB360),
            Map.entry("minecraft:sunflower_plains", 0xC6E57A),
            Map.entry("minecraft:snowy_plains", 0xFFFFFF),
            Map.entry("minecraft:ice_spikes", 0x7FD6E8),
            Map.entry("minecraft:desert", 0xFA9418),
            Map.entry("minecraft:savanna", 0xBDB25F),
            Map.entry("minecraft:savanna_plateau", 0x8F8546),
            Map.entry("minecraft:windswept_savanna", 0xE8D98A),
            Map.entry("minecraft:badlands", 0xD94515),
            Map.entry("minecraft:eroded_badlands", 0xFF8A5C),
            Map.entry("minecraft:wooded_badlands", 0x9C6B3C),
            Map.entry("minecraft:forest", 0x056621),
            Map.entry("minecraft:flower_forest", 0x3DBE6B),
            Map.entry("minecraft:birch_forest", 0x4C9A5A),
            Map.entry("minecraft:old_growth_birch_forest", 0x86C9A0),
            Map.entry("minecraft:dark_forest", 0x3B4A12),
            Map.entry("minecraft:pale_garden", 0xC4CCBA),
            Map.entry("minecraft:cherry_grove", 0xF5A6CF),
            Map.entry("minecraft:dappled_forest", 0xA8C050),
            Map.entry("minecraft:taiga", 0x0B7F70),
            Map.entry("minecraft:snowy_taiga", 0x4D7C8C),
            Map.entry("minecraft:old_growth_pine_taiga", 0x7A6A3E),
            Map.entry("minecraft:old_growth_spruce_taiga", 0x6F9488),
            Map.entry("minecraft:jungle", 0x2C7A0F),
            Map.entry("minecraft:sparse_jungle", 0x7FA82A),
            Map.entry("minecraft:bamboo_jungle", 0x96C83A),
            Map.entry("minecraft:swamp", 0x2E8F7A),
            Map.entry("minecraft:mangrove_swamp", 0x5A7A3A),
            // Mountains
            Map.entry("minecraft:windswept_hills", 0x606A70),
            Map.entry("minecraft:windswept_forest", 0x3F7F5F),
            Map.entry("minecraft:windswept_gravelly_hills", 0xB4B4B4),
            Map.entry("minecraft:meadow", 0x58C27A),
            Map.entry("minecraft:grove", 0x9CCDB8),
            Map.entry("minecraft:snowy_slopes", 0xB8CCE0),
            Map.entry("minecraft:jagged_peaks", 0xE1DCCB),
            Map.entry("minecraft:frozen_peaks", 0x7E96C8),
            Map.entry("minecraft:stony_peaks", 0x8C8C94),
            // Shores, rivers, mushrooms
            Map.entry("minecraft:mushroom_fields", 0xFF00FF),
            Map.entry("minecraft:beach", 0xF5DE6A),
            Map.entry("minecraft:snowy_beach", 0xFAF0C8),
            Map.entry("minecraft:stony_shore", 0xA59A7A),
            Map.entry("minecraft:river", 0x2F6FE0),
            Map.entry("minecraft:frozen_river", 0x9DB4FF),
            // Oceans
            Map.entry("minecraft:ocean", 0x2038A8),
            Map.entry("minecraft:deep_ocean", 0x0C1650),
            Map.entry("minecraft:cold_ocean", 0x3A7CB8),
            Map.entry("minecraft:deep_cold_ocean", 0x233F6B),
            Map.entry("minecraft:lukewarm_ocean", 0x1FA3D6),
            Map.entry("minecraft:deep_lukewarm_ocean", 0x1B66B0),
            Map.entry("minecraft:warm_ocean", 0x3ED8C3),
            Map.entry("minecraft:frozen_ocean", 0xA4A0F0),
            Map.entry("minecraft:deep_frozen_ocean", 0x5A5FA8),
            // Caves
            Map.entry("minecraft:dripstone_caves", 0x8A5A2B),
            Map.entry("minecraft:lush_caves", 0xA0E83C),
            Map.entry("minecraft:deep_dark", 0x004D40),
            Map.entry("minecraft:sulfur_caves", 0xE8D020),
            // Nether
            Map.entry("minecraft:nether_wastes", 0x7A2B2B),
            Map.entry("minecraft:soul_sand_valley", 0x6E5F52),
            Map.entry("minecraft:crimson_forest", 0xD41F3B),
            Map.entry("minecraft:warped_forest", 0x14B8A6),
            Map.entry("minecraft:basalt_deltas", 0x4A4A55),
            // End
            Map.entry("minecraft:the_end", 0x5B4BC4),
            Map.entry("minecraft:small_end_islands", 0xC7B8FF),
            Map.entry("minecraft:end_highlands", 0xF2E7A0),
            Map.entry("minecraft:end_midlands", 0xD8A64E),
            Map.entry("minecraft:end_barrens", 0xA07CC0),
            Map.entry("minecraft:the_void", 0x2B2B2B)
    );

    private BiomeColors() {
    }

    /** Whether the biome has a colour of its own in the table. */
    public static boolean known(String biomeId) {
        return TABLE.containsKey(biomeId);
    }

    /** The colour of the biome as 0xRRGGBB. */
    public static int rgb(String biomeId) {
        Integer colour = TABLE.get(biomeId);
        return colour != null ? colour : fromId(biomeId);
    }

    /** The fill colour of the biome as 0xAARRGGBB, with {@link #FILL_ALPHA} as the opacity. */
    public static int fillArgb(String biomeId) {
        int alpha = Math.round(FILL_ALPHA * 255);
        return (alpha << 24) | rgb(biomeId);
    }

    /** A colour from the id: the hue follows its hash, the saturation and lightness are fixed. */
    private static int fromId(String biomeId) {
        float hue = Math.floorMod(biomeId.hashCode(), 360) / 360f;
        return Color.HSBtoRGB(hue, 0.65f, 0.85f) & 0xFFFFFF;
    }
}
