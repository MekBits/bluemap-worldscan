package dk.mekbits.bluemapworldscan.bluemap;

import static dk.mekbits.bluemapworldscan.testsupport.TestStates.endCity;
import static dk.mekbits.bluemapworldscan.testsupport.TestStates.state;
import static dk.mekbits.bluemapworldscan.testsupport.TestStates.structure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.IconAssets;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryAssetStorage;
import dk.mekbits.bluemapworldscan.testsupport.TestStates;
import org.junit.jupiter.api.Test;

class LayerBuilderTest {

    private static final AddonConfig DEFAULTS = AddonConfig.defaults();

    private static AddonConfig withDisabledLayers(String... layers) {
        return new AddonConfig(true, 500, 5, new AddonConfig.Structures(List.of(layers)), DEFAULTS.biomes());
    }

    private static LayerSet set(List<LayerSet> sets, String layer) {
        return sets.stream()
                .filter(candidate -> candidate.id().equals(LayerBuilder.STRUCTURE_SET_PREFIX + layer))
                .findFirst()
                .orElse(null);
    }

    @Test
    void theMarkerPointIsTheMiddleOfTheBoxOnTopOfIt() {
        assertArrayEquals(new int[] {4, 21, 4}, LayerBuilder.markerPoint(new int[] {0, 10, 0, 9, 20, 9}));
        assertArrayEquals(new int[] {-5, 1, -5}, LayerBuilder.markerPoint(new int[] {-9, 0, -9, 0, 0, 0}));
    }

    @Test
    void aStructureBecomesAMarkerInItsLayerWithAPopup() {
        ScanState state = state(structure(0, "minecraft:village_taiga", 0, 60, 0, 9, 70, 9));

        List<LayerSet> sets = LayerBuilder.buildStructures(state, DEFAULTS, Map.of("village", "maps/m/assets/village.png"));

        assertEquals(1, sets.size());
        LayerSet village = set(sets, "village");
        assertEquals("Village", village.label());
        assertFalse(village.defaultHidden());
        Poi poi = village.pois().getFirst();
        assertEquals(new Poi(
                "4_71_4", "Village", 4, 71, 4, MarkerData.popupHtml("Village", 4, 71, 4),
                "maps/m/assets/village.png", 11, 11, 5000), poi);
        assertTrue(poi.detail().contains("/tp @s 4 71 4"));
    }

    @Test
    void aLayerWithoutAnIconUrlGetsMarkersWithoutAnIcon() {
        ScanState state = state(structure(0, "minecraft:igloo", 0, 60, 0, 9, 70, 9));

        assertNull(LayerBuilder.buildStructures(state, DEFAULTS, Map.of()).getFirst().pois().getFirst().icon());
    }

    @Test
    void anEndCityWithAShipGetsASecondMarkerInTheShipLayerAtTheShip() {
        ScanState state = state(endCity(0, new int[] {0, 50, 0, 40, 90, 40}, new int[] {100, 60, 100, 119, 70, 109}));

        List<LayerSet> sets = LayerBuilder.buildStructures(state, DEFAULTS, Map.of());

        assertEquals(List.of("worldscan-structure-end_city", "worldscan-structure-end_city_ship"),
                sets.stream().map(LayerSet::id).toList());
        assertEquals("20_91_20", set(sets, "end_city").pois().getFirst().id());
        Poi ship = set(sets, "end_city_ship").pois().getFirst();
        assertEquals("End city ship", ship.label());
        assertEquals(109, ship.x());
        assertEquals(71, ship.y());
        assertEquals(104, ship.z());
    }

    @Test
    void mineshaftsAreNotBuiltWithTheDefaultConfig() {
        ScanState state = state(
                structure(0, "minecraft:mineshaft", 0, 20, 0, 9, 30, 9),
                structure(1, "minecraft:igloo", 16, 60, 0, 25, 70, 9));

        List<LayerSet> sets = LayerBuilder.buildStructures(state, DEFAULTS, Map.of());

        assertNull(set(sets, "mineshaft"));
        assertEquals(1, sets.size());
    }

    @Test
    void buriedTreasureIsNotBuiltWithTheDefaultConfigEither() {
        ScanState state = state(
                structure(0, "minecraft:buried_treasure", 0, 60, 0, 0, 60, 0),
                structure(1, "minecraft:igloo", 16, 60, 0, 25, 70, 9));

        assertNull(set(LayerBuilder.buildStructures(state, DEFAULTS, Map.of()), "buried_treasure"));
        assertNull(set(LayerBuilder.buildStructures(state, withDisabledLayers(), Map.of()), "mineshaft"));
        assertTrue(set(LayerBuilder.buildStructures(state, withDisabledLayers(), Map.of()), "buried_treasure").defaultHidden());
    }

    @Test
    void mineshaftsAreBuiltHiddenWhenTheirLayerIsNotDisabled() {
        ScanState state = state(
                structure(0, "minecraft:mineshaft", 0, 20, 0, 9, 30, 9),
                structure(1, "minecraft:mineshaft_mesa", 16, 20, 0, 25, 30, 9));

        LayerSet mineshaft = set(LayerBuilder.buildStructures(state, withDisabledLayers(), Map.of()), "mineshaft");

        assertTrue(mineshaft.defaultHidden());
        assertEquals(2, mineshaft.pois().size());
        assertEquals(1000, mineshaft.pois().getFirst().maxDistance());
    }

