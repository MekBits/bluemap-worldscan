package dk.mekbits.bluemapworldscan.scan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo;
import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import dk.mekbits.bluemapworldscan.chunk.ChunkReader;
import dk.mekbits.bluemapworldscan.chunk.FoundStructure;
import dk.mekbits.bluemapworldscan.region.ChunkBuffers;
import dk.mekbits.bluemapworldscan.region.ChunkReadException;
import dk.mekbits.bluemapworldscan.region.RegionFile;
import dk.mekbits.bluemapworldscan.region.RegionFile.RegionHeader;
import dk.mekbits.bluemapworldscan.region.UnsupportedCompressionException;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;
import dk.mekbits.bluemapworldscan.util.Log;
import org.jetbrains.annotations.Nullable;

/**
 * Scans a world's region files for structures and biomes, reading only the chunks whose header
 * timestamp differs from the one in the previous state.
 *
 * <p>The world is only ever read: region files are opened read-only and never locked, because
 * the game may be writing to them. The game writes a chunk to new sectors, rewrites the 8 KiB
 * header and only then frees the old sectors (and moves a new {@code .mcc} file into place for an
 * oversized chunk). A chunk read while that happens could come back as another chunk's data or as
 * the old external file under the new timestamp, so a chunk is taken only when it sits in its own
 * slot and its header entry and external file are the same after the region's chunks were read.
 * Any other chunk keeps its old entries and old timestamp and is read again on the next scan.
 */
public final class WorldScanner {

    private static final int CHUNKS = 1024;
    private static final int BIOME_WORDS = CHUNKS / Long.SIZE;
    private static final int NEVER_READ = -1;
    private static final int CHECKPOINT_EVERY_REGIONS = 100;
    /** A checkpoint writes the whole state so far, so a fast scan must not write one every few seconds. */
    private static final long MIN_CHECKPOINT_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long SLEEP_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private WorldScanner() {
    }

    /**
     * Scans a dimension folder (the one that holds {@code region/}).
     *
     * <p>A dimension folder without a {@code region} folder, or one that cannot be listed, is
     * skipped: nothing is read and {@code previous} is returned unchanged, because a wrong path or
     * an unmounted volume must not wipe the stored layers. A region file that is no longer in
     * the folder drops out of the state.
     *
     * @param previous the state to start from; it is never changed
     * @param checkpoint called with the state so far after every 100 regions, but at most once a
     *     minute; an exception it throws ends the scan
     * @param cancel checked between chunks; a thread interrupt while the scan waits for the rate
     *     cap counts as a cancel
     */
    public static ScanOutcome scan(
            Path dimensionFolder,
            ScanState previous,
            ScanLimits limits,
            CancellationToken cancel,
            Consumer<ScanState> checkpoint) {
        return scan(dimensionFolder, previous, limits, cancel, checkpoint, (file, index) -> {
        }, MIN_CHECKPOINT_NANOS);
    }

    /**
     * As the public overload; {@code afterChunkRead} is called with the region file and the chunk
     * index after each chunk was read (or failed to be read). Tests use it to change the world in
     * the middle of a scan.
     */
    static ScanOutcome scan(
            Path dimensionFolder,
            ScanState previous,
            ScanLimits limits,
            CancellationToken cancel,
            Consumer<ScanState> checkpoint,
            BiConsumer<Path, Integer> afterChunkRead) {
        return scan(dimensionFolder, previous, limits, cancel, checkpoint, afterChunkRead, MIN_CHECKPOINT_NANOS);
    }

