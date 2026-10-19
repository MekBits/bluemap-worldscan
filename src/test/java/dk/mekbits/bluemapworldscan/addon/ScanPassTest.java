package dk.mekbits.bluemapworldscan.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import dk.mekbits.bluemapworldscan.bluemap.LayerBuilder;
import dk.mekbits.bluemapworldscan.bluemap.LayerPublisher;
import dk.mekbits.bluemapworldscan.bluemap.MapLayers;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.ClientAssetIcons;
import dk.mekbits.bluemapworldscan.layer.IdLabels;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.layer.ServedSize;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.scan.CancellationToken;
import dk.mekbits.bluemapworldscan.state.StructureEntry;
import dk.mekbits.bluemapworldscan.testsupport.FakeBlueMap.FakeWorld;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryBlueMapMap;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import dk.mekbits.bluemapworldscan.testsupport.TestStates;
import dk.mekbits.bluemapworldscan.testsupport.TestWorlds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanPassTest {

    private static final String VILLAGE_SET = "worldscan-structure-village";

    @TempDir
    Path temp;

    private final LayerPublisher publisher = new LayerPublisher();

    private CapturedWorld world(String id, Path dimension, InMemoryBlueMapMap... maps) {
        FakeWorld world = new FakeWorld(id, dimension, List.of(maps));
        return new CapturedWorld(id, world,
                List.of(maps).stream().map(map -> new MapLayers(map, map.getAssetStorage(), publisher)).toList());
    }

    private ScanPass pass(CancellationToken cancel, ScanPass.BiomeBuilder biomes) throws IOException {
        return new ScanPass(Files.createDirectories(temp.resolve("addon")), AddonConfig.defaults(), "scanner", "test",
                new ClientAssetIcons(Optional::empty), cancel, biomes);
    }

    private static List<LayerSet> oneBiomeSet() {
        Area area = new Area("0", "Plains", 63f, List.of(new int[] {0, 0}, new int[] {0, 16}, new int[] {16, 0}), List.of(), 0x73336699);
        return List.of(new LayerSet("worldscan-biome-minecraft-plains", "Biome: Plains", true, 0, List.of(), List.of(area)));
    }

    @Test
    void aBiomeBuildThatThrowsStillStoresTheStructureLayersOfEveryMapAndTheNextWorldIsScanned() throws IOException {
        InMemoryBlueMapMap first = new InMemoryBlueMapMap("overworld");
        InMemoryBlueMapMap second = new InMemoryBlueMapMap("overworld-b");
        InMemoryBlueMapMap later = new InMemoryBlueMapMap("nether");
        CapturedWorld broken = world("world", TestWorlds.withChunks(temp.resolve("world"), 40), first, second);
        CapturedWorld next = world("world_nether", TestWorlds.withChunks(temp.resolve("nether"), 40), later);
        ScanPass pass = pass(new CancellationToken(), (state, config, names) -> {
            throw new IllegalStateException("boom");
        });

        try (LogCapture log = new LogCapture()) {
            assertTrue(pass.run(broken));
            assertTrue(pass.run(next), "the scan goes on with the next world");

            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("world") && message.contains("biome")),
                    log.warnings().toString());
        }
        for (InMemoryBlueMapMap map : List.of(first, second, later)) {
            assertTrue(map.getMarkerSets().containsKey(VILLAGE_SET), map.getId() + " has the structure layers");
            assertEquals(1, map.getMarkerSets().size(), map.getId() + " has no biome layers");
        }
    }

    @Test
    void aScanCancelledWhileTheBiomesAreBuiltPublishesNothing() throws IOException {
        InMemoryBlueMapMap first = new InMemoryBlueMapMap("overworld");
        InMemoryBlueMapMap second = new InMemoryBlueMapMap("overworld-b");
        CapturedWorld world = world("world", TestWorlds.withChunks(temp.resolve("world"), 40), first, second);
        CancellationToken cancel = new CancellationToken();
        ScanPass pass = pass(cancel, (state, config, names) -> {
            cancel.cancel();
            return oneBiomeSet();
        });

        boolean goOn = pass.run(world);

        assertFalse(goOn, "the caller stops with the remaining worlds");
        for (InMemoryBlueMapMap map : List.of(first, second)) {
            assertTrue(map.getMarkerSets().isEmpty(), map.getId() + " shows nothing");
            assertTrue(map.storage().names().isEmpty(), map.getId() + " stores nothing");
        }
    }

    @Test
    void theSizeWarningNamesTheSettingsThatShrinkTheLayers() {
        String message = ScanPass.oversizedLayersMessage("overworld", 9_500_000);

        assertTrue(message.contains("overworld"), message);
        assertTrue(message.contains("9.5 MB"), message);
        assertTrue(message.contains("structures.disabledLayers"), message);
        assertTrue(message.contains("biomes.cellSizeChunks"), message);
        assertTrue(message.contains("biomes.disabled"), message);
    }

    @Test
    void theEstimateCountsPointMarkersShapesAndTheirCorners() {
        Poi poi = new Poi("1_70_1", "Village", 1, 70, 1, "d", null, 11, 11, 5000);
        Area area = new Area("0", "Plains", 63f, List.of(new int[] {0, 0}, new int[] {0, 64}, new int[] {64, 64}, new int[] {64, 0}),
                List.of(List.of(new int[] {16, 16}, new int[] {32, 16}, new int[] {32, 32})), 0x73336699);
        LayersFile layers = new LayersFile(LayersFile.FORMAT_VERSION, List.of(
                new LayerSet("a", "A", false, 0, List.of(poi, poi)),
                new LayerSet("b", "B", true, 1, List.of(), List.of(area))));

        assertEquals(2 * ServedSize.POI_BYTES + ServedSize.AREA_BYTES + ServedSize.CORNER_BYTES * 7, ScanPass.estimatedServedBytes(layers));
    }

    @Test
    void theWarningStartsAboveTenMillionBytes() {
        assertFalse(ScanPass.oversized(10_000_000L), "10,000,000 bytes is the limit itself");
        assertTrue(ScanPass.oversized(10_000_001L));
        assertFalse(ScanPass.oversized(9_162_685L), "the measured overworld with the defaults fits");
    }

    private static long served(InMemoryBlueMapMap map, String setIdPrefix) {
        Map<String, MarkerSet> chosen = new TreeMap<>();
        map.getMarkerSets().forEach((id, set) -> {
            if (id.startsWith(setIdPrefix)) {
                chosen.put(id, set);
            }
        });
        return MarkerGson.INSTANCE.toJson(chosen).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theEstimateIsCloseToWhatBlueMapServes() {
        Random random = new Random(7);
        List<StructureEntry> villages = new ArrayList<>();
        for (int chunk = 0; chunk < 1000; chunk++) {
            int x = random.nextInt(60_000) - 30_000;
            int z = random.nextInt(60_000) - 30_000;
            villages.add(TestStates.structure(chunk, "minecraft:village_plains", x, 60, z, x + 20, 70, z + 20));
        }
        List<LayerSet> sets = new ArrayList<>(LayerBuilder.buildStructures(
                TestStates.state(villages.toArray(StructureEntry[]::new)), AddonConfig.defaults(),
                Map.of("village", "maps/overworld/assets/worldscan-village-0123456789ab.png")));
        List<LayerSet> biomes = new ArrayList<>();
        for (String biome : List.of("minecraft:plains", "minecraft:old_growth_pine_taiga")) {
            List<Area> areas = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                int x = (random.nextInt(3_700) - 1_850) * 16;
                int z = (random.nextInt(3_700) - 1_850) * 16;
                areas.add(new Area(Integer.toString(i), IdLabels.fromId(biome), 63f,
                        List.of(new int[] {x, z}, new int[] {x, z + 256}, new int[] {x + 256, z + 256}, new int[] {x + 256, z}),
                        List.of(), 0x73336699));
            }
            biomes.add(new LayerSet("worldscan-biome-" + biome.substring(10), "Biome: " + IdLabels.fromId(biome), true, 0, List.of(), areas));
        }
        LayersFile layers = new LayersFile(LayersFile.FORMAT_VERSION, LayerBuilder.combine(sets, biomes));
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        publisher.publish(map, layers);

        long structuresServed = served(map, "worldscan-structure-");
        long biomesServed = served(map, "worldscan-biome-");
        long structuresEstimated = ScanPass.estimatedServedBytes(new LayersFile(LayersFile.FORMAT_VERSION, sets));
        long biomesEstimated = ScanPass.estimatedServedBytes(new LayersFile(LayersFile.FORMAT_VERSION, biomes));
        assertEquals(structuresServed, structuresEstimated, structuresServed * 0.08, "structures");
        assertEquals(biomesServed, biomesEstimated, biomesServed * 0.03, "biomes");
    }
}
