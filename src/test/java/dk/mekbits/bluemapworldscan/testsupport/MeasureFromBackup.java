package dk.mekbits.bluemapworldscan.testsupport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import dk.mekbits.bluemapworldscan.biome.BiomeNames;
import dk.mekbits.bluemapworldscan.bluemap.LayerBuilder;
import dk.mekbits.bluemapworldscan.bluemap.LayerPublisher;
import dk.mekbits.bluemapworldscan.bluemap.LayerStore;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.IconAssets;
import dk.mekbits.bluemapworldscan.layer.Layer;
import dk.mekbits.bluemapworldscan.layer.LayerCatalog;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.scan.CancellationToken;
import dk.mekbits.bluemapworldscan.scan.ScanLimits;
import dk.mekbits.bluemapworldscan.scan.ScanOutcome;
import dk.mekbits.bluemapworldscan.scan.WorldScanner;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StateStore;
import dk.mekbits.bluemapworldscan.util.Loaded;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Measures how big the {@code live/markers.json} of a real world is, without extracting the world:
 * the whole overworld of a large server is tens of gigabytes. It streams a world backup
 * ({@code .tar.gz}), writes one region file of the chosen dimension at a time to a temporary folder,
 * scans it, and keeps only what the scan found. From that it builds the layers of one map, puts them
 * on an in-memory map and prints the size of what BlueMap would serve: the API's own
 * {@code MarkerGson} applied to the map's marker sets, as BlueMap's {@code LiveMarkersDataSupplier} does.
 *
 * <p>Usage: {@code MeasureFromBackup <backup.tar.gz | -> <overworld|the_nether|the_end> <map id> [options]}.
 * Options: {@code --state-out <file>} keeps the scan state, {@code --state-in <file>} measures a kept
 * state instead of scanning the backup (give {@code -} as the backup), {@code --client-jar <file>}
 * names the biomes from a client jar, and {@code --cell-sizes 8,16} measures the biome layers at
 * more cell sizes (in chunks) from the same scan. Icon URLs have the form and length of real ones.
 * Run through the Gradle task {@code measureBackup}.
 */
public final class MeasureFromBackup {

    private static final String WORLD_ID = "measure";
    private static final long NO_RATE_LIMIT = 1_000_000_000L;
    private static final int PROGRESS_EVERY_REGIONS = 250;
    // Held in a field: the log manager only keeps a weak reference, and a collected logger forgets its level.
    private static final Logger ADDON_LOGGER = Logger.getLogger("dk.mekbits.bluemapworldscan");

