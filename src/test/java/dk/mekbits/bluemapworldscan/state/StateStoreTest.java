package dk.mekbits.bluemapworldscan.state;

import static dk.mekbits.bluemapworldscan.testsupport.ScanStateAssertions.assertStateEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.util.Loaded;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    private static final String WORLD = "world-1";

    @TempDir
    Path dir;

    private static int[] timestamps(int firstValue) {
        int[] timestamps = new int[1024];
        Arrays.fill(timestamps, -1);
        timestamps[3] = firstValue;
        timestamps[900] = firstValue + 1;
        return timestamps;
    }

    private static long[] bits(int... chunks) {
        long[] words = new long[16];
        for (int chunk : chunks) {
            words[chunk >> 6] |= 1L << (chunk & 63);
        }
        return words;
    }

    private static ScanState sampleState() {
        TreeMap<String, long[]> biomes = new TreeMap<>();
        biomes.put("minecraft:plains", bits(3, 900));
        biomes.put("minecraft:end_highlands", bits(63, 64));
        RegionEntry first = new RegionEntry(
                timestamps(1_700_000_000),
                List.of(
                        new StructureEntry(3, "minecraft:end_city", new int[] {1, 2, 3, 4, 5, 6}, new int[] {7, 8, 9, 10, 11, 12}),
                        new StructureEntry(900, "minecraft:igloo", new int[] {-1, -2, -3, 4, 5, 6}, null)),
                biomes);
        RegionEntry second = new RegionEntry(timestamps(5), List.of(), new TreeMap<>());
        TreeMap<String, RegionEntry> regions = new TreeMap<>();
        regions.put("r.0.0.mca", first);
        regions.put("r.-1.2.mca", second);
        return new ScanState(ScanState.FORMAT_VERSION, WORLD, regions);
    }

    private static CompoundBinaryTag structureTag(BinaryTag box) {
        return CompoundBinaryTag.builder().putInt("chunk", 3).putString("id", "minecraft:igloo").put("box", box).build();
    }

    private static CompoundBinaryTag regionTag(BinaryTag timestamps, BinaryTag box) {
        ListBinaryTag structures = ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(structureTag(box)).build();
        return CompoundBinaryTag.builder()
                .put("timestamps", timestamps)
                .put("structures", structures)
                .put("biomes", CompoundBinaryTag.builder().putLongArray("minecraft:plains", bits(1)).build())
                .build();
    }

    private static CompoundBinaryTag rootTag(int formatVersion, String worldId, CompoundBinaryTag region) {
        return CompoundBinaryTag.builder()
                .putInt("formatVersion", formatVersion)
                .putString("worldId", worldId)
                .put("regions", CompoundBinaryTag.builder().put("r.0.0.mca", region).build())
                .build();
    }

    private void writeTag(Path file, CompoundBinaryTag root) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            BinaryTagIO.writer().write(root, out, BinaryTagIO.Compression.GZIP);
        }
    }

    private static ScanState loaded(Loaded<ScanState> result) {
        return assertInstanceOf(Loaded.Ok.class, result) instanceof Loaded.Ok<?> ok ? (ScanState) ok.value() : null;
    }

    private static BinaryTag validBox() {
        return IntArrayBinaryTag.intArrayBinaryTag(1, 2, 3, 4, 5, 6);
    }

    private static BinaryTag validTimestamps() {
        return IntArrayBinaryTag.intArrayBinaryTag(timestamps(7));
    }

    @Test
    void saveThenLoadRoundTripsEveryField() throws IOException {
        Path file = dir.resolve("state").resolve("abc.nbt.gz");
        ScanState state = sampleState();

        StateStore.save(state, file);

        ScanState read = loaded(StateStore.load(file, WORLD));
        assertStateEquals(state, read);
        assertNull(read.regions().get("r.0.0.mca").structures().get(1).endShipBox());
        assertArrayEquals(new int[] {7, 8, 9, 10, 11, 12}, read.regions().get("r.0.0.mca").structures().get(0).endShipBox());
    }

    @Test
    void loadedRegionsAndBiomesIterateInSortedOrder() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        StateStore.save(sampleState(), file);

        ScanState read = loaded(StateStore.load(file, WORLD));

        assertEquals(List.of("r.-1.2.mca", "r.0.0.mca"), new ArrayList<>(read.regions().keySet()));
        assertEquals(
                List.of("minecraft:end_highlands", "minecraft:plains"),
                new ArrayList<>(read.regions().get("r.0.0.mca").biomes().keySet()));
    }

    @Test
    void saveReplacesAnExistingStateAndLeavesNoTemporaryFile() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        StateStore.save(new ScanState(ScanState.FORMAT_VERSION, WORLD, new TreeMap<>()), file);

        StateStore.save(sampleState(), file);

        ScanState read = loaded(StateStore.load(file, WORLD));
        assertEquals(2, read.regions().size());
        assertFalse(Files.exists(dir.resolve("state.nbt.gz.tmp")));
    }

    @Test
    void loadWithoutAFileIsMissing() {
        assertInstanceOf(Loaded.Missing.class, StateStore.load(dir.resolve("none.nbt.gz"), WORLD));
    }

    @Test
    void garbageBytesAreFailedWithoutAnException() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void validNbtThatIsNotGzipIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        try (OutputStream out = Files.newOutputStream(file)) {
            BinaryTagIO.writer().write(rootTag(1, WORLD, regionTag(validTimestamps(), validBox())), out);
        }

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aWellFormedFileOfTheTestHelpersLoads() throws IOException {
        // Guards the helpers the failure tests below modify one detail of.
        Path file = dir.resolve("state.nbt.gz");
        writeTag(file, rootTag(1, WORLD, regionTag(validTimestamps(), validBox())));

        assertInstanceOf(Loaded.Ok.class, StateStore.load(file, WORLD));
    }

    @Test
    void anotherFormatVersionIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        writeTag(file, rootTag(2, WORLD, regionTag(validTimestamps(), validBox())));

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void anotherWorldIdIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        StateStore.save(sampleState(), file);

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, "another-world"));
    }

    @Test
    void timestampsWith1023EntriesAreFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        BinaryTag shortTimestamps = IntArrayBinaryTag.intArrayBinaryTag(new int[1023]);
        writeTag(file, rootTag(1, WORLD, regionTag(shortTimestamps, validBox())));

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aBoxThatIsALongArrayIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        BinaryTag longBox = LongArrayBinaryTag.longArrayBinaryTag(1L, 2L, 3L, 4L, 5L, 6L);
        writeTag(file, rootTag(1, WORLD, regionTag(validTimestamps(), longBox)));

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aBoxWithFiveEntriesIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        BinaryTag shortBox = IntArrayBinaryTag.intArrayBinaryTag(1, 2, 3, 4, 5);
        writeTag(file, rootTag(1, WORLD, regionTag(validTimestamps(), shortBox)));

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aBiomeBitsetOfTheWrongLengthIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        CompoundBinaryTag region = CompoundBinaryTag.builder()
                .put("timestamps", validTimestamps())
                .put("structures", ListBinaryTag.empty())
                .put("biomes", CompoundBinaryTag.builder().putLongArray("minecraft:plains", new long[15]).build())
                .build();
        writeTag(file, rootTag(1, WORLD, region));

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aMissingRegionsTagIsFailed() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        writeTag(file, CompoundBinaryTag.builder().putInt("formatVersion", 1).putString("worldId", WORLD).build());

        assertInstanceOf(Loaded.Failed.class, StateStore.load(file, WORLD));
    }

    @Test
    void aLeftoverTemporaryFileDoesNotAffectLoad() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        StateStore.save(sampleState(), file);
        Files.write(dir.resolve("state.nbt.gz.tmp"), new byte[] {9, 9, 9});

        assertStateEquals(sampleState(), loaded(StateStore.load(file, WORLD)));
        assertTrue(Files.exists(dir.resolve("state.nbt.gz.tmp")), "load does not touch the leftover");
    }

    @Test
    void aLeftoverTemporaryFileIsReplacedBySave() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        Files.write(dir.resolve("state.nbt.gz.tmp"), new byte[] {9, 9, 9});

        StateStore.save(sampleState(), file);

        assertInstanceOf(Loaded.Ok.class, StateStore.load(file, WORLD));
        assertFalse(Files.exists(dir.resolve("state.nbt.gz.tmp")));
    }

    @Test
    void aStateWithoutRegionsRoundTrips() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        ScanState empty = new ScanState(ScanState.FORMAT_VERSION, WORLD, new TreeMap<>());

        StateStore.save(empty, file);

        ScanState read = loaded(StateStore.load(file, WORLD));
        assertTrue(read.regions().isEmpty());
    }

    @Test
    void biomeKeysThatAreNoResourceLocationsAreDroppedWhenTheStateIsLoaded() throws IOException {
        Path file = dir.resolve("state.nbt.gz");
        CompoundBinaryTag biomes = CompoundBinaryTag.builder()
                .putLongArray("minecraft:plains", bits(1))
                .putLongArray("plains", bits(2))
                .putLongArray("minecraft:<img src=x onerror=1>", bits(3))
                .putLongArray("Minecraft:Plains", bits(4))
                .putLongArray("minecraft:" + "a".repeat(300), bits(5))
                .build();
        CompoundBinaryTag region = CompoundBinaryTag.builder()
                .put("timestamps", validTimestamps())
                .put("structures", ListBinaryTag.empty())
                .put("biomes", biomes)
                .build();
        writeTag(file, rootTag(ScanState.FORMAT_VERSION, WORLD, region));

        ScanState read = loaded(StateStore.load(file, WORLD));

        assertEquals(List.of("minecraft:plains"), new ArrayList<>(read.regions().get("r.0.0.mca").biomes().keySet()));
    }
}
