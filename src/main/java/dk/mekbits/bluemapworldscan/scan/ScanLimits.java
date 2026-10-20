package dk.mekbits.bluemapworldscan.scan;

/**
 * What limits the scan's use of CPU and disk.
 *
 * @param maxChunksPerSecond the most chunks the scan reads per second, over all its threads; chunks
 *     skipped as unchanged do not count. 0 is no cap.
 * @param threads how many region files are read at the same time, from 1 to {@link #MAX_THREADS}
 */
public record ScanLimits(int maxChunksPerSecond, int threads) {

    /** More threads than this only wait for the disk. */
    public static final int MAX_THREADS = 64;

    public ScanLimits {
        if (maxChunksPerSecond < 0) {
            throw new IllegalArgumentException("maxChunksPerSecond must not be negative: " + maxChunksPerSecond);
        }
        if (threads < 1 || threads > MAX_THREADS) {
            throw new IllegalArgumentException("threads must be from 1 to " + MAX_THREADS + ": " + threads);
        }
    }

    /** One thread at the given cap. */
    public ScanLimits(int maxChunksPerSecond) {
        this(maxChunksPerSecond, 1);
    }
}