    /** As above, with the least time between two checkpoints; tests use 0. */
    static ScanOutcome scan(
            Path dimensionFolder,
            ScanState previous,
            ScanLimits limits,
            CancellationToken cancel,
            Consumer<ScanState> checkpoint,
            BiConsumer<Path, Integer> afterChunkRead,
            long minCheckpointNanos) {
        Path regionFolder = dimensionFolder.resolve("region");
        if (!Files.isDirectory(regionFolder)) {
            return skipped(previous);
        }
        SortedMap<String, Path> files;
        try {
            files = listRegionFiles(regionFolder);
        } catch (IOException e) {
            Log.warn("cannot list " + regionFolder + ": " + e);
            return skipped(previous);
        }

        // Regions that are gone drop out; the others start from their previous entry.
        SortedMap<String, RegionEntry> regions = new TreeMap<>();
        previous.regions().forEach((name, entry) -> {
            if (files.containsKey(name)) {
                regions.put(name, entry);
            }
        });

        ScanReport.Builder total = new ScanReport.Builder();
        Pacer pacer = new Pacer(limits.maxChunksPerSecond());
        boolean cancelled = false;
        int sinceCheckpoint = 0;
        long lastCheckpoint = System.nanoTime();
        int threads = limits.threads();
        // One thread reads on the caller's thread; more read whole regions in parallel, which are
        // taken in name order on the caller's thread, so the state and the checkpoints are the same.
        ExecutorService readers = threads > 1 ? newReaders(threads) : null;
        BlockingQueue<ChunkBuffers> buffers = new ArrayBlockingQueue<>(threads);
        for (int i = 0; i < threads; i++) {
            buffers.add(new ChunkBuffers(ChunkReader.MAX_NBT_BYTES));
        }
        try {
            Iterator<Map.Entry<String, Path>> pending = files.entrySet().iterator();
            Deque<Map.Entry<String, Future<RegionResult>>> inFlight = new ArrayDeque<>();
            int window = threads == 1 ? 1 : threads * 2;
            boolean stop = false;
            while (true) {
                while (!stop && inFlight.size() < window && pending.hasNext()) {
                    Map.Entry<String, Path> file = pending.next();
                    RegionEntry previousEntry = regions.get(file.getKey());
                    Callable<RegionResult> read = () -> scanFile(
                            file.getValue(), file.getKey(), previousEntry, pacer, cancel, afterChunkRead, buffers);
                    inFlight.add(Map.entry(file.getKey(), readers == null ? inline(read) : readers.submit(read)));
                }
                if (inFlight.isEmpty()) {
                    break;
                }
                Map.Entry<String, Future<RegionResult>> next = inFlight.removeFirst();
                RegionResult result = join(next.getValue());
                if (result == null || result.cut()) {
                    cancelled = true;
                    stop = true;
                    continue;
                }
                sinceCheckpoint++;
                if (result.entry() == null) {
                    total.failed++;
                    continue;
                }
                regions.put(next.getKey(), result.entry());
                total.add(result.tally());
                if (!stop && sinceCheckpoint >= CHECKPOINT_EVERY_REGIONS
                        && System.nanoTime() - lastCheckpoint >= minCheckpointNanos) {
                    checkpoint.accept(snapshot(previous, regions));
                    sinceCheckpoint = 0;
                    lastCheckpoint = System.nanoTime();
                }
            }
        } finally {
            if (readers != null) {
                readers.shutdownNow();
                awaitReaders(readers);
            }
            buffers.forEach(ChunkBuffers::close);
        }

        ScanReport report = total.build();
        Log.info((cancelled ? "cancelled scan of " : "scanned ") + dimensionFolder + ": " + report.summary());
        if (Log.isDebugEnabled()) {
            Log.debug("DataVersions of " + dimensionFolder + ": " + report.dataVersions());
        }
        return new ScanOutcome(snapshot(previous, regions), report, false, cancelled);
    }

    /**
     * What reading one region file gave: its new entry and its counts; no entry when the file could
     * not be opened (counted as failed); or {@code cut} when a cancel cut it off.
     */
    private record RegionResult(@Nullable RegionEntry entry, ScanReport.@Nullable Builder tally, boolean cut) {
        static final RegionResult CUT = new RegionResult(null, null, true);
        static final RegionResult NOT_OPENED = new RegionResult(null, null, false);
    }

    private static RegionResult scanFile(
            Path file,
            String name,
            @Nullable RegionEntry previous,
            Pacer pacer,
            CancellationToken cancel,
            BiConsumer<Path, Integer> afterChunkRead,
            BlockingQueue<ChunkBuffers> buffers) throws InterruptedException {
        if (cancel.isCancelled()) {
            return RegionResult.CUT;
        }
        int[] coords = Objects.requireNonNull(RegionFile.coords(name));
        RegionFile region;
        try {
            region = RegionFile.open(file);
        } catch (IOException e) {
            Log.warn("cannot open " + file + ": " + e);
            return RegionResult.NOT_OPENED;
        }
        ChunkBuffers own;
        try {
            own = buffers.take();
        } catch (InterruptedException e) {
            close(region, file);
            throw e;
        }
        try {
            ScanReport.Builder tally = new ScanReport.Builder();
            RegionEntry entry = scanRegion(region, file, coords[0], coords[1], previous, pacer, cancel, afterChunkRead, own, tally);
            return entry == null ? RegionResult.CUT : new RegionResult(entry, tally, false);
        } finally {
            buffers.add(own);
            close(region, file);
        }
    }

