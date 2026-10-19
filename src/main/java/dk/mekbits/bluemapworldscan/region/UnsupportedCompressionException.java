package dk.mekbits.bluemapworldscan.region;

/**
 * A chunk is stored with a compression type the addon cannot read (anything but gzip, zlib,
 * uncompressed and LZ4, including the custom type 127). The scanner counts these apart from
 * other unreadable chunks.
 */
public final class UnsupportedCompressionException extends ChunkReadException {

    private final int compression;

    public UnsupportedCompressionException(int compression) {
        super("unsupported compression " + compression);
        this.compression = compression;
    }

    /** The compression type byte, without the external flag. */
    public int compression() {
        return compression;
    }
}
