package dk.mekbits.bluemapworldscan.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import dk.mekbits.bluemapworldscan.region.RegionFile;
import net.jpountz.lz4.LZ4BlockOutputStream;

/**
 * Builds region files for tests from NBT bytes: the header, the sector layout and, for external
 * chunks, the {@code c.<x>.<z>.mcc} files beside the region file.
 */
public final class TestRegions {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int MAX_SECTORS = 255;

    private TestRegions() {
    }

    /**
     * One chunk of a test region.
     *
     * @param nbt the uncompressed NBT bytes
     * @param timestamp the save timestamp for the header, in seconds
     * @param compression the compression type: 1 gzip, 2 zlib, 3 none, 4 LZ4; any other value
     *     stores the bytes as they are under that type byte
     * @param external whether the data goes to a {@code .mcc} file (type byte plus 128)
     */
    public record TestChunk(byte[] nbt, int timestamp, int compression, boolean external) {
    }

    /**
     * Writes a region file, replacing an existing one, with the chunks laid out in ascending
     * index order from sector 2. External chunks are written to {@code .mcc} files beside it,
     * named after the region file's coordinates.
     *
     * @param regionFile a path named {@code r.<x>.<z>.mca}
     * @param chunksByIndex the chunks by index ({@code x + 32 * z})
     */
    public static void write(Path regionFile, Map<Integer, TestChunk> chunksByIndex) throws IOException {
        int[] region = RegionFile.coords(regionFile.getFileName().toString());
        if (region == null) {
            throw new IllegalArgumentException("not a region file name: " + regionFile.getFileName());
        }

        ByteBuffer header = ByteBuffer.allocate(HEADER_SECTORS * SECTOR_BYTES);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int nextSector = HEADER_SECTORS;

        for (Map.Entry<Integer, TestChunk> entry : new TreeMap<>(chunksByIndex).entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= 1024) {
                throw new IllegalArgumentException("chunk index out of range: " + index);
            }
            TestChunk chunk = entry.getValue();
            byte[] payload = compress(chunk.nbt(), chunk.compression());

            int type = chunk.compression() | (chunk.external() ? 128 : 0);
            byte[] stored;
            if (chunk.external()) {
                int chunkX = region[0] * 32 + (index & 31);
                int chunkZ = region[1] * 32 + (index >> 5);
                Files.write(regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc"), payload);
                stored = new byte[0];
            } else {
                stored = payload;
            }

            ByteBuffer chunkBytes = ByteBuffer.allocate(4 + 1 + stored.length);
            chunkBytes.putInt(stored.length + 1).put((byte) type).put(stored);
            int sectors = (chunkBytes.capacity() + SECTOR_BYTES - 1) / SECTOR_BYTES;
            if (sectors > MAX_SECTORS) {
                throw new IllegalArgumentException("chunk " + index + " needs " + sectors + " sectors; make it external");
            }

            header.putInt(index * 4, (nextSector << 8) | sectors);
            header.putInt(SECTOR_BYTES + index * 4, chunk.timestamp());
            body.write(chunkBytes.array());
            body.write(new byte[sectors * SECTOR_BYTES - chunkBytes.capacity()]);
            nextSector += sectors;
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(header.array());
        body.writeTo(file);
        Files.write(regionFile, file.toByteArray());
    }

    private static byte[] compress(byte[] nbt, int compression) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream compressing = switch (compression) {
            case 1 -> new GZIPOutputStream(out);
            case 2 -> new DeflaterOutputStream(out);
            case 4 -> new LZ4BlockOutputStream(out);
            default -> out;
        }) {
            compressing.write(nbt);
        }
        return out.toByteArray();
    }
}
