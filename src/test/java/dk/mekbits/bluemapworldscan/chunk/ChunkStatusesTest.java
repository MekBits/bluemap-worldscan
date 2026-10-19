package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChunkStatusesTest {

    @ParameterizedTest
    @CsvSource({
        "empty, false",
        "structure_starts, false",
        "structure_references, false",
        "minecraft:empty, false",
        "minecraft:structure_starts, false",
        "minecraft:structure_references, false",
        "biomes, true",
        "minecraft:biomes, true",
        "noise, true",
        "minecraft:carvers, true",
        "minecraft:initialize_light, true",
        "full, true",
        "minecraft:full, true",
        "minecraft:terrain, true",
        "pack:later_step, true"
    })
    void fromDataVersion1911AStatusBeforeTheBiomeStepHasNoBiomes(String status, boolean expected) {
        assertEquals(expected, ChunkStatuses.biomesGenerated(status, null, 4903));
    }

    @ParameterizedTest
    @CsvSource({
        "empty, false",
        "structure_starts, false",
        "structure_references, false",
        "biomes, false",
        "noise, true",
        "fullchunk, true",
        "full, true"
    })
    void below1911TheStatusBiomesHasNoBiomesEither(String status, boolean expected) {
        assertEquals(expected, ChunkStatuses.biomesGenerated(status, null, 1905));
    }

    @ParameterizedTest
    @CsvSource({"1910, false", "1911, true"})
    void theStatusBiomesCountsFromDataVersion1911(int dataVersion, boolean expected) {
        assertEquals(expected, ChunkStatuses.biomesGenerated("biomes", null, dataVersion));
    }

    @ParameterizedTest
    @CsvSource({
        "empty, heightmaps, true",
        "empty, minecraft:features, true",
        "full, structure_starts, false",
        "empty, empty, false"
    })
    void theRetrogenTargetStatusReplacesTheStatus(String status, String target, boolean expected) {
        assertEquals(expected, ChunkStatuses.biomesGenerated(status, target, 2844));
    }

    @Test
    void aChunkWithoutAnyStatusHasNoBiomes() {
        assertFalse(ChunkStatuses.biomesGenerated(null, null, 4903));
    }

    @Test
    void aRetrogenTargetAloneIsEnough() {
        assertTrue(ChunkStatuses.biomesGenerated(null, "heightmaps", 2844));
    }
}
