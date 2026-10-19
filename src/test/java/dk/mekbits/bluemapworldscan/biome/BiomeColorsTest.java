package dk.mekbits.bluemapworldscan.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dk.mekbits.bluemapworldscan.chunk.BiomeIds;
import org.junit.jupiter.api.Test;

class BiomeColorsTest {

    /** The smallest "redmean" colour distance (0 to 765) any two biomes may have. */
    private static final double MIN_DISTANCE = 40;

    @Test
    void everyBiomeOfMinecraft263HasAColourOfItsOwn() {
        assertEquals(67, BiomeIds.knownIds().size());
        for (String id : BiomeIds.knownIds()) {
            assertTrue(BiomeColors.known(id), id);
        }
    }

    @Test
    void noTwoBiomesHaveColoursThatAreHardToTellApart() {
        List<String> ids = new ArrayList<>(BiomeIds.knownIds());
        ids.sort(null);
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                double distance = distance(BiomeColors.rgb(ids.get(i)), BiomeColors.rgb(ids.get(j)));
                assertTrue(distance >= MIN_DISTANCE, ids.get(i) + " and " + ids.get(j) + " are only " + distance + " apart");
            }
        }
    }

    @Test
    void theColoursAreWhatAPlayerExpects() {
        assertTrue(blue(BiomeColors.rgb("minecraft:ocean")) > red(BiomeColors.rgb("minecraft:ocean")));
        assertTrue(blue(BiomeColors.rgb("minecraft:deep_ocean")) < blue(BiomeColors.rgb("minecraft:ocean")), "deeper is darker");
        assertTrue(red(BiomeColors.rgb("minecraft:nether_wastes")) > blue(BiomeColors.rgb("minecraft:nether_wastes")));
        assertTrue(red(BiomeColors.rgb("minecraft:desert")) > 200 && blue(BiomeColors.rgb("minecraft:desert")) < 100);
        assertEquals(0xFF00FF, BiomeColors.rgb("minecraft:mushroom_fields"));
    }

    @Test
    void aBiomeNotInTheTableGetsTheSameColourFromItsIdEveryTime() {
        int first = BiomeColors.rgb("some_pack:crystal_caves");

        assertFalse(BiomeColors.known("some_pack:crystal_caves"));
        assertEquals(first, BiomeColors.rgb("some_pack:crystal_caves"));
        assertEquals(0, first & ~0xFFFFFF, "an RGB colour");
        assertNotEquals(first, BiomeColors.rgb("some_pack:fungal_caves"));
    }

    @Test
    void theFillIsTheColourAtAboutHalfOpacity() {
        int argb = BiomeColors.fillArgb("minecraft:plains");

        assertEquals(0x8DB360, argb & 0xFFFFFF);
        assertEquals(Math.round(0.45f * 255), argb >>> 24);
    }

    private static int red(int rgb) {
        return rgb >> 16 & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static double distance(int first, int second) {
        double meanRed = (red(first) + red(second)) / 2.0;
        double dr = red(first) - red(second);
        double dg = (first >> 8 & 0xFF) - (second >> 8 & 0xFF);
        double db = blue(first) - blue(second);
        return Math.sqrt((2 + meanRed / 256) * dr * dr + 4 * dg * dg + (2 + (255 - meanRed) / 256) * db * db);
    }
}
