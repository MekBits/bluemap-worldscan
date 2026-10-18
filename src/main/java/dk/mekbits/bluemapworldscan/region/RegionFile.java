package dk.mekbits.bluemapworldscan.region;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.jpountz.lz4.LZ4BlockInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only view of one Minecraft region file ({@code r.<x>.<z>.mca}).
 *
 * <p>The file is opened read-only for each read and never locked, because the game may be writing
 * to it. The header layout, the compression types and the external {@code .mcc} chunks are
 * modelled on BlueMap's {@code MCARegion} (MIT, see {@code THIRD_PARTY_NOTICES.md}).
 */
public final class RegionFile {

    /** Names of region files; any other name (such as {@code r.0.1.mca.bak}) is not one. */
    public static final Pattern FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private static final int CHUNKS = 1024;
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int EXTERNAL_FLAG = 128;

    private static final int GZIP = 1;
    private static final int ZLIB = 2;
    private static final int NONE = 3;
    private static final int LZ4 = 4;

    /**
     * The raw header: per chunk index ({@code x + 32 * z}) the 4-byte offset entry (sector offset
     * in the upper 24 bits, sector count in the lower 8; 0 means no chunk) and the save timestamp
     * in seconds. Record equality compares the arrays by reference.
     */
    public record RegionHeader(int[] offsets, int[] timestamps) {
    }

    /** Last modification time and file key of the {@code .mcc} file an external chunk was read from. */
    public record ExternalStamp(FileTime lastModified, @Nullable Object fileKey) {
    }

    private final Path file;
    private final RegionHeader header;
    private final Map<Integer, ExternalStamp> externalStamps = new ConcurrentHashMap<>();

    private RegionFile(Path file, RegionHeader header) {
        this.file = file;
        this.header = header;
    }

    /**
     * Reads the header of a region file. A file shorter than 8 KiB has no chunks.
     */
    public static RegionFile open(Path file) throws IOException {
        return new RegionFile(file, readHeader(file));
    }

