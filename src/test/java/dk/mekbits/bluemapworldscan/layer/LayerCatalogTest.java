package dk.mekbits.bluemapworldscan.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dk.mekbits.bluemapworldscan.chunk.StructureIds;
import org.junit.jupiter.api.Test;

class LayerCatalogTest {

    private static final List<String> KNOWN_STRUCTURES = List.of(
            "ancient_city", "bastion_remnant", "buried_treasure", "desert_pyramid", "end_city", "fortress",
            "igloo", "jungle_pyramid", "mansion", "mineshaft", "mineshaft_mesa", "monument", "nether_fossil",
            "ocean_ruin_cold", "ocean_ruin_warm", "pillager_outpost", "ruined_portal", "ruined_portal_desert",
            "ruined_portal_jungle", "ruined_portal_mountain", "ruined_portal_nether", "ruined_portal_ocean",
            "ruined_portal_swamp", "shipwreck", "shipwreck_beached", "stronghold", "swamp_hut", "trail_ruins",
            "trial_chambers", "village_desert", "village_plains", "village_savanna", "village_snowy",
            "village_taiga", "abandoned_camp_forest", "abandoned_camp_wooded_badlands");

    @Test
    void villageVariantsShareOneLayer() {
        assertEquals(LayerCatalog.layerFor("minecraft:village_plains"), LayerCatalog.layerFor("minecraft:village_snowy"));
        assertEquals("Village", LayerCatalog.layerFor("minecraft:village_taiga").label());
    }

    @Test
    void mineshaftAndItsBadlandsVariantShareOneLayer() {
        assertEquals("Mineshaft", LayerCatalog.layerFor("minecraft:mineshaft").label());
        assertEquals("Mineshaft", LayerCatalog.layerFor("minecraft:mineshaft_mesa").label());
    }

    @Test
    void groupedKindsFallInTheirLayers() {
        assertEquals("Ruined portal", LayerCatalog.layerFor("minecraft:ruined_portal_nether").label());
        assertEquals("Ruined portal", LayerCatalog.layerFor("minecraft:ruined_portal").label());
        assertEquals("Ocean ruin", LayerCatalog.layerFor("minecraft:ocean_ruin_warm").label());
        assertEquals("Shipwreck", LayerCatalog.layerFor("minecraft:shipwreck_beached").label());
        assertEquals("Abandoned camp", LayerCatalog.layerFor("minecraft:abandoned_camp_wooded_badlands").label());
    }

    @Test
    void everyStructureOfMinecraft263HasACatalogLayer() {
        for (String path : KNOWN_STRUCTURES) {
            String id = "minecraft:" + path;
            assertTrue(StructureIds.known(id), id + " is in the id table");
            assertTrue(LayerCatalog.layers().contains(LayerCatalog.layerFor(id)), id + " has a layer in the catalog");
        }
    }

    @Test
    void anUnknownIdGetsItsOwnLayerLabelledFromTheId() {
        Layer layer = LayerCatalog.layerFor("pack:big_tower");

        assertEquals("Big tower (pack)", layer.label());
        assertEquals("pack_big_tower", layer.id());
        assertNull(layer.icon());
        assertEquals(LayerCatalog.layerFor("pack:big_tower"), layer);
    }

    @Test
    void anUnknownVanillaIdIsLabelledWithoutItsNamespace() {
        assertEquals("Sky island", LayerCatalog.layerFor("minecraft:sky_island").label());
    }

    @Test
    void anUnknownIdWithOddCharactersGetsASafeLayerId() {
        assertEquals("my_pack_towers_big.one", LayerCatalog.layerFor("My Pack:towers/big.one").id());
    }

    @Test
    void theDefaultHiddenLayersAreTheDenseKinds() {
        Set<String> hidden = LayerCatalog.layers().stream()
                .filter(Layer::defaultHidden)
                .map(Layer::id)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of("mineshaft", "shipwreck", "ocean_ruin", "ruined_portal", "buried_treasure", "nether_fossil"),
                hidden);
    }

    @Test
    void theAbandonedCampIsShownByDefault() {
        assertEquals(false, LayerCatalog.layerFor("minecraft:abandoned_camp_forest").defaultHidden());
    }

    @Test
    void layerIdsAreUniqueAndTheSidebarOrderIsAlphabeticalByLabel() {
        List<String> labels = LayerCatalog.layers().stream().map(Layer::label).toList();

        assertEquals(LayerCatalog.layers().size(), LayerCatalog.layers().stream().map(Layer::id).distinct().count());
        assertEquals(labels.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), labels);
    }

    @Test
    void everyKnownLayerHasAClientJarTextureForItsIcon() {
        for (Layer layer : LayerCatalog.layers()) {
            assertTrue(layer.icon().matches("^assets/minecraft/textures/(item|block)/[a-z0-9_]+\\.png$"),
                    layer.id() + " has the icon texture " + layer.icon());
        }
    }

    @Test
    void theEndCityShipIsALayerOfItsOwn() {
        assertTrue(LayerCatalog.layers().contains(LayerCatalog.END_CITY_SHIP));
        assertEquals("End city ship", LayerCatalog.END_CITY_SHIP.label());
    }
}