    /** Runs a read on the caller's thread; an exception it throws is thrown from {@link #join}. */
    private static Future<RegionResult> inline(Callable<RegionResult> read) {
        try {
            return CompletableFuture.completedFuture(read.call());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** The result of a region read, or null when the wait for it was interrupted, which counts as a cancel. */
    private static @Nullable RegionResult join(Future<RegionResult> result) {
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static ExecutorService newReaders(int threads) {
        AtomicInteger number = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "bluemap-worldscan-reader-" + number.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** An interrupted reader stops at its next chunk; one that is still busy after that is a daemon and is left. */
    private static void awaitReaders(ExecutorService readers) {
        try {
            readers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Closing a file that was only read cannot lose anything, so a failure is only logged. */
    private static void close(RegionFile region, Path file) {
        try {
            region.close();
        } catch (IOException e) {
            Log.debug("cannot close " + file + ": " + e);
        }
    }

    private static ScanOutcome skipped(ScanState previous) {
        return new ScanOutcome(previous, ScanReport.empty(), true, false);
    }

    private static ScanState snapshot(ScanState previous, SortedMap<String, RegionEntry> regions) {
        return new ScanState(previous.formatVersion(), previous.worldId(), new TreeMap<>(regions));
    }

    private static SortedMap<String, Path> listRegionFiles(Path regionFolder) throws IOException {
        SortedMap<String, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (RegionFile.coords(name) != null && Files.isRegularFile(path)) {
                    files.put(name, path);
                }
            }
        }
        return files;
    }

    /**
     * Scans one region.
     *
     * @return the region's new entry, {@code previous} itself when nothing in it changed, or null
     *     when a cancel cut the region off (then nothing of it is used)
     */
    private static @Nullable RegionEntry scanRegion(
            RegionFile region,
            Path file,
            int regionX,
            int regionZ,
            @Nullable RegionEntry previous,
            Pacer pacer,
            CancellationToken cancel,
            BiConsumer<Path, Integer> afterChunkRead,
            ChunkBuffers buffers,
            ScanReport.Builder tally) {
        int[] timestamps = previous != null ? previous.timestamps().clone() : neverRead();
        BitSet replaced = new BitSet(CHUNKS);
        Map<Integer, ChunkInfo> read = new TreeMap<>();

        for (int index = 0; index < CHUNKS; index++) {
            if (cancel.isCancelled()) {
                return null;
            }
            if (!region.present(index)) {
                if (timestamps[index] != NEVER_READ) {
                    replaced.set(index);
                    timestamps[index] = NEVER_READ;
                }
                continue;
            }
            if (region.timestamp(index) == timestamps[index]) {
                tally.unchanged++;
                continue;
            }
            if (!pacer.await(cancel)) {
                return null;
            }
            ChunkInfo info = readChunk(region, file, index, buffers, tally);
            afterChunkRead.accept(file, index);
            if (info == null) {
                continue;
            }
            if (info.kind() == Kind.MODERN
                    && (info.chunkX() != regionX * 32 + (index & 31) || info.chunkZ() != regionZ * 32 + (index >> 5))) {
                tally.wrongPosition++;
                continue;
            }
            read.put(index, info);
        }

        // The header and the external files are read again only now, after all chunks of the region.
        RegionHeader first = region.header();
        RegionHeader second = null;
        if (!read.isEmpty()) {
            try {
                second = region.readHeaderAgain();
            } catch (IOException e) {
                Log.debug("cannot read the header of " + file + " again: " + e);
            }
        }
        Map<Integer, ChunkInfo> taken = new TreeMap<>();
        for (Map.Entry<Integer, ChunkInfo> chunk : read.entrySet()) {
            int index = chunk.getKey();
            if (changedDuringRead(region, index, first, second)) {
                tally.changedDuringRead++;
                continue;
            }
            ChunkInfo info = chunk.getValue();
            timestamps[index] = first.timestamps()[index];
            replaced.set(index);
            tally.addDataVersion(info.dataVersion());
            if (info.kind() == Kind.MODERN) {
                tally.read++;
                tally.addUnknownIds(info.unknownStructureIds(), info.unknownBiomeIds());
                taken.put(index, info);
            } else {
                tally.pre113++;
            }
        }

        if (previous != null && replaced.isEmpty()) {
            return previous;
        }
        return new RegionEntry(timestamps, structures(previous, replaced, taken), biomes(previous, replaced, taken));
    }

    /** The chunk's data, or null when it cannot be read (already counted as failed or unsupported). */
    private static @Nullable ChunkInfo readChunk(
            RegionFile region, Path file, int index, ChunkBuffers buffers, ScanReport.Builder tally) {
        try {
            // The read may grow the buffer, so the array is taken after it.
            int length = region.readChunk(index, buffers);
            return ChunkReader.read(buffers.data(), length);
        } catch (UnsupportedCompressionException e) {
            tally.unsupportedCompression++;
            Log.debug(file.getFileName() + " chunk " + index + ": " + e.getMessage());
            return null;
        } catch (ChunkReadException e) {
            tally.failed++;
            Log.debug(file.getFileName() + " chunk " + index + ": " + e.getMessage());
            return null;
        } catch (IOException e) {
            tally.failed++;
            Log.debug(file.getFileName() + " chunk " + index + ": " + e);
            return null;
        }
    }

    private static boolean changedDuringRead(
            RegionFile region, int index, RegionHeader first, @Nullable RegionHeader second) {
        return second == null
                || first.offsets()[index] != second.offsets()[index]
                || first.timestamps()[index] != second.timestamps()[index]
                || region.externalFileChanged(index);
    }

    /** The structures of the chunks not replaced, plus those of the taken chunks, ordered by chunk index. */
    private static List<StructureEntry> structures(
            @Nullable RegionEntry previous, BitSet replaced, Map<Integer, ChunkInfo> taken) {
        List<StructureEntry> structures = new ArrayList<>();
        if (previous != null) {
            for (StructureEntry structure : previous.structures()) {
                if (!replaced.get(structure.chunkIndex())) {
                    structures.add(structure);
                }
            }
        }
        for (Map.Entry<Integer, ChunkInfo> chunk : taken.entrySet()) {
            for (FoundStructure found : chunk.getValue().structures()) {
                structures.add(new StructureEntry(
                        chunk.getKey(),
                        found.structureId(),
                        found.box().clone(),
                        found.endShipBox() == null ? null : found.endShipBox().clone()));
            }
        }
        // A stable sort: the structures of one chunk keep the order ChunkReader gave them.
        structures.sort(Comparator.comparingInt(StructureEntry::chunkIndex));
        return structures;
    }

    /** The biome bits of the chunks not replaced, plus those of the taken chunks. */
    private static SortedMap<String, long[]> biomes(
            @Nullable RegionEntry previous, BitSet replaced, Map<Integer, ChunkInfo> taken) {
        SortedMap<String, BitSet> sets = new TreeMap<>();
        if (previous != null) {
            previous.biomes().forEach((biome, bits) -> {
                BitSet set = BitSet.valueOf(bits);
                set.andNot(replaced);
                if (!set.isEmpty()) {
                    sets.put(biome, set);
                }
            });
        }
        for (Map.Entry<Integer, ChunkInfo> chunk : taken.entrySet()) {
            for (String biome : chunk.getValue().biomes()) {
                sets.computeIfAbsent(biome, key -> new BitSet(CHUNKS)).set(chunk.getKey());
            }
        }
        SortedMap<String, long[]> biomes = new TreeMap<>();
        sets.forEach((biome, set) -> biomes.put(biome, Arrays.copyOf(set.toLongArray(), BIOME_WORDS)));
        return biomes;
    }

    private static int[] neverRead() {
        int[] timestamps = new int[CHUNKS];
        Arrays.fill(timestamps, NEVER_READ);
        return timestamps;
    }

    /**
     * Keeps the rate of chunk reads at the cap, over all reader threads: each read is due one
     * interval after the one before. A sleep that overshoots (on Windows a sleep lasts a millisecond
     * or more) is made up by the reads after it, but at most {@link #MAX_CREDIT_NANOS} of it, so
     * time the scan spent busy elsewhere buys only a short burst. A cap of 0 is no cap.
     */
    private static final class Pacer {
        private static final long MAX_CREDIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
        private final long intervalNanos;
        private long next = System.nanoTime();

        Pacer(int perSecond) {
            this.intervalNanos = perSecond == 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / perSecond;
        }

        /** Waits until the next read may start; false when the scan was cancelled or interrupted meanwhile. */
        boolean await(CancellationToken cancel) {
            if (intervalNanos == 0) {
                return !cancel.isCancelled() && !Thread.currentThread().isInterrupted();
            }
            long due = reserve();
            long now = System.nanoTime();
            while (due - now > 0) {
                if (cancel.isCancelled()) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(due - now, SLEEP_SLICE_NANOS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                now = System.nanoTime();
            }
            return !cancel.isCancelled();
        }

        /** The time the next read is due; taking it books that slot. */
        private synchronized long reserve() {
            next = Math.max(next, System.nanoTime() - MAX_CREDIT_NANOS);
            long due = next;
            next += intervalNanos;
            return due;
        }
    }
}