    @Test
    void aDisabledLayerIsLeftOutWhateverElseIsThere() {
        ScanState state = state(
                structure(0, "minecraft:monument", 0, 40, 0, 57, 60, 57),
                structure(1, "minecraft:buried_treasure", 16, 60, 0, 16, 60, 0));

        List<LayerSet> sets = LayerBuilder.buildStructures(state, withDisabledLayers("buried_treasure"), Map.of());

        assertEquals(List.of("worldscan-structure-monument"), sets.stream().map(LayerSet::id).toList());
    }

    @Test
    void anUnknownIdGetsALayerAfterTheKnownOnes() {
        ScanState state = state(
                structure(0, "pack:big_tower", 0, 60, 0, 9, 80, 9),
                structure(1, "minecraft:village_plains", 16, 60, 0, 25, 70, 9));

        List<LayerSet> sets = LayerBuilder.buildStructures(state, DEFAULTS, Map.of());

        assertEquals(List.of("worldscan-structure-village", "worldscan-structure-pack_big_tower"),
                sets.stream().map(LayerSet::id).toList());
        assertEquals("Big tower (pack)", sets.get(1).label());
        assertEquals(List.of(0, 1), sets.stream().map(LayerSet::sorting).toList());
    }

    @Test
    void markersComeInRegionNameThenChunkOrder() {
        ScanState state = state(Map.of(
                "r.0.0.mca", List.of(
                        structure(2, "minecraft:igloo", 32, 60, 0, 41, 70, 9),
                        structure(7, "minecraft:igloo", 112, 60, 0, 121, 70, 9)),
                "r.-1.0.mca", List.of(structure(900, "minecraft:igloo", -500, 60, 0, -491, 70, 9))));

        List<Poi> pois = set(LayerBuilder.buildStructures(state, DEFAULTS, Map.of()), "igloo").pois();

        assertEquals(List.of(-496, 36, 116), pois.stream().map(Poi::x).toList());
    }

    @Test
    void twoStructuresAtOnePositionAreOneMarker() {
        ScanState state = state(
                structure(0, "minecraft:igloo", 0, 60, 0, 9, 70, 9),
                structure(1, "minecraft:igloo", 0, 60, 0, 9, 70, 9));

        assertEquals(1, set(LayerBuilder.buildStructures(state, DEFAULTS, Map.of()), "igloo").pois().size());
    }

    @Test
    void theSameStateAndConfigGiveByteIdenticalJson() {
        ScanState state = state(
                structure(0, "minecraft:village_plains", 0, 60, 0, 9, 70, 9),
                structure(1, "minecraft:stronghold", 16, 20, 0, 25, 30, 9),
                endCity(2, new int[] {32, 50, 0, 72, 90, 40}, new int[] {100, 60, 100, 119, 70, 109}));
        Map<String, String> iconUrls = new TreeMap<>(Map.of("village", "maps/m/assets/v.png"));

        byte[] first = LayerStore.toJson(new LayersFile(1, LayerBuilder.buildStructures(state, DEFAULTS, iconUrls)));
        byte[] second = LayerStore.toJson(new LayersFile(1, LayerBuilder.buildStructures(state, DEFAULTS, iconUrls)));

        assertArrayEquals(first, second);
    }

    @Test
    void eachMapsLayersCarryOnlyThatMapsIconUrls() throws IOException {
        ScanState state = state(structure(0, "minecraft:village_plains", 0, 60, 0, 9, 70, 9));
        byte[] png = {1, 2, 3};
        Map<String, byte[]> pngByLayer = Map.of("village", png);

        Map<String, String> overworldUrls = IconAssets.writeAll(new InMemoryAssetStorage("overworld"), pngByLayer);
        Map<String, String> otherUrls = IconAssets.writeAll(new InMemoryAssetStorage("overworld-flat"), pngByLayer);
        String overworld = new String(LayerStore.toJson(
                new LayersFile(1, LayerBuilder.buildStructures(state, DEFAULTS, overworldUrls))));
        String other = new String(LayerStore.toJson(
                new LayersFile(1, LayerBuilder.buildStructures(state, DEFAULTS, otherUrls))));

        assertTrue(overworld.contains("maps/overworld/assets/bluemap-worldscan-icon-village-"), overworld);
        assertFalse(overworld.contains("overworld-flat"));
        assertTrue(other.contains("maps/overworld-flat/assets/bluemap-worldscan-icon-village-"), other);
        assertEquals(overworld.replace("maps/overworld/", "maps/overworld-flat/"), other,
                "the layers of two maps differ only in their icon URLs");
    }

    @Test
    void anEmptyStateGivesNoLayers() {
        assertEquals(List.of(), LayerBuilder.buildStructures(TestStates.state(), DEFAULTS, Map.of()));
    }
}
