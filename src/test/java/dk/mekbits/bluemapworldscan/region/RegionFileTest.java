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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
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

    /** Replaces the sector count in the low byte of a chunk's header entry. */
    private static void setSectorCount(Path file, int index, int sectors) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            channel.read(entry, index * 4L);
            entry.flip();
            int updated = (entry.getInt() & 0xFFFFFF00) | sectors;
            channel.write(ByteBuffer.allocate(4).putInt(updated).flip(), index * 4L);
        }
    }

    @Test
    void aChunkWithSectorCountZeroIsAChunkReadException() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, false)));
        setSectorCount(file, 5, 0);

        RegionFile region = RegionFile.open(file);

        assertTrue(region.present(5));
        assertThrows(ChunkReadException.class, () -> region.openChunk(5));
    }

    @Test
    void aLengthThatDoesNotFitItsSectorsIsAChunkReadExceptionEvenWhenTheFileHoldsTheData() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] twoSectors = new byte[5000];
        TestRegions.write(file, Map.of(
                5, new TestChunk(twoSectors, TIMESTAMP, 3, false),
                6, new TestChunk(nbt, TIMESTAMP, 3, false)));
        assertEquals(2, RegionFile.open(file).header().offsets()[5] & 0xFF);
        setSectorCount(file, 5, 1);

        RegionFile region = RegionFile.open(file);

        ChunkReadException e = assertThrows(ChunkReadException.class, () -> region.openChunk(5));
        assertTrue(e.getMessage().contains("sectors"), e.getMessage());
        assertArrayEquals(nbt, read(region.openChunk(6)));
    }

    @Test
    void aLengthThatFillsItsSectorsExactlyIsRead() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] filling = new byte[2 * 4096 - 5];
        System.arraycopy(nbt, 0, filling, 0, nbt.length);
        TestRegions.write(file, Map.of(5, new TestChunk(filling, TIMESTAMP, 3, false)));

        RegionFile region = RegionFile.open(file);

        assertEquals(2, region.header().offsets()[5] & 0xFF);
        assertArrayEquals(filling, read(region.openChunk(5)));
    }

    @Test
    void customCompression127IsUnsupported() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 127, false)));

        RegionFile region = RegionFile.open(file);

        UnsupportedCompressionException e = assertThrows(UnsupportedCompressionException.class, () -> region.openChunk(5));
        assertEquals("unsupported compression 127", e.getMessage());
        assertEquals(127, e.compression());
    }

    @Test
    void unsupportedCompressionOfAnExternalChunkKeepsTheTypeWithoutTheExternalFlag() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 9, true)));

        RegionFile region = RegionFile.open(file);

        UnsupportedCompressionException e = assertThrows(UnsupportedCompressionException.class, () -> region.openChunk(5));
        assertEquals(9, e.compression());
    }

    @Test
    void externalFileChangedIsFalseWhileTheMccFileIsUntouched() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, true)));
        RegionFile region = RegionFile.open(file);
        read(region.openChunk(5));

        assertFalse(region.externalFileChanged(5));
    }

    @Test
    void externalFileChangedIsTrueWhenTheMccFileIsReplacedWithALaterModificationTime() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, true)));
        Path external = dir.resolve("c.5.0.mcc");
        RegionFile region = RegionFile.open(file);
        read(region.openChunk(5));
        FileTime opened = Files.getLastModifiedTime(external);

        Files.write(external, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(external, FileTime.from(opened.toInstant().plusSeconds(10)));

        assertTrue(region.externalFileChanged(5));
    }

    @Test
    void externalFileChangedIsTrueWhenTheMccFileIsDeleted() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, true)));
        RegionFile region = RegionFile.open(file);
        read(region.openChunk(5));

        Files.delete(dir.resolve("c.5.0.mcc"));

        assertTrue(region.externalFileChanged(5));
    }

    @Test
    void externalFileChangedIsFalseForAnInternalChunk() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        TestRegions.write(file, Map.of(5, new TestChunk(nbt, TIMESTAMP, 2, false)));
        RegionFile region = RegionFile.open(file);
        read(region.openChunk(5));

        assertFalse(region.externalFileChanged(5));
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
