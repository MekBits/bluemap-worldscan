package dk.mekbits.bluemapworldscan.region;

import java.io.IOException;

/**
 * A chunk of a region file cannot be read: it is absent, outside the file, truncated, stored with
 * an unsupported compression, or its external file is missing. The scanner counts the chunk as
 * failed and reads it again on the next scan.
 */
public final class ChunkReadException extends IOException {

    public ChunkReadException(String reason) {
        super(reason);
    }

    public ChunkReadException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
