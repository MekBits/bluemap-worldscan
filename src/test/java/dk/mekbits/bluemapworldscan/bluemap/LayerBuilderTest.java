package dk.mekbits.bluemapworldscan.bluemap;

import static dk.mekbits.bluemapworldscan.testsupport.TestStates.endCity;
import static dk.mekbits.bluemapworldscan.testsupport.TestStates.state;
import static dk.mekbits.bluemapworldscan.testsupport.TestStates.structure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.biome.BiomeColors;
import dk.mekbits.bluemapworldscan.biome.BiomeNames;
import dk.mekbits.bluemapworldscan.biome.BiomeShapes;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.IconAssets;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryAssetStorage;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import dk.mekbits.bluemapworldscan.testsupport.TestStates;
import org.junit.jupiter.api.Test;

class LayerBuilderTest {

    private static final AddonConfig DEFAULTS = AddonConfig.defaults();

    private static AddonConfig withDisabledLayers(String... layers) {
        return new AddonConfig(true, 500, 5, new AddonConfig.Structures(List.of(layers)), DEFAULTS.biomes());
    }

    private static final BiomeNames NAMES = BiomeNames.fromIds();

    private static AddonConfig withBiomes(boolean enabled, int cellSizeChunks, String... disabled) {
        return new AddonConfig(true, 500, 5, DEFAULTS.structures(),
                new AddonConfig.Biomes(enabled, cellSizeChunks, List.of(disabled)));
    }

    private static LayerSet biomeSet(List<LayerSet> sets, String id) {
        return sets.stream().filter(candidate -> candidate.id().equals(LayerBuilder.BIOME_SET_PREFIX + id)).findFirst().orElse(null);
    }

