package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BiomeIdsTest {

    @ParameterizedTest
    @CsvSource({
        "0, minecraft:ocean",
        "3, minecraft:windswept_hills",
        "8, minecraft:nether_wastes",
        "9, minecraft:the_end",
        "40, minecraft:small_end_islands",
        "42, minecraft:end_highlands",
        "47, minecraft:warm_ocean",
        "127, minecraft:the_void",
        "170, minecraft:soul_sand_valley",
        "171, minecraft:crimson_forest",
        "172, minecraft:warped_forest",
        "173, minecraft:basalt_deltas",
        "174, minecraft:dripstone_caves",
        "175, minecraft:lush_caves",
        "180, minecraft:frozen_peaks",
        "181, minecraft:jagged_peaks"
    })
    void aNumericIdGetsTheNameItHasInMinecraft26_3(int numericId, String expected) {
        assertEquals(expected, BiomeIds.current(null, numericId, 2586));
    }

    @Test
    void numericIdsWithoutAnEntryAreNull() {
        assertNull(BiomeIds.current(null, 176, 2586));
        assertNull(BiomeIds.current(null, 250, 2586));
        assertNull(BiomeIds.current(null, 51, 2586));
        assertNull(BiomeIds.current(null, -1, 2586));
    }

    @Test
    void everyNumericIdEndsAsABiomeOfMinecraft26_3() {
        int named = 0;
        for (int numericId = 0; numericId <= 182; numericId++) {
            String id = BiomeIds.current(null, numericId, 2586);
            if (id != null) {
                named++;
                assertTrue(BiomeIds.known(id), numericId + " -> " + id);
            }
        }
        assertEquals(87, named);
    }

    @Test
    void aPaletteNameRenamedByCavesAndCliffsGetsItsNewNameBelowDataVersion2838() {
        assertEquals("minecraft:windswept_hills", BiomeIds.current("minecraft:mountains", -1, 2837));
        assertEquals("minecraft:mountains", BiomeIds.current("minecraft:mountains", -1, 2838));
    }

    @Test
    void deepWarmOceanBecomesWarmOceanBelowDataVersion2843() {
        assertEquals("minecraft:warm_ocean", BiomeIds.current("minecraft:deep_warm_ocean", -1, 2842));
        assertEquals("minecraft:deep_warm_ocean", BiomeIds.current("minecraft:deep_warm_ocean", -1, 2843));
    }

    @Test
    void aSnapshotNameBothRenamesTouchGetsBothRenames() {
        assertEquals("minecraft:jagged_peaks", BiomeIds.current("minecraft:lofty_peaks", -1, 2832));
    }

    @Test
    void aNameInAnotherNamespaceIsNotRenamed() {
        assertEquals("pack:mountains", BiomeIds.current("pack:mountains", -1, 2832));
    }

    @Test
    void aBareNameGetsTheMinecraftNamespace() {
        assertEquals("minecraft:plains", BiomeIds.current("plains", -1, 4903));
    }

    @Test
    void theNumericIdIsIgnoredWhenThereIsAName() {
        assertEquals("minecraft:plains", BiomeIds.current("minecraft:plains", 3, 4903));
    }

    @Test
    void theBiomesOfMinecraft26_3AreKnown() {
        assertTrue(BiomeIds.known("minecraft:sulfur_caves"));
        assertTrue(BiomeIds.known("minecraft:dappled_forest"));
        assertTrue(BiomeIds.known("minecraft:pale_garden"));
        assertTrue(BiomeIds.known("minecraft:the_void"));
        assertNotNull(BiomeIds.current("minecraft:plains", -1, 4903));
    }

    @Test
    void renamedAndForeignIdsAreNotKnown() {
        assertFalse(BiomeIds.known("minecraft:mountains"));
        assertFalse(BiomeIds.known("minecraft:deep_warm_ocean"));
        assertFalse(BiomeIds.known("pack:crystal_caves"));
        assertFalse(BiomeIds.known("plains"));
    }
}
