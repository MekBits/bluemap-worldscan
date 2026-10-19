package dk.mekbits.bluemapworldscan.chunk;

import static dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks.piece;
import static dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks.sections;
import static dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks.start;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

/** {@link ChunkReader} on chunks built for the formats and odd tags no real fixture has. */
class ChunkReaderSyntheticTest {

    private static final int[] CITY_BOX = {0, 60, 0, 40, 100, 40};
    private static final int[] SHIP_BOX = {70, 90, 10, 90, 110, 30};

    private static ChunkInfo read(CompoundBinaryTag root) throws IOException {
        return ChunkReader.read(new ByteArrayInputStream(SyntheticChunks.toBytes(root)));
    }

    /** A chunk of the root layout (DataVersion 2842 and later), with the position tags. */
    private static CompoundBinaryTag.Builder rootLayout(int dataVersion) {
        return CompoundBinaryTag.builder().putInt("DataVersion", dataVersion).putInt("xPos", 3).putInt("zPos", -4);
    }

    /** A chunk of the {@code Level} layout (DataVersion 1466 to 2841) around the given level tags. */
    private static CompoundBinaryTag levelLayout(int dataVersion, CompoundBinaryTag.Builder level) {
        return CompoundBinaryTag.builder().putInt("DataVersion", dataVersion).put("Level", level.build()).build();
    }

    private static CompoundBinaryTag.Builder level() {
        return CompoundBinaryTag.builder().putInt("xPos", 3).putInt("zPos", -4);
    }

    private static CompoundBinaryTag starts(Object... keysAndStarts) {
        CompoundBinaryTag.Builder starts = CompoundBinaryTag.builder();
        for (int i = 0; i < keysAndStarts.length; i += 2) {
            starts.put((String) keysAndStarts[i], (CompoundBinaryTag) keysAndStarts[i + 1]);
        }
        return starts.build();
    }

    private static CompoundBinaryTag cityStart(String id, boolean withShip) {
        return withShip
                ? start(id, piece("base_floor", CITY_BOX), piece("ship", SHIP_BOX))
                : start(id, piece("base_floor", CITY_BOX));
    }

    private static List<String> structureIds(ChunkInfo chunk) {
        return chunk.structures().stream().map(FoundStructure::structureId).toList();
    }

    @Test
    void renamedSnapshotBiomesGetTheirCurrentNames_synthetic() throws IOException {
        CompoundBinaryTag deepWarmOcean = levelLayout(2840, level()
                .putString("Status", "full")
                .put("Sections", sections(List.of("minecraft:deep_warm_ocean", "minecraft:plains"))));
        CompoundBinaryTag mountains = levelLayout(2836, level()
                .putString("Status", "full")
                .put("Sections", sections(List.of("minecraft:mountains"))));

        assertEquals(Set.of("minecraft:warm_ocean", "minecraft:plains"), read(deepWarmOcean).biomes());
        assertEquals(Set.of("minecraft:windswept_hills"), read(mountains).biomes());
    }

    @Test
    void aRetrogenTargetStatusDecidesWhetherBiomesCountAtDataVersion2844_synthetic() throws IOException {
        ListBinaryTag palette = sections(List.of("minecraft:plains"));
        CompoundBinaryTag withRetrogen = rootLayout(2844)
                .putString("Status", "empty")
                .put("below_zero_retrogen", CompoundBinaryTag.builder().putString("target_status", "heightmaps").build())
                .put("sections", palette)
                .build();
        CompoundBinaryTag withoutRetrogen = rootLayout(2844).putString("Status", "empty").put("sections", palette).build();

        assertEquals(Set.of("minecraft:plains"), read(withRetrogen).biomes());
        assertEquals(Set.of(), read(withoutRetrogen).biomes());
    }

    @Test
    void aRetrogenTargetStatusInTheLevelLayoutCounts_synthetic() throws IOException {
        CompoundBinaryTag chunk = levelLayout(2835, level()
                .putString("Status", "empty")
                .put("below_zero_retrogen", CompoundBinaryTag.builder().putString("target_status", "heightmaps").build())
                .put("Sections", sections(List.of("minecraft:plains"))));

        assertEquals(Set.of("minecraft:plains"), read(chunk).biomes());
    }

    @Test
    void theLowercaseStatusTagOfTheRootLayoutIsReadWhenThereIsNoStatus_synthetic() throws IOException {
        ListBinaryTag palette = sections(List.of("minecraft:plains"));

        assertEquals(Set.of("minecraft:plains"),
                read(rootLayout(5100).putString("status", "minecraft:full").put("sections", palette).build()).biomes());
        assertEquals(Set.of("minecraft:plains"),
                read(rootLayout(5100).putString("status", "minecraft:terrain").put("sections", palette).build()).biomes());
        assertEquals(Set.of(), read(rootLayout(5100).put("sections", palette).build()).biomes());
    }