    private MeasureFromBackup() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: MeasureFromBackup <backup.tar.gz | -> <overworld|the_nether|the_end> <map id> "
                    + "[--state-out F] [--state-in F] [--client-jar F] [--cell-sizes 8,16]");
        }
        Path backup = Path.of(args[0]);
        String dimension = args[1];
        String mapId = args[2];
        Path stateOut = null;
        Path stateIn = null;
        Path clientJar = null;
        List<Integer> cellSizes = List.of();
        for (int i = 3; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("option without a value: " + args[i]);
            }
            switch (args[i]) {
                case "--state-out" -> stateOut = Path.of(args[i + 1]);
                case "--state-in" -> stateIn = Path.of(args[i + 1]);
                case "--client-jar" -> clientJar = Path.of(args[i + 1]);
                case "--cell-sizes" -> cellSizes = Arrays.stream(args[i + 1].split(",")).map(String::strip).map(Integer::valueOf).toList();
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        // One INFO line per region would be thousands of lines.
        ADDON_LOGGER.setLevel(Level.WARNING);

        ScanState state;
        if (stateIn != null) {
            state = switch (StateStore.load(stateIn, WORLD_ID)) {
                case Loaded.Ok<ScanState> ok -> ok.value();
                default -> throw new IllegalArgumentException("cannot read the scan state " + stateIn);
            };
            System.out.println("scan state read from " + stateIn);
        } else {
            long start = System.nanoTime();
            state = scanBackup(backup, dimension);
            System.out.printf("scanned in %d s%n", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start));
        }
        if (stateOut != null) {
            StateStore.save(state, stateOut);
            System.out.println("scan state saved to " + stateOut);
        }

        BiomeNames names = BiomeNames.fromClientJar(clientJar);
        AddonConfig defaults = AddonConfig.defaults();
        measure(state, defaults, names, mapId, true);
        measure(state, withDisabled(defaults, "mineshaft"), names, mapId, false);
        measure(state, withDisabled(defaults), names, mapId, false);
        for (int cellSize : cellSizes) {
            measure(state, withCellSize(defaults, cellSize), names, mapId, false);
        }
    }

    private static AddonConfig withDisabled(AddonConfig base, String... layers) {
        return new AddonConfig(
                base.scan(), base.maxChunksPerSecond(), base.refreshMinutes(),
                new AddonConfig.Structures(List.of(layers)), base.biomes());
    }

    private static AddonConfig withCellSize(AddonConfig base, int cellSizeChunks) {
        return new AddonConfig(
                base.scan(), base.maxChunksPerSecond(), base.refreshMinutes(), base.structures(),
                new AddonConfig.Biomes(base.biomes().enabled(), cellSizeChunks, base.biomes().disabled()));
    }

    private static ScanState scanBackup(Path backup, String dimension) throws IOException, InterruptedException {
        Pattern regionMember = Pattern.compile("(?:^|/)dimensions/minecraft/" + Pattern.quote(dimension) + "/region/(r\\.-?\\d+\\.-?\\d+\\.mca)$");
        SortedMap<String, RegionEntry> regions = new TreeMap<>();
        Totals totals = new Totals();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService scanners = Executors.newFixedThreadPool(threads);
        Semaphore inFlight = new Semaphore(threads * 2);
        List<Future<?>> pending = new ArrayList<>();
        Path temp = Files.createTempDirectory("measure-backup");
        int regionNumber = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(backup), 1 << 20), 1 << 20), 1 << 20))) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                Matcher matcher = regionMember.matcher(entry.getName());
                if (entry.isDirectory() || !matcher.find()) {
                    continue;
                }
                String name = matcher.group(1);
                Path regionFolder = Files.createDirectories(temp.resolve(Integer.toString(regionNumber++)).resolve("region"));
                Files.copy(tar, regionFolder.resolve(name));
                inFlight.acquire();
                pending.add(scanners.submit(() -> {
                    try {
                        scanRegionFolder(regionFolder, name, regions, totals);
                    } finally {
                        inFlight.release();
                    }
                }));
                if (regionNumber % PROGRESS_EVERY_REGIONS == 0) {
                    System.out.printf("%d regions read from the archive%n", regionNumber);
                }
            }
        } finally {
            scanners.shutdown();
        }
        for (Future<?> scan : pending) {
            try {
                scan.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("a region scan failed", e.getCause());
            }
        }
        try (Stream<Path> leftovers = Files.walk(temp)) {
            leftovers.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        System.out.printf("%d region files of %s: %s%n", regionNumber, dimension, totals);
        return new ScanState(ScanState.FORMAT_VERSION, WORLD_ID, regions);
    }

    private static void scanRegionFolder(
            Path regionFolder, String name, SortedMap<String, RegionEntry> regions, Totals totals) {
        Path dimensionFolder = regionFolder.getParent();
        try {
            ScanState empty = new ScanState(ScanState.FORMAT_VERSION, WORLD_ID, new TreeMap<>());
            ScanOutcome outcome = WorldScanner.scan(
                    dimensionFolder, empty, new ScanLimits((int) NO_RATE_LIMIT), new CancellationToken(), checkpoint -> {
                    });
            synchronized (regions) {
                regions.putAll(outcome.state().regions());
                totals.add(outcome);
            }
        } finally {
            try (Stream<Path> files = Files.walk(dimensionFolder)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot delete " + dimensionFolder + " after " + name, e);
            }
        }
    }

    private static void measure(ScanState state, AddonConfig config, BiomeNames names, String mapId, boolean perSet)
            throws IOException {
        Map<String, String> iconUrls = new TreeMap<>();
        for (Layer layer : LayerCatalog.layers()) {
            byte[] stand = layer.id().getBytes(StandardCharsets.UTF_8);
            iconUrls.put(layer.id(), "maps/" + mapId + "/assets/" + IconAssets.assetName(layer.id(), stand));
        }
        long start = System.nanoTime();
        List<LayerSet> biomeSets = LayerBuilder.buildBiomes(state, config, names);
        long biomeSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        LayersFile layers = new LayersFile(LayersFile.FORMAT_VERSION,
                LayerBuilder.combine(LayerBuilder.buildStructures(state, config, iconUrls), biomeSets));
        InMemoryBlueMapMap map = new InMemoryBlueMapMap(mapId);
        new LayerPublisher().publish(map, layers);

        Map<String, MarkerSet> structures = new TreeMap<>();
        Map<String, MarkerSet> biomes = new TreeMap<>();
        map.getMarkerSets().forEach((id, set) -> (id.startsWith("worldscan-biome-") ? biomes : structures).put(id, set));
        long corners = layers.sets().stream().flatMap(set -> set.areas().stream())
                .mapToLong(area -> area.outline().size() + area.holes().stream().mapToLong(List::size).sum()).sum();

        System.out.printf("%n== structures.disabledLayers %s, biomes %s%s%n", config.structures().disabledLayers(),
                config.biomes().enabled() ? "on at " + config.biomes().cellSizeChunks() + " chunks per cell" : "off",
                config.equals(AddonConfig.defaults()) ? " (the default)" : "");
        System.out.printf("stored layers JSON: %,d bytes%n", LayerStore.toJson(layers).length);
        System.out.printf("live/markers.json:  %,d bytes%n", servedBytes(map.getMarkerSets()));
        System.out.printf("  structures: %,d bytes in %d sets; biomes: %,d bytes in %d sets (%,d areas, %,d corners, built in %d s)%n",
                servedBytes(structures), structures.size(), servedBytes(biomes), biomes.size(),
                layers.sets().stream().mapToLong(set -> set.areas().size()).sum(), corners, biomeSeconds);
        long markers = 0;
        for (Map.Entry<String, MarkerSet> set : new TreeMap<>(map.getMarkerSets()).entrySet()) {
            markers += set.getValue().getMarkers().size();
            if (perSet) {
                System.out.printf("  %-45s %,8d markers %,12d bytes%n",
                        set.getKey(), set.getValue().getMarkers().size(), servedBytes(Map.of(set.getKey(), set.getValue())));
            }
        }
        System.out.printf("  %d markers in %d sets%n", markers, map.getMarkerSets().size());
    }

    /** The body BlueMap serves as {@code live/markers.json} for these marker sets. */
    private static int servedBytes(Map<String, MarkerSet> markerSets) {
        return MarkerGson.INSTANCE.toJson(markerSets).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The counts of all scans together. */
    private static final class Totals {
        private long read;
        private long failed;
        private long preThirteen;
        private final SortedSet<String> unknownStructureIds = new TreeSet<>();

        void add(ScanOutcome outcome) {
            read += outcome.report().read();
            failed += outcome.report().failed() + outcome.report().wrongPosition() + outcome.report().changedDuringRead()
                    + outcome.report().unsupportedCompression();
            preThirteen += outcome.report().pre113();
            unknownStructureIds.addAll(outcome.report().unknownStructureIds());
        }

        @Override
        public String toString() {
            return "chunks read " + read + ", failed " + failed + ", pre-1.13 " + preThirteen
                    + ", unknown structure ids " + unknownStructureIds;
        }
    }
}
