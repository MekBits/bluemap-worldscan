package dk.mekbits.bluemapworldscan.addon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import de.bluecolored.bluemap.api.BlueMapWorld;
import dk.mekbits.bluemapworldscan.biome.BiomeNames;
import dk.mekbits.bluemapworldscan.bluemap.LayerBuilder;
import dk.mekbits.bluemapworldscan.bluemap.LayerStore;
import dk.mekbits.bluemapworldscan.bluemap.Manifest;
import dk.mekbits.bluemapworldscan.bluemap.MapLayers;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.config.AddonFolder;
import dk.mekbits.bluemapworldscan.icon.ClientAssetIcons;
import dk.mekbits.bluemapworldscan.icon.IconAssets;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.ServedSize;
import dk.mekbits.bluemapworldscan.scan.CancellationToken;
import dk.mekbits.bluemapworldscan.scan.ScanLimits;
import dk.mekbits.bluemapworldscan.scan.ScanOutcome;
import dk.mekbits.bluemapworldscan.scan.WorldScanner;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StateStore;
import dk.mekbits.bluemapworldscan.util.Loaded;
import dk.mekbits.bluemapworldscan.util.Log;
import dk.mekbits.bluemapworldscan.util.Sha1;

/**
 * What the scanning instance does for each world when BlueMap enables: scan it from the stored
 * state, and after a complete pass store and publish the finished layers of each of its maps.
 * Layers are stored only after a complete pass, so every stored generation is complete; the
 * checkpoints during a pass go to the local state file only.
 */
final class ScanPass {

    private final Path addonFolder;
    private final AddonConfig config;
    private final String scannerId;
    private final String addonVersion;
    private final ClientAssetIcons icons;
    private final CancellationToken cancel;
    private final BiomeBuilder biomeBuilder;

    /** Builds the biome sets of a world; tests replace it. */
    @FunctionalInterface
    interface BiomeBuilder {
        List<LayerSet> build(ScanState state, AddonConfig config, BiomeNames names);
    }

    ScanPass(Path addonFolder, AddonConfig config, String scannerId, String addonVersion, ClientAssetIcons icons,
            CancellationToken cancel) {
        this(addonFolder, config, scannerId, addonVersion, icons, cancel, LayerBuilder::buildBiomes);
    }

    ScanPass(Path addonFolder, AddonConfig config, String scannerId, String addonVersion, ClientAssetIcons icons,
            CancellationToken cancel, BiomeBuilder biomeBuilder) {
        this.biomeBuilder = biomeBuilder;
        this.addonFolder = addonFolder;
        this.config = config;
        this.scannerId = scannerId;
        this.addonVersion = addonVersion;
        this.icons = icons;
        this.cancel = cancel;
    }

    /** @return false when the scan was cancelled, so the caller stops with the remaining worlds */
    boolean run(CapturedWorld world) {
        Path dimension;
        try {
            dimension = saveFolder(world.world());
        } catch (UnsupportedOperationException e) {
            Log.info("world " + world.id() + " is not stored in region files; skipped");
            return true;
        }
        warnAboutOtherScanners(world);

        Path stateFile = AddonFolder.stateFile(addonFolder, world.id());
        ScanState previous = loadState(stateFile, world.id());
        Log.info("scanning world " + world.id() + " (" + dimension + ")");
        ScanOutcome outcome = WorldScanner.scan(
                dimension, previous, new ScanLimits(config.maxChunksPerSecond()), cancel,
                checkpoint -> saveState(checkpoint, stateFile));
        if (outcome.skipped()) {
            Log.warn("world " + world.id() + " has no region folder in " + dimension + "; nothing was read or stored");
            return true;
        }
        if (!outcome.state().regions().equals(previous.regions())) {
            saveState(outcome.state(), stateFile);
        }
        if (outcome.cancelled()) {
            return false;
        }
        return publishLayers(world, outcome.state());
    }

    /** Deprecated in the API, but still how an addon finds the dimension folder of a world. */
    @SuppressWarnings("deprecation")
    private static Path saveFolder(BlueMapWorld world) {
        return world.getSaveFolder();
    }

    private void warnAboutOtherScanners(CapturedWorld world) {
        for (MapLayers map : world.maps()) {
            if (LayerStore.readManifest(map.storage()) instanceof Loaded.Ok<Manifest>(Manifest stored)
                    && !stored.scannerId().equals(scannerId)) {
                Log.warn("the layers of map " + map.map().getId() + " were stored by another scanner (" + stored.scannerId()
                        + "); only one instance per map storage should have \"scan\" on");
            }
        }
    }

