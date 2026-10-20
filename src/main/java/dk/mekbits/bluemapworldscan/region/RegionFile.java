package dk.mekbits.bluemapworldscan.region;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
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
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import net.jpountz.lz4.LZ4BlockInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only view of one Minecraft region file ({@code r.<x>.<z>.mca}).
 *
 * <p>The file is opened read-only once, by {@link #open}, and the chunks are read through it until
 * {@link #close}, so a scan does not open it again for every chunk. It is never locked, because the
 * game may be writing to it; the game writes a region file in place, so the open file sees its writes. The header
 * layout, the compression types and the external {@code .mcc} chunks are modelled on BlueMap's
 * {@code MCARegion} (MIT, see {@code THIRD_PARTY_NOTICES.md}).
 */
public final class RegionFile implements Closeable {

    /** Names of region files; any other name (such as {@code r.0.1.mca.bak}) is not one. */
    public static final Pattern FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private static final int CHUNKS = 1024;
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int EXTERNAL_FLAG = 128;
    private static final int STREAM_BUFFER_BYTES = 64 << 10;

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
    private final FileChannel channel;
    private final RegionHeader header;
    private final Map<Integer, ExternalStamp> externalStamps = new ConcurrentHashMap<>();

    private RegionFile(Path file, FileChannel channel, RegionHeader header) {
        this.file = file;
        this.channel = channel;
        this.header = header;
    }

    /**
     * Opens a region file read-only and reads its header. A file shorter than 8 KiB has no chunks.
     * The caller closes it.
     */
    public static RegionFile open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            return new RegionFile(file, channel, readHeader(channel));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
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
     * The file is opened again by its path for this, so a region file that was deleted or replaced
     * meanwhile shows up as missing or changed, not as the file that is still open.
     */
    public RegionHeader readHeaderAgain() throws IOException {
        try (FileChannel again = FileChannel.open(file, StandardOpenOption.READ)) {
            return readHeader(again);
        }
    }

    /**
     * The {@code lastModifiedTime} and {@code fileKey} of the {@code .mcc} file the last read of
     * this index opened, or null for an internal chunk (or when it was not read).
     */
    public @Nullable ExternalStamp externalStamp(int index) {
        return externalStamps.get(index);
    }

    /**
     * Reads the decompressed NBT bytes of a chunk into {@link ChunkBuffers#data()}, which holds
     * them until the next read with the same buffers.
     *
     * @return the number of bytes
     * @throws ChunkReadException the chunk is absent, its offset or length is outside the file or
     *     0, its sector count is 0 or too small for its length, its compression is unknown, its
     *     external file is missing, its data cannot be decompressed, or it is larger than the
     *     buffers allow
     */
    public int readChunk(int index, ChunkBuffers buffers) throws ChunkReadException {
        Objects.checkIndex(index, CHUNKS);
        externalStamps.remove(index);

        int entry = header.offsets()[index];
        if (entry == 0) {
            throw new ChunkReadException("chunk " + index + " is not present");
        }
        long position = (long) (entry >>> 8) * SECTOR_BYTES;
        int sectors = entry & 0xFF;
        if (sectors == 0) {
            throw new ChunkReadException("chunk " + index + " occupies no sectors");
        }

        try {
            long size = channel.size();
            if (position < HEADER_BYTES || position + CHUNK_HEADER_BYTES > size) {
                throw new ChunkReadException("chunk " + index + " starts outside the file");
            }
            // One read of the chunk's sectors: its 5-byte header and its data.
            int span = (int) Math.min((long) sectors * SECTOR_BYTES, size - position);
            byte[] raw = buffers.raw(span);
            int got = readUpTo(channel, ByteBuffer.wrap(raw, 0, span), position);
            if (got < CHUNK_HEADER_BYTES) {
                throw new ChunkReadException("the file ended while reading a chunk");
            }
            int length = (raw[0] & 0xFF) << 24 | (raw[1] & 0xFF) << 16 | (raw[2] & 0xFF) << 8 | raw[3] & 0xFF;
            int type = raw[4] & 0xFF;

            if (length <= 0) {
                throw new ChunkReadException("chunk " + index + " has length " + length);
            }
            // Minecraft rejects the same: a chunk is read into the room its sectors give it, no more.
            if (length - 1L > (long) sectors * SECTOR_BYTES - CHUNK_HEADER_BYTES) {
                throw new ChunkReadException("chunk " + index + " has length " + length + " but occupies " + sectors + " sectors");
            }
            boolean external = (type & EXTERNAL_FLAG) != 0;
            int compression = type & ~EXTERNAL_FLAG;
            if (!isSupported(compression)) {
                throw new UnsupportedCompressionException(compression);
            }

            if (external) {
                return readExternal(index, compression, buffers);
            }
            long dataLength = length - 1L;
            if (position + CHUNK_HEADER_BYTES + dataLength > size) {
                throw new ChunkReadException("chunk " + index + " runs past the end of the file");
            }
            if (CHUNK_HEADER_BYTES + dataLength > got) {
                throw new ChunkReadException("the file ended while reading a chunk");
            }
            if (compression == ZLIB) {
                return inflate(raw, CHUNK_HEADER_BYTES, (int) dataLength, buffers);
            }
            return readAll(decompress(new ByteArrayInputStream(raw, CHUNK_HEADER_BYTES, (int) dataLength), compression, buffers), buffers);
        } catch (ChunkReadException e) {
            throw e;
        } catch (IOException e) {
            throw new ChunkReadException("chunk " + index + " could not be read: " + e, e);
        }
    }

    /**
     * Opens the decompressed NBT bytes of a chunk, as {@link #readChunk} reads them, with buffers
     * of its own for this one chunk. The caller closes the stream.
     */
    public InputStream openChunk(int index) throws ChunkReadException {
        try (ChunkBuffers buffers = new ChunkBuffers(Integer.MAX_VALUE)) {
            int length = readChunk(index, buffers);
            return new ByteArrayInputStream(Arrays.copyOf(buffers.data(), length));
        }
    }

    /**
     * Whether the {@code .mcc} file the last read of this index opened has changed since: it is
     * gone, or its {@code lastModifiedTime} or {@code fileKey} differ from the stamp taken when it
     * was opened. False for an internal chunk, or one that was not read.
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

    /** Closes the file. */
    @Override
    public void close() throws IOException {
        channel.close();
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

    private int readExternal(int index, int compression, ChunkBuffers buffers) throws IOException {
        Path external = externalPath(index);

        // The stamp is read before the content, so a file replaced in between shows up as changed.
        BasicFileAttributes attributes;
        InputStream raw;
        try {
            attributes = Files.readAttributes(external, BasicFileAttributes.class);
            raw = Files.newInputStream(external, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw new ChunkReadException("external chunk file " + external.getFileName() + " is missing", e);
        }
        externalStamps.put(index, new ExternalStamp(attributes.lastModifiedTime(), attributes.fileKey()));
        return readAll(decompress(new BufferedInputStream(raw, STREAM_BUFFER_BYTES), compression, buffers), buffers);
    }

    private static boolean isSupported(int compression) {
        return compression == GZIP || compression == ZLIB || compression == NONE || compression == LZ4;
    }

    /** zlib data straight from the raw buffer into the data buffer, with the buffers' inflater. */
    private static int inflate(byte[] raw, int offset, int length, ChunkBuffers buffers) throws ChunkReadException {
        Inflater inflater = buffers.inflater;
        inflater.reset();
        inflater.setInput(raw, offset, length);
        int filled = 0;
        try {
            while (!inflater.finished()) {
                if (filled == buffers.data.length && !buffers.growData(filled)) {
                    throw tooLarge(buffers);
                }
                int inflated = inflater.inflate(buffers.data, filled, buffers.data.length - filled);
                filled += inflated;
                if (inflated == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new ChunkReadException("chunk data cannot be decompressed: the zlib data ends early");
                }
            }
        } catch (DataFormatException e) {
            throw new ChunkReadException("chunk data cannot be decompressed: " + e, e);
        }
        if (filled > buffers.maxDataBytes) {
            throw tooLarge(buffers);
        }
        return filled;
    }

    /** Reads a decompressing stream to its end into the data buffer, and closes it. */
    private static int readAll(InputStream in, ChunkBuffers buffers) throws ChunkReadException {
        try (in) {
            int filled = 0;
            while (true) {
                if (filled == buffers.data.length && !buffers.growData(filled)) {
                    throw tooLarge(buffers);
                }
                int read = in.read(buffers.data, filled, buffers.data.length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
            if (filled > buffers.maxDataBytes) {
                throw tooLarge(buffers);
            }
            return filled;
        } catch (ChunkReadException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ChunkReadException("chunk data cannot be decompressed: " + e, e);
        }
    }

    private static ChunkReadException tooLarge(ChunkBuffers buffers) {
        return new ChunkReadException("the chunk is larger than " + buffers.maxDataBytes + " bytes decompressed");
    }

    private static InputStream decompress(InputStream in, int compression, ChunkBuffers buffers) throws ChunkReadException {
        try {
            return switch (compression) {
                case GZIP -> new GZIPInputStream(in, STREAM_BUFFER_BYTES);
                case ZLIB -> {
                    buffers.inflater.reset();
                    // An inflater passed in is not ended when the stream is closed; the buffers end it.
                    yield new InflaterInputStream(in, buffers.inflater, STREAM_BUFFER_BYTES);
                }
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

    private static RegionHeader readHeader(FileChannel channel) throws IOException {
        int[] offsets = new int[CHUNKS];
        int[] timestamps = new int[CHUNKS];
        if (channel.size() < HEADER_BYTES) {
            return new RegionHeader(offsets, timestamps);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
        if (readUpTo(channel, buffer, 0) < HEADER_BYTES) {
            return new RegionHeader(offsets, timestamps);
        }
        buffer.flip();
        buffer.asIntBuffer().get(offsets);
        buffer.position(SECTOR_BYTES);
        buffer.asIntBuffer().get(timestamps);
        return new RegionHeader(offsets, timestamps);
    }

    /** Reads until the buffer is full or the file ends; the number of bytes read. */
    private static int readUpTo(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int start = buffer.position();
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                break;
            }
            at += read;
        }
        return buffer.position() - start;
    }
}