    @Test
    void aStatusThatIsNotAStringIsTreatedAsNoStatus_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(2844)
                .putInt("Status", 7)
                .put("sections", sections(List.of("minecraft:plains")))
                .build();

        assertEquals(Set.of(), read(chunk).biomes());
    }

    @Test
    void aVersion1_13ChunkMapsItsOldStructureKeysAndDependsOnItsStatus_synthetic() throws IOException {
        CompoundBinaryTag oldStarts = starts(
                "Village", start("minecraft:village", piece(null, 0, 60, 0, 10, 70, 10)),
                "EndCity", cityStart("minecraft:endcity", true));
        CompoundBinaryTag.Builder level = level().putString("Status", "fullchunk")
                .put("Structures", CompoundBinaryTag.builder().put("Starts", oldStarts).build())
                .putIntArray("Biomes", new int[] {1, 1, 40});

        ChunkInfo generated = read(levelLayout(1519, level));
        ChunkInfo empty = read(levelLayout(1519, level.putString("Status", "empty")));

        assertEquals(List.of("minecraft:end_city", "minecraft:village_plains"), structureIds(generated));
        assertEquals(Set.of("minecraft:plains", "minecraft:small_end_islands"), generated.biomes());
        assertEquals(Set.of(), empty.biomes());
        assertEquals(2, empty.structures().size(), "the structures do not depend on the status");
    }

    @Test
    void theStatusBiomesCountsOnlyFromDataVersion1911_synthetic() throws IOException {
        CompoundBinaryTag.Builder level = level().putString("Status", "biomes").putIntArray("Biomes", new int[] {1});

        assertEquals(Set.of(), read(levelLayout(1905, level)).biomes());
        assertEquals(Set.of("minecraft:plains"), read(levelLayout(1911, level)).biomes());
    }

    @Test
    void numericBiomesAreNamedAndAnUnknownNumberIsReported_synthetic() throws IOException {
        CompoundBinaryTag chunk = levelLayout(2586, level()
                .putString("Status", "full")
                .putIntArray("Biomes", new int[] {3, 3, 175, 250}));

        ChunkInfo info = read(chunk);

        assertEquals(Set.of("minecraft:windswept_hills", "minecraft:lush_caves"), info.biomes());
        assertEquals(Set.of("250"), info.unknownBiomeIds());
    }

    @Test
    void anEmptyBiomeArrayGivesNoBiomes_synthetic() throws IOException {
        CompoundBinaryTag chunk = levelLayout(1976, level().putString("Status", "full").putIntArray("Biomes", new int[0]));

        assertEquals(Set.of(), read(chunk).biomes());
    }

    @Test
    void sulfurCavesAndDappledForestAreKnownBiomes_synthetic() throws IOException {
        ChunkInfo sulfur = read(rootLayout(4903).putString("Status", "minecraft:full")
                .put("sections", sections(List.of("minecraft:sulfur_caves"))).build());
        ChunkInfo dappled = read(rootLayout(5023).putString("Status", "minecraft:full")
                .put("sections", sections(List.of("minecraft:dappled_forest"))).build());

        assertEquals(Set.of("minecraft:sulfur_caves"), sulfur.biomes());
        assertEquals(Set.of(), sulfur.unknownBiomeIds());
        assertEquals(Set.of("minecraft:dappled_forest"), dappled.biomes());
        assertEquals(Set.of(), dappled.unknownBiomeIds());
    }

    @Test
    void anUnknownBiomeNameIsPassedThroughAndReported_synthetic() throws IOException {
        ChunkInfo info = read(rootLayout(4903).putString("Status", "minecraft:full")
                .put("sections", sections(List.of("pack:crystal_caves"))).build());

        assertEquals(Set.of("pack:crystal_caves"), info.biomes());
        assertEquals(Set.of("pack:crystal_caves"), info.unknownBiomeIds());
    }

    @Test
    void anUnknownStructureIsPassedThroughAndReported_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("pack:big_tower", start("pack:big_tower", piece(null, 0, 0, 0, 5, 5, 5))))
                        .build())
                .build();

        ChunkInfo info = read(chunk);

        assertEquals(List.of("pack:big_tower"), structureIds(info));
        assertEquals(Set.of("pack:big_tower"), info.unknownStructureIds());
    }

    @Test
    void idsThatAreNoResourceLocationsAreSkippedWithoutLosingTheChunk_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903).putString("Status", "minecraft:full")
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts(
                                "pack:Big Tower", start("pack:Big Tower", piece(null, 0, 0, 0, 5, 5, 5)),
                                "pack:tower\u001b[31m", start("pack:tower", piece(null, 0, 0, 0, 5, 5, 5)),
                                "a:b:c", start("a:b:c", piece(null, 0, 0, 0, 5, 5, 5)),
                                "minecraft:igloo", start("minecraft:igloo", piece(null, 100, 60, 100, 107, 70, 107))))
                        .build())
                .put("sections", sections(List.of("minecraft:plains", "Pack:Bad Biome", "pack:cave\n", "pack:crystal_caves/deep.v-2")))
                .build();

        ChunkInfo info = read(chunk);

        assertEquals(List.of("minecraft:igloo"), structureIds(info));
        assertEquals(Set.of(), info.unknownStructureIds());
        assertEquals(Set.of("minecraft:plains", "pack:crystal_caves/deep.v-2"), info.biomes());
        assertEquals(Set.of("pack:crystal_caves/deep.v-2"), info.unknownBiomeIds());
    }

    @Test
    void anIdLongerThanTheLimitIsSkippedLikeAnInvalidOne_synthetic() throws IOException {
        String atLimit = "pack:" + "a".repeat(ResourceNames.MAX_LENGTH - 5);
        String overLimit = atLimit + "a";
        CompoundBinaryTag chunk = rootLayout(4903).putString("Status", "minecraft:full")
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts(
                                atLimit, start(atLimit, piece(null, 0, 0, 0, 5, 5, 5)),
                                overLimit, start(overLimit, piece(null, 0, 0, 0, 5, 5, 5))))
                        .build())
                .put("sections", sections(List.of(atLimit, overLimit)))
                .build();

        ChunkInfo info = read(chunk);

        assertEquals(List.of(atLimit), structureIds(info));
        assertEquals(Set.of(atLimit), info.biomes());
    }

    @Test
    void aStartKeyOfAnOldChunkIsCheckedAfterItIsLowerCased_synthetic() throws IOException {
        CompoundBinaryTag.Builder level = level().putString("Status", "fullchunk")
                .put("Structures", CompoundBinaryTag.builder()
                        .put("Starts", starts(
                                "Village", start("minecraft:village", piece(null, 0, 60, 0, 10, 70, 10)),
                                "Odd Name", start("minecraft:village", piece(null, 0, 60, 0, 10, 70, 10))))
                        .build());

        assertEquals(List.of("minecraft:village_plains"), structureIds(read(levelLayout(1519, level))));
    }

    @Test
    void aChunkWithoutADataVersionIsPre1_13_synthetic() throws IOException {
        ChunkInfo info = read(CompoundBinaryTag.builder().put("Level", level().build()).build());

        assertEquals(Kind.PRE_1_13, info.kind());
        assertEquals(-1, info.dataVersion());
    }

    @Test
    void aVersion1_12ChunkWithByteBiomesIsPre1_13AndNothingIsRead_synthetic() throws IOException {
        CompoundBinaryTag chunk = levelLayout(1343, level()
                .putByte("TerrainPopulated", (byte) 1)
                .putByteArray("Biomes", new byte[256])
                .put("Structures", CompoundBinaryTag.builder()
                        .put("Starts", starts("Village", start("minecraft:village", piece(null, 0, 0, 0, 5, 5, 5))))
                        .build()));

        ChunkInfo info = read(chunk);

        assertEquals(Kind.PRE_1_13, info.kind());
        assertEquals(1343, info.dataVersion());
        assertEquals(Integer.MIN_VALUE, info.chunkX());
        assertEquals(List.of(), info.structures());
        assertEquals(Set.of(), info.biomes());
    }

    @Test
    void anEndCityWithoutAShipPieceHasNoShipBox_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("minecraft:end_city", cityStart("minecraft:end_city", false)))
                        .build())
                .build();

        FoundStructure city = read(chunk).structures().getFirst();

        assertArrayEquals(CITY_BOX, city.box());
        assertNull(city.endShipBox());
    }

    @Test
    void theShipPieceIsFoundByItsTemplateWithoutNamespace_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("minecraft:end_city",
                                start("minecraft:end_city", piece("base_floor", CITY_BOX), piece("minecraft:ship", SHIP_BOX))))
                        .build())
                .build();

        FoundStructure city = read(chunk).structures().getFirst();

        assertArrayEquals(SHIP_BOX, city.endShipBox());
        assertArrayEquals(new int[] {0, 60, 0, 90, 110, 40}, city.box());
    }

    @Test
    void aShipPieceOfAnotherStructureDoesNotGiveAShipBox_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("minecraft:shipwreck", start("minecraft:shipwreck", piece("ship", SHIP_BOX))))
                        .build())
                .build();

        assertNull(read(chunk).structures().getFirst().endShipBox());
    }

    @Test
    void aRootLayoutChunkOfDataVersion2842IsReadLikeOne2844_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(2842)
                .putString("Status", "full")
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("monument", start("minecraft:monument", piece(null, 0, 30, 0, 57, 60, 57))))
                        .build())
                .put("sections", sections(List.of("minecraft:deep_ocean")))
                .build();

        ChunkInfo info = read(chunk);

        assertEquals(List.of("minecraft:monument"), structureIds(info));
        assertEquals(Set.of("minecraft:deep_ocean"), info.biomes());
        assertEquals(3, info.chunkX());
        assertEquals(-4, info.chunkZ());
    }

    @Test
    void aByteArrayWhereBiomesShouldBeAnIntArrayGivesNoBiomesButKeepsTheStructures_synthetic() throws IOException {
        CompoundBinaryTag chunk = levelLayout(1519, level()
                .putString("Status", "fullchunk")
                .putByteArray("Biomes", new byte[256])
                .put("Structures", CompoundBinaryTag.builder()
                        .put("Starts", starts("Village", start("minecraft:village", piece(null, 0, 60, 0, 10, 70, 10))))
                        .build()));

        ChunkInfo info = read(chunk);

        assertEquals(Kind.MODERN, info.kind());
        assertEquals(Set.of(), info.biomes());
        assertEquals(List.of("minecraft:village_plains"), structureIds(info));
    }

    @Test
    void aPieceWhoseBoundingBoxIsALongArrayIsSkipped_synthetic() throws IOException {
        CompoundBinaryTag longBox = CompoundBinaryTag.builder()
                .putString("id", "minecraft:omb")
                .putLongArray("BB", new long[] {0, 0, 0, 100, 100, 100})
                .build();
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts("minecraft:monument",
                                start("minecraft:monument", longBox, piece(null, 0, 30, 0, 57, 60, 57))))
                        .build())
                .build();

        assertArrayEquals(new int[] {0, 30, 0, 57, 60, 57}, read(chunk).structures().getFirst().box());
    }

    @Test
    void startsThatTheGameWouldNotLoadAreSkipped_synthetic() throws IOException {
        CompoundBinaryTag withoutId = CompoundBinaryTag.builder()
                .put("Children", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(piece(null, 0, 0, 0, 5, 5, 5)).build())
                .build();
        CompoundBinaryTag chunk = rootLayout(2865)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts(
                                "igloo", start("INVALID"),
                                "monument", start("minecraft:monument"),
                                "mansion", withoutId,
                                "village", start("minecraft:village", piece(null, 0, 60, 0, 10, 70, 10))))
                        .build())
                .build();

        assertEquals(List.of("minecraft:village_plains"), structureIds(read(chunk)));
    }

    @Test
    void aChunkWithoutPositionTagsHasNoPosition_synthetic() throws IOException {
        ChunkInfo info = read(CompoundBinaryTag.builder().putInt("DataVersion", 4903).putString("Status", "minecraft:full").build());

        assertEquals(Kind.MODERN, info.kind());
        assertEquals(Integer.MIN_VALUE, info.chunkX());
        assertEquals(Integer.MIN_VALUE, info.chunkZ());
    }

    @Test
    void structuresComeInSortedKeyOrderOnEveryRead_synthetic() throws IOException {
        CompoundBinaryTag chunk = rootLayout(4903)
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", starts(
                                "minecraft:monument", start("minecraft:monument", piece(null, 0, 30, 0, 57, 60, 57)),
                                "minecraft:igloo", start("minecraft:igloo", piece(null, 100, 60, 100, 107, 70, 107)),
                                "minecraft:buried_treasure", start("minecraft:buried_treasure", piece(null, 200, 40, 200, 205, 45, 205))))
                        .build())
                .build();

        List<List<String>> reads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reads.add(structureIds(read(chunk)));
        }

        List<String> sorted = List.of("minecraft:buried_treasure", "minecraft:igloo", "minecraft:monument");
        assertEquals(List.of(sorted, sorted, sorted, sorted, sorted, sorted, sorted, sorted, sorted, sorted), reads);
    }
}
