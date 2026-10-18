package dk.mekbits.bluemapworldscan.addon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import dk.mekbits.bluemapworldscan.bluemap.LayerPublisher;
import dk.mekbits.bluemapworldscan.bluemap.MapLayers;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.config.AddonFolder;
import dk.mekbits.bluemapworldscan.icon.ClientAssetIcons;
import dk.mekbits.bluemapworldscan.icon.ClientJar;
import dk.mekbits.bluemapworldscan.scan.CancellationToken;
import dk.mekbits.bluemapworldscan.util.Log;
import org.jetbrains.annotations.Nullable;

/**
 * Connects the addon to BlueMap's enable and disable events.
 *
 * <p>BlueMap calls the enable consumers inside its own load, holding its lock, and the disable
 * consumers while the game server stops, so both only hand work to the addon's one worker thread
 * and return. That thread publishes the stored layers, scans, and refreshes, one task at a time.
 *
 * <p>What a disable means depends on the platform. A server mod or plugin stops the scan and waits
 * at most five seconds for it, as the server is stopping. The command line tool (its API has no
 * plugin) calls disable when a render run ends, and then saves the marker sets once: it waits for
 * the scan, so that save stores the new layers. Marker sets are never removed on disable,
 * because that save would then store empty layers.
 */
public final class AddonLifecycle {

    private static final long PLUGIN_STOP_WAIT_SECONDS = 5;

    private final Path folder;
    private final String addonVersion;
    private final TimeUnit refreshUnit;
    private final ScheduledThreadPoolExecutor worker = newWorker();
    private final LayerPublisher publisher = new LayerPublisher();
    private final ClientAssetIcons icons;

    private volatile CancellationToken scanCancel = new CancellationToken();
    private volatile Future<?> lastEnable = CompletableFuture.completedFuture(null);
    private volatile boolean commandLine;
    private @Nullable ScheduledFuture<?> refresh;

    public AddonLifecycle(Path folder, String addonVersion) {
        this(folder, addonVersion, TimeUnit.MINUTES, new ClientAssetIcons(new ClientJar(AddonFolder.clientJarFolder(folder))::ensure));
    }

    /**
     * @param refreshUnit the unit of {@code refreshMinutes}; tests use a shorter one
     * @param icons where the layer icons come from; tests use a source that needs no network
     */
    AddonLifecycle(Path folder, String addonVersion, TimeUnit refreshUnit, ClientAssetIcons icons) {
        this.folder = folder;
        this.addonVersion = addonVersion;
        this.refreshUnit = refreshUnit;
        this.icons = icons;
    }

    /** Listens for BlueMap's enable and disable; the enable listener runs at once when BlueMap is already enabled. */
    public void register() {
        BlueMapAPI.onEnable(this::onEnable);
        BlueMapAPI.onDisable(this::onDisable);
    }

    /** Stops a running scan; the JVM's shutdown hook calls it. */
    public void cancelScan() {
        if (!lastEnable.isDone()) {
            // The JVM's own shutdown closes the log handlers of java.util.logging, so what is logged
            // from here on may be lost; this line goes to stderr directly.
            System.err.println("[bluemap-worldscan] the JVM is shutting down; cancelling the running scan");
        }
        scanCancel.cancel();
    }

    void onEnable(BlueMapAPI api) {
        commandLine = isCommandLine(api);
        List<CapturedWorld> worlds = capture(api);
        CancellationToken cancel = new CancellationToken();
        scanCancel = cancel;
        lastEnable = worker.submit(() -> enable(worlds, cancel));
    }

    void onDisable(BlueMapAPI api) {
        if (commandLine) {
            awaitEnableTask();
            return;
        }
        synchronized (this) {
            scanCancel.cancel();
            cancelRefresh();
        }
        try {
            lastEnable.get(PLUGIN_STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.warn("the scan is still stopping; BlueMap goes on, the last checkpoint of the scan state is kept");
        } catch (ExecutionException e) {
            Log.error("the scan failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The command line tool's API has no plugin. */
    private static boolean isCommandLine(BlueMapAPI api) {
        try {
            api.getPlugin();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    private List<CapturedWorld> capture(BlueMapAPI api) {
        List<CapturedWorld> worlds = new ArrayList<>();
        for (BlueMapWorld world : api.getWorlds()) {
            List<MapLayers> maps = world.getMaps().stream()
                    .map(map -> new MapLayers(map, map.getAssetStorage(), publisher))
                    .toList();
            worlds.add(new CapturedWorld(world.getId(), world, maps));
        }
        return worlds;
    }

    private void enable(List<CapturedWorld> worlds, CancellationToken cancel) {
        try {
            AddonConfig config = AddonConfig.loadOrCreate(folder);
            refreshAll(worlds);
            if (cancel.isCancelled()) {
                return;
            }
            if (config.scan()) {
                scan(worlds, config, cancel);
            } else {
                scheduleRefresh(config, worlds, cancel);
            }
        } catch (IOException | RuntimeException e) {
            Log.error("cannot show or update the layers", e);
        }
    }

    private void scan(List<CapturedWorld> worlds, AddonConfig config, CancellationToken cancel) throws IOException {
        if (AddonFolder.consumeReset(folder)) {
            Log.info("reset requested: the stored scan state is deleted and the scan starts from zero");
        }
        ScanPass pass = new ScanPass(folder, config, AddonFolder.scannerId(folder), addonVersion, icons, cancel);
        for (CapturedWorld world : worlds) {
            if (!pass.run(world)) {
                Log.info("the scan was cancelled; the regions it finished are kept and the next scan continues from them");
                return;
            }
        }
    }

    private static void refreshAll(List<CapturedWorld> worlds) {
        for (CapturedWorld world : worlds) {
            for (MapLayers map : world.maps()) {
                map.refreshIfNewer();
            }
        }
    }

    private synchronized void scheduleRefresh(AddonConfig config, List<CapturedWorld> worlds, CancellationToken cancel) {
        if (config.refreshMinutes() == 0 || cancel.isCancelled()) {
            return;
        }
        cancelRefresh();
        refresh = worker.scheduleWithFixedDelay(() -> {
            try {
                refreshAll(worlds);
            } catch (RuntimeException e) {
                Log.warn("cannot look for new layers", e);
            }
        }, config.refreshMinutes(), config.refreshMinutes(), refreshUnit);
    }

    private synchronized void cancelRefresh() {
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
    }

    /**
     * Waits for the enable task of the last enable, queued or running. The scan is stopped by the
     * JVM's shutdown hook when the process is stopped, which ends the wait.
     */
    private void awaitEnableTask() {
        Future<?> enable = lastEnable;
        if (enable.isDone()) {
            return;
        }
        Log.info("BlueMap is finishing; waiting for the scan to end so that its final save stores the new layers "
                + "(stopping the process cancels the scan)");
        try {
            enable.get();
            Log.info("the scan has ended");
        } catch (ExecutionException e) {
            Log.error("the scan failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledThreadPoolExecutor newWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "bluemap-worldscan");
            thread.setDaemon(true);
            // Linux ignores Java thread priorities; maxChunksPerSecond is what limits the scan.
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