    private static int[][] corners(Area area) {
        return area.outline().toArray(int[][]::new);
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

        byte[] first = LayerStore.toJson(new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildStructures(state, DEFAULTS, iconUrls)));
        byte[] second = LayerStore.toJson(new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildStructures(state, DEFAULTS, iconUrls)));

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
                new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildStructures(state, DEFAULTS, overworldUrls))));
        String other = new String(LayerStore.toJson(
                new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildStructures(state, DEFAULTS, otherUrls))));

        assertTrue(overworld.contains("maps/overworld/assets/bluemap-worldscan-icon-village-"), overworld);
        assertFalse(overworld.contains("overworld-flat"));
        assertTrue(other.contains("maps/overworld-flat/assets/bluemap-worldscan-icon-village-"), other);
        assertEquals(overworld.replace("maps/overworld/", "maps/overworld-flat/"), other,
                "the layers of two maps differ only in their icon URLs");
    }

    @Test
    void aBiomeBecomesAHiddenSetWithAnAreaOfItsCells() {
        ScanState state = TestStates.biomes(Map.of("minecraft:plains", new int[] {0, 1, 32, 33}));

        List<LayerSet> sets = LayerBuilder.buildBiomes(state, withBiomes(true, 4), NAMES);

        assertEquals(1, sets.size());
        LayerSet plains = biomeSet(sets, "minecraft-plains");
        assertEquals("Biome: Plains", plains.label());
        assertTrue(plains.defaultHidden());
        assertEquals(List.of(), plains.pois());
        Area area = plains.areas().getFirst();
        assertEquals(1, plains.areas().size());
        assertEquals("Plains", area.label());
        assertEquals(63f, area.y());
        assertEquals(BiomeColors.fillArgb("minecraft:plains"), area.fillArgb());
        assertEquals(List.of(), area.holes());
        // Chunks 0, 1, 32 and 33 are in the cell of 4x4 chunks at the origin: a square of 64 blocks.
        assertEquals(4, area.outline().size(), "four corners, the closing repeat of the first one is left out");
        assertEquals(List.of(List.of(0, 0), List.of(0, 64), List.of(64, 64), List.of(64, 0)),
                area.outline().stream().map(corner -> List.of(corner[0], corner[1])).toList());
    }

    @Test
    void theCellSizeOfTheConfigDecidesTheResolution() {
        ScanState state = TestStates.biomes(Map.of("minecraft:plains", new int[] {0}));

        Area coarse = LayerBuilder.buildBiomes(state, withBiomes(true, 8), NAMES).getFirst().areas().getFirst();
        Area fine = LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES).getFirst().areas().getFirst();

        assertEquals(128, Arrays.stream(corners(coarse)).mapToInt(corner -> corner[0]).max().orElseThrow());
        assertEquals(16, Arrays.stream(corners(fine)).mapToInt(corner -> corner[0]).max().orElseThrow());
    }

    @Test
    void aRingOfCellsBecomesAnAreaWithAHole() {
        // The eight chunks around (1,1), cells of one chunk.
        ScanState state = TestStates.biomes(Map.of("minecraft:lush_caves", new int[] {0, 1, 2, 32, 34, 64, 65, 66}));

        Area area = LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES).getFirst().areas().getFirst();

        assertEquals(1, area.holes().size());
        assertEquals(4, area.holes().getFirst().size());
        assertEquals(4, area.outline().size());
    }

    @Test
    void twoSeparateAreasOfOneBiomeAreTwoShapesInAFixedOrder() {
        // Chunk 40 (x 8, z 1) is far from chunk 0, cells of 4x4 chunks.
        ScanState state = TestStates.biomes(Map.of("minecraft:plains", new int[] {40, 0}));

        List<Area> areas = LayerBuilder.buildBiomes(state, withBiomes(true, 4), NAMES).getFirst().areas();

        assertEquals(List.of("0", "1"), areas.stream().map(Area::id).toList());
        assertEquals(0, corners(areas.get(0))[0][0] + corners(areas.get(0))[0][1]);
        assertTrue(corners(areas.get(1))[0][0] >= 128);
    }

    @Test
    void aDisabledBiomeHasNoSetWhetherItIsNamedWithOrWithoutTheNamespace() {
        ScanState state = TestStates.biomes(Map.of(
                "minecraft:plains", new int[] {0},
                "minecraft:desert", new int[] {40},
                "pack:crystal_caves", new int[] {80},
                "minecraft:swamp", new int[] {120}));

        List<LayerSet> sets = LayerBuilder.buildBiomes(state, withBiomes(true, 4, "plains", "minecraft:desert", "pack:crystal_caves"), NAMES);

        assertEquals(List.of("worldscan-biome-minecraft-swamp"), sets.stream().map(LayerSet::id).toList());
    }

    @Test
    void whenBiomesAreSwitchedOffThereAreNoBiomeSets() {
        ScanState state = TestStates.biomes(Map.of("minecraft:plains", new int[] {0}));

        assertEquals(List.of(), LayerBuilder.buildBiomes(state, withBiomes(false, 4), NAMES));
    }

    @Test
    void biomeSetsAreInOrderOfTheirNamesAndFollowTheStructureSetsInTheSidebar() {
        ScanState biomeState = TestStates.biomes(Map.of(
                "minecraft:swamp", new int[] {0},
                "minecraft:desert", new int[] {40},
                "pack:big_tower", new int[] {80}));
        List<LayerSet> structures = LayerBuilder.buildStructures(
                state(structure(0, "minecraft:igloo", 0, 60, 0, 9, 70, 9), structure(1, "minecraft:village_plains", 16, 60, 0, 25, 70, 9)),
                DEFAULTS, Map.of());

        List<LayerSet> biomes = LayerBuilder.buildBiomes(biomeState, DEFAULTS, NAMES);
        List<LayerSet> all = LayerBuilder.combine(structures, biomes);

        assertEquals(List.of("Biome: Big tower (pack)", "Biome: Desert", "Biome: Swamp"), biomes.stream().map(LayerSet::label).toList());
        assertEquals(List.of(0, 1, 2), biomes.stream().map(LayerSet::sorting).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), all.stream().map(LayerSet::sorting).toList());
        assertEquals(List.of("Igloo", "Village", "Biome: Big tower (pack)", "Biome: Desert", "Biome: Swamp"),
                all.stream().map(LayerSet::label).toList());
    }

    @Test
    void biomeSetIdsHoldOnlyCharactersASetIdShouldAndNeverClash() {
        ScanState state = TestStates.biomes(Map.of(
                "pack:a/b", new int[] {0},
                "pack:a_b", new int[] {40},
                "Pack:Odd One", new int[] {80}));

        List<String> ids = LayerBuilder.buildBiomes(state, DEFAULTS, NAMES).stream().map(LayerSet::id).toList();

        assertEquals(3, ids.stream().distinct().count(), ids.toString());
        assertTrue(ids.stream().allMatch(id -> id.matches("worldscan-biome-[a-z0-9_.-]+")), ids.toString());
    }

    @Test
    void theSameStateAndConfigGiveByteIdenticalBiomeJson() {
        ScanState state = TestStates.biomes(Map.of(
                "minecraft:plains", new int[] {0, 1, 2, 3, 32, 35, 64, 67, 96, 97, 98, 99, 200, 400},
                "minecraft:forest", new int[] {5, 6, 37, 38, 700}));

        byte[] first = LayerStore.toJson(new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES)));
        byte[] second = LayerStore.toJson(new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES)));

        assertArrayEquals(first, second);
    }

    @Test
    void anEmptyStateGivesNoBiomeSets() {
        assertEquals(List.of(), LayerBuilder.buildBiomes(TestStates.state(), DEFAULTS, NAMES));
    }

    @Test
    void anEmptyStateGivesNoLayers() {
        assertEquals(List.of(), LayerBuilder.buildStructures(TestStates.state(), DEFAULTS, Map.of()));
    }

    @Test
    void aBiomeIdWithoutANamespaceGetsASetAsIfItWereMinecrafts() {
        ScanState state = TestStates.biomes(Map.of("plains", new int[] {0}));

        List<LayerSet> sets = LayerBuilder.buildBiomes(state, DEFAULTS, NAMES);

        assertEquals(1, sets.size());
        assertEquals(LayerBuilder.BIOME_SET_PREFIX + "minecraft-plains", sets.getFirst().id());
    }

    @Test
    void aBiomeIdWithoutANamespaceNeverClashesWithTheSameNameInMinecraft() {
        ScanState state = TestStates.biomes(Map.of("plains", new int[] {0}, "minecraft:plains", new int[] {5}));

        List<LayerSet> sets = LayerBuilder.buildBiomes(state, DEFAULTS, NAMES);

        assertEquals(2, sets.size());
        assertEquals(2, sets.stream().map(LayerSet::id).distinct().count());
    }

    @Test
    void anAreaFarOutIsClampedToTheIntRangeInsteadOfWrappingToTheOtherSide() {
        ScanState near = TestStates.biomes(Map.of("minecraft:plains", new int[] {0}));
        TreeMap<String, dk.mekbits.bluemapworldscan.state.RegionEntry> regions = new TreeMap<>();
        regions.put("r.70000000.70000000.mca", near.regions().get("r.0.0.mca"));
        ScanState far = new ScanState(ScanState.FORMAT_VERSION, "world-1", regions);

        List<LayerSet> sets = LayerBuilder.buildBiomes(far, DEFAULTS, NAMES);

        for (int[] corner : sets.getFirst().areas().getFirst().outline()) {
            assertEquals(Integer.MAX_VALUE, corner[0]);
            assertEquals(Integer.MAX_VALUE, corner[1]);
        }
    }

    private static ScanState biomesNamed(int count) {
        Map<String, int[]> biomes = new TreeMap<>();
        for (int n = 0; n < count; n++) {
            biomes.put(String.format("pack:b%03d", n), new int[] {0});
        }
        return TestStates.biomes(biomes);
    }

    @Test
    void aWorldWithMoreThanFiveHundredAndTwelveBiomesGetsTheFirstOnesByIdAndAWarning() {
        try (LogCapture log = new LogCapture()) {
            List<LayerSet> sets = LayerBuilder.buildBiomes(biomesNamed(600), DEFAULTS, NAMES);

            assertEquals(512, sets.size());
            assertNull(biomeSet(sets, "pack-b512"));
            assertEquals("worldscan-biome-pack-b511", sets.stream().map(LayerSet::id).max(String::compareTo).orElseThrow());
            assertNotNull(biomeSet(sets, "pack-b000"));
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("600") && message.contains("512") && message.contains("88")),
                    log.warnings().toString());
        }
    }

    @Test
    void aWorldWithFiveHundredAndTwelveBiomesGetsAllOfThemWithoutAWarning() {
        try (LogCapture log = new LogCapture()) {
            assertEquals(512, LayerBuilder.buildBiomes(biomesNamed(512), DEFAULTS, NAMES).size());

            assertTrue(log.warnings().isEmpty(), log.warnings().toString());
        }
    }

    @Test
    void disabledBiomesDoNotCountTowardsTheLimit() {
        try (LogCapture log = new LogCapture()) {
            List<LayerSet> sets = LayerBuilder.buildBiomes(biomesNamed(513), withBiomes(true, 16, "pack:b512"), NAMES);

            assertEquals(512, sets.size());
            assertNotNull(biomeSet(sets, "pack-b511"));
            assertTrue(log.warnings().isEmpty(), log.warnings().toString());
        }
    }

    @Test
    void aWorldWhoseCellsCouldTakeHundredMillionBytesStillGetsItsBiomeLayers() {
        ScanState state = TestStates.biomeChunks("minecraft:plains", BiomeShapes.MAX_CELLS);

        List<LayerSet> sets = LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES);

        assertEquals(1, sets.size());
    }

    @Test
    void aWorldWithOneCellMoreThanTheLimitGetsNoBiomeLayersAndAWarningNamingTheCellSize() {
        ScanState state = TestStates.biomeChunks("minecraft:plains", BiomeShapes.MAX_CELLS + 1);

        try (LogCapture log = new LogCapture()) {
            List<LayerSet> sets = LayerBuilder.buildBiomes(state, withBiomes(true, 1), NAMES);

            assertTrue(sets.isEmpty());
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("biomes.cellSizeChunks") && message.contains("world-1")),
                    log.warnings().toString());
        }
    }

    @Test
    void aLargerCellBringsTheSameWorldUnderTheLimit() {
        ScanState state = TestStates.biomeChunks("minecraft:plains", BiomeShapes.MAX_CELLS + 1);

        assertEquals(1, LayerBuilder.buildBiomes(state, withBiomes(true, 16), NAMES).size());
    }
}
