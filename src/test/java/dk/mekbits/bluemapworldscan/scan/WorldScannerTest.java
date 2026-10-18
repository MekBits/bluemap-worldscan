package dk.mekbits.bluemapworldscan.scan;

import static dk.mekbits.bluemapworldscan.testsupport.ScanStateAssertions.assertRegionEquals;
import static dk.mekbits.bluemapworldscan.testsupport.ScanStateAssertions.assertStateEquals;
import static dk.mekbits.bluemapworldscan.testsupport.ScanStateAssertions.copy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;
import dk.mekbits.bluemapworldscan.testsupport.ChunkFixtures;
import dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks;
import dk.mekbits.bluemapworldscan.testsupport.TestRegions;
import dk.mekbits.bluemapworldscan.testsupport.TestRegions.TestChunk;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldScannerTest {

    private static final int TS = 1_700_000_000;
    private static final ScanLimits FAST = new ScanLimits(1_000_000);
    private static final String MONUMENT = "overworld-1.18.2plus-struct-monument-dv4903-c.-440.-27";
    private static final String END_SHIP = "the_end-1.13-1.14-ship-dv1976-c.322.-158";

    @TempDir
    Path world;

    // ---- helpers

    private static ScanState emptyState() {
        return new ScanState(ScanState.FORMAT_VERSION, "world-1", new TreeMap<>());
    }

    private static int index(int chunkX, int chunkZ) {
        return Math.floorMod(chunkX, 32) + 32 * Math.floorMod(chunkZ, 32);
    }

    private static String regionName(int chunkX, int chunkZ) {
        return "r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca";
    }

    private Path regionFile(String name) throws IOException {
        Path folder = world.resolve("region");
        Files.createDirectories(folder);
        return folder.resolve(name);
    }

    private static int[] box(int chunkX, int chunkZ) {
        return new int[] {chunkX * 16, 60, chunkZ * 16, chunkX * 16 + 15, 70, chunkZ * 16 + 15};
    }

    /**
     * A chunk of the given DataVersion at the given position: the Level layout up to 2841, the root
     * layout from 2842. Only the root layout carries the given biome and structures.
     */
    private static byte[] chunk(int dataVersion, int chunkX, int chunkZ, String biome, String... structureIds) {
        boolean level = dataVersion <= 2841;
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder().putInt("DataVersion", dataVersion);
        CompoundBinaryTag.Builder content = level ? CompoundBinaryTag.builder() : root;
        content.putInt("xPos", chunkX)
                .putInt("zPos", chunkZ)
                .putString("Status", dataVersion < 1911 ? "fullchunk" : "full");
        if (level) {
            root.put("Level", content.build());
        } else {
            CompoundBinaryTag.Builder starts = CompoundBinaryTag.builder();
            for (String id : structureIds) {
                starts.put(id, SyntheticChunks.start(id, SyntheticChunks.piece(null, box(chunkX, chunkZ))));
            }
            content.put("structures", CompoundBinaryTag.builder().put("starts", starts.build()).build());
            content.put("sections", SyntheticChunks.sections(List.of(biome)));
        }
        return SyntheticChunks.toBytes(root.build());
    }

    private static byte[] chunk(int chunkX, int chunkZ, String biome, String... structureIds) {
        return chunk(4903, chunkX, chunkZ, biome, structureIds);
    }

    /** A 1.12.2 chunk: a byte-array {@code Level.Biomes}, which the reader never looks at. */
    private static byte[] preThirteenChunk(int chunkX, int chunkZ) {
        CompoundBinaryTag level = CompoundBinaryTag.builder()
                .putInt("xPos", chunkX)
                .putInt("zPos", chunkZ)
                .putByteArray("Biomes", new byte[256])
                .putByte("TerrainPopulated", (byte) 1)
                .build();
        return SyntheticChunks.toBytes(CompoundBinaryTag.builder().putInt("DataVersion", 1343).put("Level", level).build());
    }

    private static TestChunk zlib(byte[] nbt, int timestamp) {
        return new TestChunk(nbt, timestamp, 2, false);
    }

    private static ScanOutcome scan(Path dimension, ScanState previous) {
        return WorldScanner.scan(dimension, previous, FAST, new CancellationToken(), state -> {
        });
    }

    private static RegionEntry region(ScanOutcome outcome, String regionName) {
        RegionEntry entry = outcome.state().regions().get(regionName);
        assertNotNull(entry, regionName + " is in the state");
        return entry;
    }

    private static long[] bits(int... chunks) {
        long[] words = new long[16];
        for (int chunk : chunks) {
            words[chunk >> 6] |= 1L << (chunk & 63);
        }
        return words;
    }

    private static List<String> structureIds(RegionEntry entry, int chunkIndex) {
        List<String> ids = new ArrayList<>();
        for (StructureEntry structure : entry.structures()) {
            if (structure.chunkIndex() == chunkIndex) {
                ids.add(structure.structureId());
            }
        }
        return ids;
    }

    private static List<String> keys(Map<String, ?> map) {
        return new ArrayList<>(map.keySet());
    }

    /** Chunks (0,0), (1,0) and (1,1) of region 0.0; chunk (1,0) holds the given structure. */
    private Map<Integer, TestChunk> threeChunks(int timestamp, String structureOfChunk1) {
        return Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), timestamp),
                index(1, 0), zlib(chunk(1, 0, "minecraft:desert", structureOfChunk1), timestamp),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), timestamp));
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    // ---- first scan and delta

    @Test
    void firstScanReadsAllChunksAndStoresTheirStructuresBiomesAndTimestamps() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));

        ScanOutcome outcome = scan(world, emptyState());

        assertFalse(outcome.skipped());
        assertFalse(outcome.cancelled());
        assertEquals(3, outcome.report().read());
        assertEquals(0, outcome.report().unchanged());
        assertEquals(0, outcome.report().failed());
        RegionEntry region = region(outcome, "r.0.0.mca");
        assertEquals(TS, region.timestamps()[0]);
        assertEquals(TS, region.timestamps()[1]);
        assertEquals(TS, region.timestamps()[33]);
        assertEquals(-1, region.timestamps()[2]);
        assertEquals(List.of("minecraft:igloo"), structureIds(region, 0));
        assertEquals(List.of("minecraft:desert_pyramid"), structureIds(region, 1));
        assertEquals(List.of(), structureIds(region, 33));
        assertArrayEquals(box(1, 0), region.structures().get(1).box());
        assertNull(region.structures().get(1).endShipBox());
        assertEquals(List.of("minecraft:desert", "minecraft:plains"), keys(region.biomes()));
        assertArrayEquals(bits(0, 33), region.biomes().get("minecraft:plains"));
        assertArrayEquals(bits(1), region.biomes().get("minecraft:desert"));
    }

    @Test
    void aPresentChunkWithHeaderTimestampZeroIsReadOnTheFirstScanAndNotAgain() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), 0)));

        ScanOutcome first = scan(world, emptyState());
        ScanOutcome second = scan(world, first.state());

        assertEquals(1, first.report().read());
        assertEquals(0, region(first, "r.0.0.mca").timestamps()[0]);
        assertEquals(List.of("minecraft:igloo"), structureIds(region(first, "r.0.0.mca"), 0));
        assertEquals(0, second.report().read());
        assertEquals(1, second.report().unchanged());
    }

    @Test
    void aSecondScanOfTheSameFilesReadsNothing() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());

        ScanOutcome second = scan(world, first.state());

        assertEquals(0, second.report().read());
        assertEquals(3, second.report().unchanged());
        assertStateEquals(first.state(), second.state());
    }

    @Test
    void aChangedTimestampRereadsThatChunkOnly() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(1, 0), zlib(chunk(1, 0, "minecraft:desert", "minecraft:swamp_hut"), TS + 1),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), TS)));

        ScanOutcome second = scan(world, first.state());

        assertEquals(1, second.report().read());
        assertEquals(2, second.report().unchanged());
        RegionEntry region = region(second, "r.0.0.mca");
        assertEquals(TS + 1, region.timestamps()[1]);
        assertEquals(List.of("minecraft:igloo"), structureIds(region, 0));
        assertEquals(List.of("minecraft:swamp_hut"), structureIds(region, 1));
        assertArrayEquals(bits(0, 33), region.biomes().get("minecraft:plains"));
    }

    @Test
    void aClearedHeaderEntryClearsTheChunksStructuresAndBiomeBits() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), TS)));

        ScanOutcome second = scan(world, first.state());

        RegionEntry region = region(second, "r.0.0.mca");
        assertEquals(-1, region.timestamps()[1]);
        assertEquals(List.of(), structureIds(region, 1));
        assertEquals(List.of("minecraft:plains"), keys(region.biomes()), "only chunk 1 held desert");
        assertArrayEquals(bits(0, 33), region.biomes().get("minecraft:plains"));
        assertEquals(0, second.report().read());
    }

    @Test
    void aDeletedRegionDropsOutOfTheState() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(chunk(0, 0, "minecraft:plains"), TS)));
        TestRegions.write(regionFile("r.1.0.mca"), Map.of(0, zlib(chunk(32, 0, "minecraft:plains"), TS)));
        ScanOutcome first = scan(world, emptyState());
        Files.delete(regionFile("r.1.0.mca"));

        ScanOutcome second = scan(world, first.state());

        assertEquals(List.of("r.0.0.mca"), keys(second.state().regions()));
    }

    @Test
    void aFileThatIsNotARegionFileNameIsIgnored() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(chunk(0, 0, "minecraft:plains"), TS)));
        Files.copy(regionFile("r.0.0.mca"), regionFile("r.0.1.mca.bak"));

        ScanOutcome outcome = scan(world, emptyState());

        assertEquals(List.of("r.0.0.mca"), keys(outcome.state().regions()));
        assertEquals(1, outcome.report().read());
    }

    @Test
    void aRegionFileUnder8KiBGivesNoChunksAndNoFailure() throws IOException {
        Files.write(regionFile("r.0.0.mca"), new byte[100]);

        ScanOutcome outcome = scan(world, emptyState());

        assertEquals(0, outcome.report().failed());
        assertEquals(0, outcome.report().read());
        RegionEntry region = region(outcome, "r.0.0.mca");
        assertTrue(region.structures().isEmpty());
        assertTrue(region.biomes().isEmpty());
        assertEquals(-1, region.timestamps()[0]);
    }

    @Test
    void realChunksAreScannedAtTheirOwnSlotAndTheEndShipIsKept() throws IOException {
        int[] monument = place(MONUMENT);
        int[] ship = place(END_SHIP);

        ScanOutcome outcome = scan(world, emptyState());

        assertEquals(2, outcome.report().read());
        assertEquals(0, outcome.report().wrongPosition());
        assertEquals(0, outcome.report().failed());
        RegionEntry monumentRegion = region(outcome, regionName(monument[0], monument[1]));
        assertEquals(List.of("minecraft:monument"), structureIds(monumentRegion, index(monument[0], monument[1])));
        RegionEntry shipRegion = region(outcome, regionName(ship[0], ship[1]));
        StructureEntry endCity = shipRegion.structures().get(0);
        assertEquals("minecraft:end_city", endCity.structureId());
        assertNotNull(endCity.endShipBox());
        assertTrue(shipRegion.biomes().containsKey("minecraft:end_highlands"));
    }

    /** Writes a reduced real chunk into a region file at the slot its own position names; returns {x, z}. */
    private int[] place(String fixture) throws IOException {
        CompoundBinaryTag tree = ChunkFixtures.tree(fixture);
        CompoundBinaryTag content = tree.getCompound("Level", tree);
        int chunkX = content.getInt("xPos");
        int chunkZ = content.getInt("zPos");
        TestRegions.write(
                regionFile(regionName(chunkX, chunkZ)),
                Map.of(index(chunkX, chunkZ), zlib(ChunkFixtures.bytes(fixture), TS)));
        return new int[] {chunkX, chunkZ};
    }

    // ---- failures and chunks the game may be writing

    @Test
    void aTruncatedChunkCountsAsFailedAndKeepsItsOldEntriesAndTimestamp() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());
        byte[] whole = chunk(1, 0, "minecraft:desert", "minecraft:swamp_hut");
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(1, 0), zlib(Arrays.copyOf(whole, whole.length / 2), TS + 1),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), TS)));

        ScanOutcome second = scan(world, first.state());

        assertEquals(1, second.report().failed());
        assertEquals(0, second.report().read());
        RegionEntry region = region(second, "r.0.0.mca");
        assertEquals(TS, region.timestamps()[1]);
        assertEquals(List.of("minecraft:desert_pyramid"), structureIds(region, 1));
        assertArrayEquals(bits(1), region.biomes().get("minecraft:desert"));
    }

    @Test
    void aTruncatedChunkOnTheFirstScanStaysUnreadSoItIsReadAgain() throws IOException {
        byte[] whole = chunk(0, 0, "minecraft:plains", "minecraft:igloo");
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(Arrays.copyOf(whole, whole.length / 2), TS)));
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(whole, TS)));

        ScanOutcome second = scan(world, first.state());

        assertEquals(1, first.report().failed());
        assertEquals(-1, region(first, "r.0.0.mca").timestamps()[0]);
        assertEquals(1, second.report().read());
        assertEquals(List.of("minecraft:igloo"), structureIds(region(second, "r.0.0.mca"), 0));
    }

    @Test
    void aChunkInTheWrongSlotCountsAsWrongPositionAndKeepsItsOldEntries() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(1, 0), zlib(chunk(5, 5, "minecraft:swamp", "minecraft:swamp_hut"), TS + 1),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), TS)));

        ScanOutcome second = scan(world, first.state());

        assertEquals(1, second.report().wrongPosition());
        assertEquals(0, second.report().read());
        RegionEntry region = region(second, "r.0.0.mca");
        assertEquals(TS, region.timestamps()[1]);
        assertEquals(List.of("minecraft:desert_pyramid"), structureIds(region, 1));
    }

    @Test
    void aChunkWithoutPositionTagsCountsAsWrongPosition() throws IOException {
        byte[] noPosition = SyntheticChunks.toBytes(CompoundBinaryTag.builder()
                .putInt("DataVersion", 4903)
                .putString("Status", "minecraft:full")
                .build());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(noPosition, TS)));

        ScanOutcome outcome = scan(world, emptyState());

        assertEquals(1, outcome.report().wrongPosition());
        assertEquals(-1, region(outcome, "r.0.0.mca").timestamps()[0]);
    }

    @Test
    void aChunkWhoseHeaderTimestampChangesWhileTheRegionIsReadIsNotTakenAndIsReadNextScan() throws IOException {
        Map<Integer, TestChunk> before = Map.of(
                index(2, 0), zlib(chunk(2, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(5, 0), zlib(chunk(5, 0, "minecraft:plains", "minecraft:igloo"), TS),
                index(7, 0), zlib(chunk(7, 0, "minecraft:plains", "minecraft:igloo"), TS));
        Map<Integer, TestChunk> rewritten = Map.of(
                index(2, 0), zlib(chunk(2, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1),
                index(5, 0), zlib(chunk(5, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1),
                index(7, 0), zlib(chunk(7, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1));
        Map<Integer, TestChunk> duringRead = new HashMap<>(rewritten);
        duringRead.put(index(5, 0), zlib(chunk(5, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 2));
        TestRegions.write(regionFile("r.0.0.mca"), before);
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), rewritten);
        AtomicBoolean done = new AtomicBoolean();

        ScanOutcome second = WorldScanner.scan(world, first.state(), FAST, new CancellationToken(), state -> {
        }, (file, chunkIndex) -> {
            if (chunkIndex == 5 && !done.getAndSet(true)) {
                try {
                    TestRegions.write(file, duringRead);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
        ScanOutcome third = scan(world, second.state());

        assertEquals(1, second.report().changedDuringRead());
        assertEquals(2, second.report().read());
        RegionEntry region = region(second, "r.0.0.mca");
        assertEquals(TS + 1, region.timestamps()[2]);
        assertEquals(TS, region.timestamps()[5], "the chunk that changed during the read keeps its old timestamp");
        assertEquals(TS + 1, region.timestamps()[7]);
        assertEquals(List.of("minecraft:swamp_hut"), structureIds(region, 2));
        assertEquals(List.of("minecraft:igloo"), structureIds(region, 5));
        assertEquals(List.of("minecraft:swamp_hut"), structureIds(region, 7));
        assertEquals(1, third.report().read());
        assertEquals(2, third.report().unchanged());
        assertEquals(TS + 2, region(third, "r.0.0.mca").timestamps()[5]);
        assertEquals(List.of("minecraft:swamp_hut"), structureIds(region(third, "r.0.0.mca"), 5));
    }

    @Test
    void anExternalChunkWhoseMccFileIsReplacedAfterItWasReadIsNotTaken() throws IOException {
        Path region = regionFile("r.0.0.mca");
        Path mcc = regionFile("c.0.0.mcc");
        Map<Integer, TestChunk> before = Map.of(
                0, new TestChunk(chunk(0, 0, "minecraft:plains", "minecraft:igloo"), TS, 2, true),
                1, zlib(chunk(1, 0, "minecraft:plains", "minecraft:igloo"), TS));
        Map<Integer, TestChunk> rewritten = Map.of(
                0, new TestChunk(chunk(0, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1, 2, true),
                1, zlib(chunk(1, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1));
        Map<Integer, TestChunk> replacedMcc = Map.of(
                0, new TestChunk(chunk(0, 0, "minecraft:plains", "minecraft:desert_pyramid"), TS + 1, 2, true),
                1, rewritten.get(1));
        TestRegions.write(region, before);
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(region, rewritten);
        AtomicBoolean done = new AtomicBoolean();

        ScanOutcome second = WorldScanner.scan(world, first.state(), FAST, new CancellationToken(), state -> {
        }, (file, chunkIndex) -> {
            if (chunkIndex == 0 && !done.getAndSet(true)) {
                try {
                    FileTime opened = Files.getLastModifiedTime(mcc);
                    TestRegions.write(file, replacedMcc);
                    Files.setLastModifiedTime(mcc, FileTime.from(opened.toInstant().plusSeconds(10)));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
        ScanOutcome third = scan(world, second.state());

        assertEquals(1, second.report().changedDuringRead());
        assertEquals(1, second.report().read());
        RegionEntry kept = region(second, "r.0.0.mca");
        assertEquals(TS, kept.timestamps()[0]);
        assertEquals(List.of("minecraft:igloo"), structureIds(kept, 0));
        assertEquals(TS + 1, kept.timestamps()[1]);
        assertEquals(1, third.report().read());
        assertEquals(List.of("minecraft:desert_pyramid"), structureIds(region(third, "r.0.0.mca"), 0));
    }

    @Test
    void aPre113ChunkIsCountedAndItsTimestampStoredSoItIsNotReadAgain() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(preThirteenChunk(0, 0), TS)));

        ScanOutcome first = scan(world, emptyState());
        ScanOutcome second = scan(world, first.state());

        assertEquals(1, first.report().pre113());
        assertEquals(0, first.report().read());
        assertEquals(0, first.report().failed());
        RegionEntry region = region(first, "r.0.0.mca");
        assertEquals(TS, region.timestamps()[0]);
        assertTrue(region.structures().isEmpty());
        assertTrue(region.biomes().isEmpty());
        assertEquals(0, second.report().pre113());
        assertEquals(1, second.report().unchanged());
    }

    @Test
    void aChunkWithAnUnsupportedCompressionIsCountedAsSuch() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, new TestChunk(chunk(0, 0, "minecraft:plains"), TS, 127, false)));

        ScanOutcome outcome = scan(world, emptyState());

        assertEquals(1, outcome.report().unsupportedCompression());
        assertEquals(0, outcome.report().failed());
        assertEquals(-1, region(outcome, "r.0.0.mca").timestamps()[0]);
    }

    // ---- previous state, skipped worlds, untouched files

    @Test
    void aDimensionFolderWithoutRegionFolderIsSkippedAndKeepsThePreviousState() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanState previous = scan(world, emptyState()).state();
        Path elsewhere = world.resolve("not-mounted");
        Files.createDirectories(elsewhere);
        List<ScanState> checkpoints = new ArrayList<>();

        ScanOutcome outcome = WorldScanner.scan(elsewhere, previous, FAST, new CancellationToken(), checkpoints::add);

        assertTrue(outcome.skipped());
        assertSame(previous, outcome.state());
        assertEquals(0, outcome.report().read());
        assertTrue(checkpoints.isEmpty());
    }

    @Test
    void aDimensionFolderThatDoesNotExistIsSkipped() {
        ScanState previous = emptyState();

        ScanOutcome outcome = scan(world.resolve("does-not-exist"), previous);

        assertTrue(outcome.skipped());
        assertSame(previous, outcome.state());
    }

    @Test
    void aScanNeverChangesThePreviousState() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanState previous = scan(world, emptyState()).state();
        ScanState before = copy(previous);
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(1, 0), zlib(chunk(1, 0, "minecraft:swamp", "minecraft:swamp_hut"), TS + 5),
                index(5, 5), zlib(chunk(5, 5, "minecraft:swamp", "minecraft:igloo"), TS + 5)));
        TestRegions.write(regionFile("r.1.0.mca"), Map.of(0, zlib(chunk(32, 0, "minecraft:plains"), TS)));

        ScanOutcome outcome = scan(world, previous);

        assertStateEquals(before, previous);
        assertEquals(List.of("r.0.0.mca", "r.1.0.mca"), keys(outcome.state().regions()));
        assertEquals(List.of("minecraft:swamp_hut"), structureIds(region(outcome, "r.0.0.mca"), 1));
    }

    @Test
    void aScanLeavesTheWorldFolderUntouched() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        TestRegions.write(
                regionFile("r.1.0.mca"), Map.of(0, new TestChunk(chunk(32, 0, "minecraft:plains"), TS, 2, true)));
        Map<Path, byte[]> bytesBefore = new HashMap<>();
        Map<Path, FileTime> timesBefore = new HashMap<>();
        for (Path file : files(world)) {
            bytesBefore.put(file, Files.readAllBytes(file));
            timesBefore.put(file, Files.getLastModifiedTime(file));
        }

        scan(world, emptyState());

        assertEquals(bytesBefore.keySet(), new HashSet<>(files(world)));
        for (Path file : bytesBefore.keySet()) {
            assertArrayEquals(bytesBefore.get(file), Files.readAllBytes(file), file.toString());
            assertEquals(timesBefore.get(file), Files.getLastModifiedTime(file), file.toString());
        }
    }

    // ---- cancel, checkpoints, rate

    @Test
    void cancelStopsBetweenChunksAndLeavesAConsistentStateThatAnotherScanCompletes() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        TestRegions.write(regionFile("r.0.1.mca"), Map.of(
                index(0, 32), zlib(chunk(0, 32, "minecraft:plains", "minecraft:igloo"), TS),
                index(1, 32), zlib(chunk(1, 32, "minecraft:plains"), TS)));
        TestRegions.write(regionFile("r.1.0.mca"), Map.of(0, zlib(chunk(32, 0, "minecraft:desert"), TS)));
        CancellationToken cancel = new CancellationToken();

        ScanOutcome cut = WorldScanner.scan(world, emptyState(), FAST, cancel, state -> {
        }, (file, chunkIndex) -> {
            if (file.getFileName().toString().equals("r.0.1.mca") && chunkIndex == 0) {
                cancel.cancel();
            }
        });
        ScanOutcome rest = scan(world, cut.state());
        ScanOutcome all = scan(world, emptyState());

        assertTrue(cut.cancelled());
        assertEquals(List.of("r.0.0.mca"), keys(cut.state().regions()), "the cut-off region has no entry yet");
        assertEquals(3, cut.report().read(), "a region cut off leaves no trace in the report");
        assertFalse(rest.cancelled());
        assertEquals(3, rest.report().read(), "only the two regions after the first are read");
        assertEquals(3, rest.report().unchanged());
        assertStateEquals(all.state(), rest.state());
    }

    @Test
    void aRegionCutOffByCancelKeepsItsPreviousEntries() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        ScanOutcome first = scan(world, emptyState());
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(chunk(0, 0, "minecraft:plains", "minecraft:swamp_hut"), TS + 1),
                index(1, 0), zlib(chunk(1, 0, "minecraft:desert", "minecraft:swamp_hut"), TS + 1),
                index(1, 1), zlib(chunk(1, 1, "minecraft:plains"), TS + 1)));
        CancellationToken cancel = new CancellationToken();

        ScanOutcome cut = WorldScanner.scan(world, first.state(), FAST, cancel, state -> {
        }, (file, chunkIndex) -> cancel.cancel());

        assertTrue(cut.cancelled());
        assertSame(first.state().regions().get("r.0.0.mca"), cut.state().regions().get("r.0.0.mca"));
        assertRegionEquals(first.state().regions().get("r.0.0.mca"), region(cut, "r.0.0.mca"));
    }

    @Test
    void aTokenCancelledBeforeTheScanReadsNothing() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), threeChunks(TS, "minecraft:desert_pyramid"));
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();

        ScanOutcome outcome = WorldScanner.scan(world, emptyState(), FAST, cancel, state -> {
        });

        assertTrue(outcome.cancelled());
        assertFalse(outcome.skipped());
        assertTrue(outcome.state().regions().isEmpty());
        assertEquals(0, outcome.report().read());
    }

    @Test
    void checkpointIsCalledAfterEvery100RegionsWithTheStateSoFar() throws IOException {
        for (int x = 0; x < 250; x++) {
            TestRegions.write(regionFile("r." + x + ".0.mca"), Map.of());
        }
        List<ScanState> checkpoints = new ArrayList<>();

        ScanOutcome outcome = WorldScanner.scan(world, emptyState(), FAST, new CancellationToken(), checkpoints::add);

        assertEquals(2, checkpoints.size());
        assertEquals(100, checkpoints.get(0).regions().size());
        assertEquals(200, checkpoints.get(1).regions().size());
        assertEquals(250, outcome.state().regions().size());
    }

    @Test
    void maxChunksPerSecondLimitsHowFastChunksAreRead() throws IOException {
        Map<Integer, TestChunk> chunks = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            chunks.put(i, zlib(chunk(i % 32, i / 32, "minecraft:plains"), TS));
        }
        TestRegions.write(regionFile("r.0.0.mca"), chunks);

        long start = System.nanoTime();
        ScanOutcome outcome = WorldScanner.scan(world, emptyState(), new ScanLimits(100), new CancellationToken(), state -> {
        });
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(300, outcome.report().read());
        assertTrue(elapsedMillis >= 2000, "300 chunks at 100 per second took " + elapsedMillis + " ms");
    }

    @Test
    void chunksSkippedAsUnchangedDoNotCountTowardsTheRate() throws IOException {
        Map<Integer, TestChunk> chunks = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            chunks.put(i, zlib(chunk(i % 32, i / 32, "minecraft:plains"), TS));
        }
        TestRegions.write(regionFile("r.0.0.mca"), chunks);
        ScanOutcome first = scan(world, emptyState());

        long start = System.nanoTime();
        ScanOutcome second = WorldScanner.scan(world, first.state(), new ScanLimits(1), new CancellationToken(), state -> {
        });
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(300, second.report().unchanged());
        assertTrue(elapsedMillis < 2000, "300 unchanged chunks at 1 per second took " + elapsedMillis + " ms");
    }

    @Test
    void aCapBelowOneChunkPerSecondIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(0));
    }

    // ---- report

    @Test
    void theDataVersionBucketsAddUpToTheChunksReadAndCountedAsPre113() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(
                index(0, 0), zlib(preThirteenChunk(0, 0), TS),
                index(1, 0), zlib(chunk(1519, 1, 0, "minecraft:plains"), TS),
                index(2, 0), zlib(chunk(2300, 2, 0, "minecraft:plains"), TS),
                index(3, 0), zlib(chunk(2840, 3, 0, "minecraft:plains"), TS),
                index(4, 0), zlib(chunk(2860, 4, 0, "minecraft:plains"), TS),
                index(5, 0), zlib(chunk(4903, 5, 0, "minecraft:plains"), TS)));

        ScanReport report = scan(world, emptyState()).report();

        assertEquals(5, report.read());
        assertEquals(1, report.pre113());
        assertEquals(List.of("< 1466", "1466-2199", "2200-2831", "2832-2843", "2844-2969", ">= 2970"),
                new ArrayList<>(report.dataVersionBuckets().keySet()));
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L), new ArrayList<>(report.dataVersionBuckets().values()));
        assertEquals(
                report.read() + report.pre113(),
                report.dataVersionBuckets().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(List.of(1343, 1519, 2300, 2840, 2860, 4903), new ArrayList<>(report.dataVersions().keySet()));
        assertTrue(report.summary().contains("read 5"), report.summary());
    }

    @Test
    void unknownStructureIdsAreReported() throws IOException {
        TestRegions.write(regionFile("r.0.0.mca"), Map.of(0, zlib(chunk(0, 0, "minecraft:plains", "pack:big_tower"), TS)));

        ScanReport report = scan(world, emptyState()).report();

        assertEquals(List.of("pack:big_tower"), new ArrayList<>(report.unknownStructureIds()));
        assertTrue(report.unknownBiomeIds().isEmpty());
        assertTrue(report.summary().contains("pack:big_tower"), report.summary());
    }
}