    private static ScanState loadState(Path file, String worldId) {
        Loaded<ScanState> loaded = StateStore.load(file, worldId);
        if (loaded instanceof Loaded.Ok<ScanState>(ScanState state)) {
            return state;
        }
        if (loaded instanceof Loaded.Failed<ScanState>(String reason)) {
            Log.warn("cannot use the stored scan state of world " + worldId + " (" + reason + "); scanning from zero");
        }
        return new ScanState(ScanState.FORMAT_VERSION, worldId, new TreeMap<>());
    }

    /** A state that cannot be written costs only the progress: the scan goes on. */
    private static void saveState(ScanState state, Path file) {
        try {
            StateStore.save(state, file);
        } catch (IOException e) {
            Log.warn("cannot save the scan state to " + file + ": " + e);
        }
    }

    /** @return false when the scan was cancelled before every map was done */
    private boolean publishLayers(CapturedWorld world, ScanState state) {
        Map<String, byte[]> pngByLayer = icons.pngByLayer();
        // The biome sets hold no icon URLs, so they are the same on every map of the world.
        List<LayerSet> biomeSets = buildBiomes(world, state);
        for (MapLayers map : world.maps()) {
            // A reload or stop during the build must not write layers after the addon is disabled.
            if (cancel.isCancelled()) {
                return false;
            }
            try {
                publishMap(map, state, pngByLayer, biomeSets);
            } catch (IOException | RuntimeException e) {
                Log.warn("cannot store the layers of map " + map.map().getId(), e);
            }
        }
        return !cancel.isCancelled();
    }

    private List<LayerSet> buildBiomes(CapturedWorld world, ScanState state) {
        if (!config.biomes().enabled()) {
            return List.of();
        }
        long start = System.nanoTime();
        try {
            List<LayerSet> sets = biomeBuilder.build(state, config, BiomeNames.fromClientJar(icons.clientJar().orElse(null)));
            Log.info("built " + sets.size() + " biome layers of world " + world.id() + " in "
                    + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) + " s");
            return sets;
        } catch (RuntimeException e) {
            // The structure layers and the other worlds do not depend on the biomes.
            Log.warn("cannot build the biome layers of world " + world.id() + "; its structure layers are stored without them", e);
            return List.of();
        }
    }

    private void publishMap(
            MapLayers map, ScanState state, Map<String, byte[]> pngByLayer, List<LayerSet> biomeSets) throws IOException {
        String mapId = map.map().getId();
        if (LayerStore.holdsNewerFormat(map.storage())) {
            Log.warn("the layers of map " + mapId + " were stored by a newer bluemap-worldscan; update this instance. "
                    + "The stored layers are kept and no new ones are stored for this map");
            return;
        }
        // The icon URLs name the map, so each map gets its own layers file.
        Map<String, String> iconUrls = IconAssets.writeAll(map.storage(), pngByLayer);
        LayersFile layers = new LayersFile(LayersFile.FORMAT_VERSION,
                LayerBuilder.combine(LayerBuilder.buildStructures(state, config, iconUrls), biomeSets));
        byte[] json = LayerStore.toJson(layers);
        long servedBytes = estimatedServedBytes(layers);
        if (oversized(servedBytes)) {
            Log.warn(oversizedLayersMessage(mapId, servedBytes));
        }

        String sha1 = Sha1.hex(json);
        long previousGeneration = -1;
        if (LayerStore.readManifest(map.storage()) instanceof Loaded.Ok<Manifest>(Manifest stored)) {
            // Identical layers stored by another scanner are stored again as this instance's own, so the
            // manifest names the scanner that is running now and the warning stops once the roles have moved.
            if (stored.layersSha1().equals(sha1) && stored.scannerId().equals(scannerId)) {
                Log.info("the layers of map " + mapId + " are unchanged");
                return;
            }
            previousGeneration = stored.generation();
        }
        long generation = Math.max(System.currentTimeMillis(), previousGeneration + 1);
        LayerStore.write(map.storage(), json, new Manifest(Manifest.FORMAT_VERSION, generation, scannerId, addonVersion, sha1));
        Log.info("stored generation " + generation + " of the layers of map " + mapId + " (" + markerCount(layers) + " markers)");
        map.show(layers, generation);
    }

    static long estimatedServedBytes(LayersFile layers) {
        return ServedSize.estimate(layers);
    }

    static boolean oversized(long servedBytes) {
        return servedBytes > ServedSize.WARNING_BYTES;
    }

    static String oversizedLayersMessage(String mapId, long servedBytes) {
        return "the layers of map " + mapId + " are about " + String.format(Locale.ROOT, "%.1f", servedBytes / 1e6)
                + " MB of markers, which every browser loads (the web app should load less than 10 MB); "
                + "make them smaller with structures.disabledLayers, biomes.cellSizeChunks (a larger cell) or biomes.disabled";
    }

    private static int markerCount(LayersFile layers) {
        return layers.sets().stream().mapToInt(set -> set.pois().size() + set.areas().size()).sum();
    }
}