    /**
     * Region coordinates of a region file name, as {@code {x, z}}, or null when the name is not
     * a region file name.
     */
    public static @Nullable int[] coords(String fileName) {
        Matcher matcher = FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The header as read by {@link #open}. */
    public RegionHeader header() {
        return header;
    }

    /** The save timestamp in seconds of a chunk index, from the header read by {@link #open}. */
    public int timestamp(int index) {
        return header.timestamps()[Objects.checkIndex(index, CHUNKS)];
    }

    /** Whether the header has a chunk at this index (offset entry is not 0). */
    public boolean present(int index) {
        return header.offsets()[Objects.checkIndex(index, CHUNKS)] != 0;
    }

    /**
     * Reads the header from disk again, to compare it with {@link #header()} after the chunks
     * were read. A header read while the game rewrites it can mix old offsets with new timestamps.
     */
    public RegionHeader readHeaderAgain() throws IOException {
        return readHeader(file);
    }

    /**
     * The {@code lastModifiedTime} and {@code fileKey} of the {@code .mcc} file the last
     * {@link #openChunk} of this index opened, or null for an internal chunk (or when it was not
     * opened).
     */
    public @Nullable ExternalStamp externalStamp(int index) {
        return externalStamps.get(index);
    }

    /**
     * Opens the decompressed NBT bytes of a chunk. The caller closes the stream.
     *
     * @throws ChunkReadException the chunk is absent, its offset or length is outside the file or
     *     0, its compression is unknown, or its external file is missing
     */
    public InputStream openChunk(int index) throws ChunkReadException {
        Objects.checkIndex(index, CHUNKS);
        externalStamps.remove(index);

        int entry = header.offsets()[index];
        if (entry == 0) {
            throw new ChunkReadException("chunk " + index + " is not present");
        }
        long position = (long) (entry >>> 8) * SECTOR_BYTES;

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (position < HEADER_BYTES || position + CHUNK_HEADER_BYTES > size) {
                throw new ChunkReadException("chunk " + index + " starts outside the file");
            }
            ByteBuffer chunkHeader = ByteBuffer.allocate(CHUNK_HEADER_BYTES);
            readFully(channel, chunkHeader, position);
            chunkHeader.flip();
            int length = chunkHeader.getInt();
            int type = chunkHeader.get() & 0xFF;

            if (length <= 0) {
                throw new ChunkReadException("chunk " + index + " has length " + length);
            }
            boolean external = (type & EXTERNAL_FLAG) != 0;
            int compression = type & ~EXTERNAL_FLAG;
            if (!isSupported(compression)) {
                throw new UnsupportedCompressionException(compression);
            }

            if (external) {
                return openExternal(index, compression);
            }
            long dataLength = length - 1L;
            if (position + CHUNK_HEADER_BYTES + dataLength > size) {
                throw new ChunkReadException("chunk " + index + " runs past the end of the file");
            }
            ByteBuffer data = ByteBuffer.allocate((int) dataLength);
            readFully(channel, data, position + CHUNK_HEADER_BYTES);
            return decompress(new ByteArrayInputStream(data.array()), compression);
        } catch (ChunkReadException e) {
            throw e;
        } catch (IOException e) {
            throw new ChunkReadException("chunk " + index + " could not be read: " + e, e);
        }
    }

    /**
     * Whether the {@code .mcc} file the last {@link #openChunk} of this index opened has changed
     * since: it is gone, or its {@code lastModifiedTime} or {@code fileKey} differ from the stamp
     * taken when it was opened. False for an internal chunk, or one that was not opened.
     */
    public boolean externalFileChanged(int index) {
        ExternalStamp stamp = externalStamps.get(index);
        if (stamp == null) {
            return false;
        }
        try {
            BasicFileAttributes now = Files.readAttributes(externalPath(index), BasicFileAttributes.class);
            return !stamp.lastModified().equals(now.lastModifiedTime()) || !Objects.equals(stamp.fileKey(), now.fileKey());
        } catch (IOException e) {
            return true;
        }
    }

    /** The {@code c.<x>.<z>.mcc} file beside the region file that holds the chunk at this index. */
    private Path externalPath(int index) throws ChunkReadException {
        int[] region = coords(file.getFileName().toString());
        if (region == null) {
            throw new ChunkReadException("external chunk " + index + " of a file that is not named r.<x>.<z>.mca");
        }
        int chunkX = region[0] * 32 + (index & 31);
        int chunkZ = region[1] * 32 + (index >> 5);
        return file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    private InputStream openExternal(int index, int compression) throws IOException {
        Path external = externalPath(index);

        // The stamp is read before the content: a file replaced in between then shows up as
        // changed when the scanner compares stamps, whereas the other order would hide it.
        BasicFileAttributes attributes;
        InputStream raw;
        try {
            attributes = Files.readAttributes(external, BasicFileAttributes.class);
            raw = Files.newInputStream(external, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw new ChunkReadException("external chunk file " + external.getFileName() + " is missing", e);
        }
        externalStamps.put(index, new ExternalStamp(attributes.lastModifiedTime(), attributes.fileKey()));
        return decompress(new BufferedInputStream(raw), compression);
    }

    private static boolean isSupported(int compression) {
        return compression == GZIP || compression == ZLIB || compression == NONE || compression == LZ4;
    }

    private static InputStream decompress(InputStream in, int compression) throws ChunkReadException {
        try {
            return switch (compression) {
                case GZIP -> new GZIPInputStream(in);
                case ZLIB -> new InflaterInputStream(in);
                case LZ4 -> LZ4BlockInputStream.newBuilder().build(in);
                default -> in;
            };
        } catch (IOException | RuntimeException e) {
            try {
                in.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new ChunkReadException("chunk data cannot be decompressed: " + e, e);
        }
    }

    private static RegionHeader readHeader(Path file) throws IOException {
        int[] offsets = new int[CHUNKS];
        int[] timestamps = new int[CHUNKS];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                return new RegionHeader(offsets, timestamps);
            }
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
            if (!tryReadFully(channel, buffer, 0)) {
                return new RegionHeader(offsets, timestamps);
            }
            buffer.flip();
            buffer.asIntBuffer().get(offsets);
            buffer.position(SECTOR_BYTES);
            buffer.asIntBuffer().get(timestamps);
        }
        return new RegionHeader(offsets, timestamps);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        if (!tryReadFully(channel, buffer, position)) {
            throw new ChunkReadException("the file ended while reading a chunk");
        }
    }

    private static boolean tryReadFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                return false;
            }
            at += read;
        }
        return true;
    }
}
