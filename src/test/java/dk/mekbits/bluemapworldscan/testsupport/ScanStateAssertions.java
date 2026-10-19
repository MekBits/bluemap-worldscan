package dk.mekbits.bluemapworldscan.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;

/**
 * Compares scan states field by field: record equality compares array components by reference,
 * so {@code assertEquals} on the records proves nothing.
 */
public final class ScanStateAssertions {

    private ScanStateAssertions() {
    }

    public static void assertStateEquals(ScanState expected, ScanState actual) {
        assertEquals(expected.formatVersion(), actual.formatVersion());
        assertEquals(expected.worldId(), actual.worldId());
        assertEquals(new ArrayList<>(expected.regions().keySet()), new ArrayList<>(actual.regions().keySet()));
        expected.regions().forEach((name, entry) -> assertRegionEquals(entry, actual.regions().get(name)));
    }

    public static void assertRegionEquals(RegionEntry expected, RegionEntry actual) {
        assertArrayEquals(expected.timestamps(), actual.timestamps());
        assertEquals(expected.structures().size(), actual.structures().size());
        for (int i = 0; i < expected.structures().size(); i++) {
            StructureEntry want = expected.structures().get(i);
            StructureEntry got = actual.structures().get(i);
            assertEquals(want.chunkIndex(), got.chunkIndex());
            assertEquals(want.structureId(), got.structureId());
            assertArrayEquals(want.box(), got.box());
            assertArrayEquals(want.endShipBox(), got.endShipBox());
        }
        assertEquals(new ArrayList<>(expected.biomes().keySet()), new ArrayList<>(actual.biomes().keySet()));
        expected.biomes().forEach((biome, words) -> assertArrayEquals(words, actual.biomes().get(biome), biome));
    }

    /** A deep copy, to compare a state with itself after a scan that must not change it. */
    public static ScanState copy(ScanState state) {
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        state.regions().forEach((name, entry) -> {
            List<StructureEntry> structures = new ArrayList<>();
            for (StructureEntry structure : entry.structures()) {
                structures.add(new StructureEntry(
                        structure.chunkIndex(),
                        structure.structureId(),
                        structure.box().clone(),
                        structure.endShipBox() == null ? null : structure.endShipBox().clone()));
            }
            TreeMap<String, long[]> biomes = new TreeMap<>();
            entry.biomes().forEach((biome, words) -> biomes.put(biome, words.clone()));
            regions.put(name, new RegionEntry(entry.timestamps().clone(), structures, biomes));
        });
        return new ScanState(state.formatVersion(), state.worldId(), regions);
    }
}
