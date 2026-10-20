package dk.mekbits.bluemapworldscan.testsupport;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import dk.mekbits.bluemapworldscan.scan.CancellationToken;
import dk.mekbits.bluemapworldscan.scan.ScanLimits;
import dk.mekbits.bluemapworldscan.scan.ScanOutcome;
import dk.mekbits.bluemapworldscan.scan.WorldScanner;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;

/**
 * Measures how fast a full scan of a dimension folder is, with no rate cap: the time, the chunks
 * read per second and the bytes allocated per chunk read. It also prints a SHA-1 of what the scan
 * found, so two versions of the scanner can be checked to find the same structures and biomes.
 * The world is only read. Usage: {@code ScanBenchmark <dimension folder> [runs] [threads]}.
 */
public final class ScanBenchmark {

    private ScanBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: ScanBenchmark <dimension folder> [runs] [threads]");
        }
        Path dimension = Path.of(args[0]);
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int scanThreads = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();

        for (int run = 1; run <= runs; run++) {
            ScanState empty = new ScanState(ScanState.FORMAT_VERSION, "benchmark", new TreeMap<>());
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long cpuBefore = threads.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            ScanOutcome outcome = WorldScanner.scan(
                    dimension, empty, new ScanLimits(0, scanThreads), new CancellationToken(), state -> {
                    });
            double seconds = (System.nanoTime() - start) / 1e9;
            double cpuSeconds = (threads.getCurrentThreadCpuTime() - cpuBefore) / 1e9;
            long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            long read = outcome.report().read() + outcome.report().pre113();
            // The CPU time and the allocations are those of this thread, so they say nothing when other threads read.
            String perChunk = scanThreads > 1 ? "" : String.format(Locale.ROOT, " (cpu %.2f s), %.1f KB allocated per chunk",
                    cpuSeconds, allocated / 1024.0 / Math.max(1, read));
            System.out.printf(Locale.ROOT, "run %d: %d chunks in %.2f s, %.0f chunks/s%s, state %s%n",
                    run, read, seconds, read / seconds, perChunk, fingerprint(outcome.state()));
        }
    }

    /** A SHA-1 of every timestamp, structure and biome bit of the state, in a fixed order. */
    static String fingerprint(ScanState state) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
            digest.update(region.getKey().getBytes(StandardCharsets.UTF_8));
            RegionEntry entry = region.getValue();
            ByteBuffer timestamps = ByteBuffer.allocate(entry.timestamps().length * Integer.BYTES);
            timestamps.asIntBuffer().put(entry.timestamps());
            digest.update(timestamps.array());
            for (StructureEntry structure : entry.structures()) {
                digest.update((structure.chunkIndex() + structure.structureId()).getBytes(StandardCharsets.UTF_8));
                ints(digest, structure.box());
                if (structure.endShipBox() != null) {
                    ints(digest, structure.endShipBox());
                }
            }
            for (Map.Entry<String, long[]> biome : entry.biomes().entrySet()) {
                digest.update(biome.getKey().getBytes(StandardCharsets.UTF_8));
                ByteBuffer bits = ByteBuffer.allocate(biome.getValue().length * Long.BYTES);
                bits.asLongBuffer().put(biome.getValue());
                digest.update(bits.array());
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static void ints(MessageDigest digest, int[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES);
        buffer.asIntBuffer().put(values);
        digest.update(buffer.array());
    }
}
