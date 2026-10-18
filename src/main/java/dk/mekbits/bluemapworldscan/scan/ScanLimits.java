package dk.mekbits.bluemapworldscan.scan;

/**
 * What limits the scan's use of CPU and disk.
 *
 * @param maxChunksPerSecond the most chunks the scan reads per second; chunks skipped as unchanged
 *     do not count. Must be at least 1.
 */
public record ScanLimits(int maxChunksPerSecond) {

    public ScanLimits {
        if (maxChunksPerSecond < 1) {
            throw new IllegalArgumentException("maxChunksPerSecond must be at least 1: " + maxChunksPerSecond);
        }
    }
}
