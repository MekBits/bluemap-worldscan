package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StructureIdsTest {

    @ParameterizedTest
    @CsvSource({
        "Village, minecraft:village_plains",
        "village, minecraft:village_plains",
        "Mineshaft, minecraft:mineshaft",
        "Jungle_Pyramid, minecraft:jungle_pyramid",
        "Desert_Pyramid, minecraft:desert_pyramid",
        "Igloo, minecraft:igloo",
        "Swamp_Hut, minecraft:swamp_hut",
        "Stronghold, minecraft:stronghold",
        "Mansion, minecraft:mansion",
        "Monument, minecraft:monument",
        "Fortress, minecraft:fortress",
        "EndCity, minecraft:end_city",
        "endcity, minecraft:end_city",
        "Shipwreck, minecraft:shipwreck",
        "Ocean_Ruin, minecraft:ocean_ruin_cold",
        "Pillager_Outpost, minecraft:pillager_outpost",
        "Buried_Treasure, minecraft:buried_treasure",
        "Ruined_Portal, minecraft:ruined_portal",
        "Nether_Fossil, minecraft:nether_fossil",
        "Bastion_Remnant, minecraft:bastion_remnant"
    })
    void anOldKeyMapsToTheIdTheDataFixerFallsBackTo(String key, String expected) {
        assertEquals(expected, StructureIds.current(key, 2865));
    }

    @Test
    void theLastDataVersionWithOldKeysIs2969() {
        assertEquals("minecraft:village_plains", StructureIds.current("village", 2969));
        assertEquals("minecraft:village", StructureIds.current("village", 2970));
    }

    @Test
    void fromDataVersion2970TheKeyIsTheId() {
        assertEquals("minecraft:ocean_ruin_warm", StructureIds.current("minecraft:ocean_ruin_warm", 4903));
        assertEquals("pack:big_tower", StructureIds.current("pack:big_tower", 4903));
    }

    @Test
    void aBareKeyFromDataVersion2970GetsTheMinecraftNamespace() {
        assertEquals("minecraft:trial_chambers", StructureIds.current("trial_chambers", 4903));
    }

    @Test
    void anOldKeyNotInTheTableIsLowerCasedAndNamespaced() {
        assertEquals("minecraft:big_tower", StructureIds.current("Big_Tower", 2865));
    }

    @Test
    void everyStructureOfMinecraft26_3IsKnown() {
        List<String> structures = List.of(
                "ancient_city", "bastion_remnant", "buried_treasure", "desert_pyramid", "end_city", "fortress",
                "igloo", "jungle_pyramid", "mansion", "mineshaft", "mineshaft_mesa", "monument", "nether_fossil",
                "ocean_ruin_cold", "ocean_ruin_warm", "pillager_outpost", "ruined_portal", "ruined_portal_desert",
                "ruined_portal_jungle", "ruined_portal_mountain", "ruined_portal_nether", "ruined_portal_ocean",
                "ruined_portal_swamp", "shipwreck", "shipwreck_beached", "stronghold", "swamp_hut", "trail_ruins",
                "trial_chambers", "village_desert", "village_plains", "village_savanna", "village_snowy",
                "village_taiga");

        for (String structure : structures) {
            assertTrue(StructureIds.known("minecraft:" + structure), structure);
        }
    }

    @Test
    void theEighteenAbandonedCampsOfMinecraft26_3AreKnown() {
        List<String> biomes = List.of(
                "bamboo_jungle", "birch_forest", "cherry_grove", "dappled_forest", "flower_forest", "forest",
                "meadow", "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                "pale_garden", "savanna", "snowy_taiga", "sparse_jungle", "swamp", "taiga", "windswept_forest",
                "wooded_badlands");

        for (String biome : biomes) {
            assertTrue(StructureIds.known("minecraft:abandoned_camp_" + biome), biome);
        }
    }

    @Test
    void idsOutsideTheTablesAreUnknown() {
        assertFalse(StructureIds.known("minecraft:village"));
        assertFalse(StructureIds.known("minecraft:abandoned_camp"));
        assertFalse(StructureIds.known("minecraft:abandoned_camp_ocean"));
        assertFalse(StructureIds.known("pack:big_tower"));
        assertFalse(StructureIds.known("village_plains"));
    }
}
