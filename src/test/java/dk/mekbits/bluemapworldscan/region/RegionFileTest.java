package dk.mekbits.bluemapworldscan.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import dk.mekbits.bluemapworldscan.testsupport.TestRegions;
import dk.mekbits.bluemapworldscan.testsupport.TestRegions.TestChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFileTest {

    private static final String FIXTURE = "/chunks/overworld-1.18.2plus-struct-monument-dv4903-c.-440.-27.nbt";
    private static final int TIMESTAMP = 1_700_000_000;

    private static byte[] nbt;

    @TempDir
    Path dir;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = RegionFileTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "fixture " + FIXTURE + " is not committed");
            nbt = in.readAllBytes();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    @Test
    void presentChunkKeepsItsTimestampAndYieldsTheSameNbt() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, false)));

        RegionFile region = RegionFile.open(file);

        assertTrue(region.present(5));
        assertEquals(TIMESTAMP, region.timestamp(5));
        assertArrayEquals(nbt, read(region.openChunk(5)));
        assertFalse(region.present(6));
        assertEquals(0, region.timestamp(6));
        assertThrows(ChunkReadException.class, () -> region.openChunk(6));
    }

    @Test
    void gzipChunkYieldsTheSameNbt() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 1, false)));

        RegionFile region = RegionFile.open(file);

        assertArrayEquals(nbt, read(region.openChunk(5)));
        assertNull(region.externalStamp(5));
    }

    @Test
    void uncompressedChunkYieldsTheSameNbt() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 3, false)));

        RegionFile region = RegionFile.open(file);

        assertArrayEquals(nbt, read(region.openChunk(5)));
        assertNull(region.externalStamp(5));
    }

    @Test
    void lz4Chunk_synthetic_yieldsTheSameNbt() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 4, false)));

        RegionFile region = RegionFile.open(file);

        assertArrayEquals(nbt, read(region.openChunk(5)));
        assertNull(region.externalStamp(5));
    }

    @Test
    void externalLz4Chunk_synthetic_yieldsTheSameNbtAndAnExternalStamp() throws IOException {
        Path file = dir.resolve("r.-1.2.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 4, true)));
        // region (-1, 2), index 5 is chunk (-32 + 5, 64 + 0)
        Path external = dir.resolve("c.-27.64.mcc");
        assertTrue(Files.exists(external));

        RegionFile region = RegionFile.open(file);
        assertNull(region.externalStamp(5));

        assertArrayEquals(nbt, read(region.openChunk(5)));

        RegionFile.ExternalStamp stamp = region.externalStamp(5);
        assertNotNull(stamp);
        assertEquals(Files.getLastModifiedTime(external), stamp.lastModified());
    }

    @Test
    void externalChunkWithoutItsMccFile_synthetic_isAChunkReadException() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, true)));
        Files.delete(dir.resolve("c.5.0.mcc"));

        RegionFile region = RegionFile.open(file);

        assertThrows(ChunkReadException.class, () -> region.openChunk(5));
    }

    @Test
    void rewrittenRegionShowsAMovedOffsetWhenTheHeaderIsReadAgain() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, false)));
        RegionFile region = RegionFile.open(file);
        int before = region.header().offsets()[5];

        TestRegions.write(file, Map.of(
                0, new TestChunk(nbt, TIMESTAMP, 2, false),
                5, new TestChunk(nbt, TIMESTAMP, 2, false)));

        assertNotEquals(before, region.readHeaderAgain().offsets()[5]);
        assertEquals(before, region.header().offsets()[5]);
    }

    @Test
    void lengthRunningPastTheEndOfTheFileIsAChunkReadException() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 3, false)));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(8192 + 5 + nbt.length - 10);
        }

        RegionFile region = RegionFile.open(file);

        assertTrue(region.present(5));
        assertThrows(ChunkReadException.class, () -> region.openChunk(5));
    }

    @Test
    void customCompression127IsUnsupported() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 127, false)));

        RegionFile region = RegionFile.open(file);

        ChunkReadException e = assertThrows(ChunkReadException.class, () -> region.openChunk(5));
        assertEquals("unsupported compression 127", e.getMessage());
    }

    @Test
    void fileUnder8KiBHasNoChunks() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, new byte[100]);

        RegionFile region = RegionFile.open(file);

        for (int index = 0; index < 1024; index++) {
            assertFalse(region.present(index));
        }
    }

    @Test
    void emptyFileHasNoChunks() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, new byte[0]);

        assertFalse(RegionFile.open(file).present(0));
    }

    @Test
    void coordsParseRegionFileNamesAndRejectOtherNames() {
        assertArrayEquals(new int[] {-3, 12}, RegionFile.coords("r.-3.12.mca"));
        assertNull(RegionFile.coords("r.0.1.mca.bak"));
        assertNull(RegionFile.coords("c.1.2.mcc"));
    }
}
