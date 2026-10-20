package dk.mekbits.bluemapworldscan.region;

import java.util.zip.Inflater;

/**
 * The buffers and the zlib inflater that {@link RegionFile#readChunk} reads chunks with, kept from
 * one chunk to the next so a scan does not allocate them per chunk. One thread uses one instance;
 * {@link #close} frees the inflater's native memory.
 */
public final class ChunkBuffers implements AutoCloseable {

    private static final int INITIAL_RAW_BYTES = 64 << 10;
    private static final int INITIAL_DATA_BYTES = 256 << 10;

    final int maxDataBytes;
    final Inflater inflater = new Inflater();
    byte[] raw = new byte[INITIAL_RAW_BYTES];
    byte[] data;

    /** @param maxDataBytes the most decompressed bytes a chunk may have; a larger one cannot be read */
    public ChunkBuffers(long maxDataBytes) {
        this.maxDataBytes = (int) Math.min(maxDataBytes, Integer.MAX_VALUE - 8);
        this.data = new byte[Math.min(INITIAL_DATA_BYTES, this.maxDataBytes + 1)];
    }

    /** The decompressed bytes of the chunk read last; the length is what {@link RegionFile#readChunk} returned. */
    public byte[] data() {
        return data;
    }

    /** The raw buffer, at least {@code bytes} long; what it held is lost. */
    byte[] raw(int bytes) {
        if (raw.length < bytes) {
            raw = new byte[Math.max(bytes, raw.length * 2)];
        }
        return raw;
    }

    /** The data buffer grown to hold more than {@code filled} bytes, keeping them; false when it is at the limit. */
    boolean growData(int filled) {
        if (data.length > maxDataBytes) {
            return false;
        }
        int size = (int) Math.min((long) maxDataBytes + 1, Math.max((long) data.length * 2, filled + 1L));
        byte[] grown = new byte[size];
        System.arraycopy(data, 0, grown, 0, filled);
        data = grown;
        return true;
    }

    @Override
    public void close() {
        inflater.end();
    }
}
