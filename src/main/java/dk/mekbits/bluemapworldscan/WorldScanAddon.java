package dk.mekbits.bluemapworldscan;

import dk.mekbits.bluemapworldscan.addon.AddonLifecycle;
import dk.mekbits.bluemapworldscan.config.AddonFolder;

/**
 * Entrypoint that BlueMap loads from {@code bluemap.addon.json}. It runs once per JVM, also across
 * {@code /bluemap reload}: it only registers for BlueMap's enable and disable events, and for the
 * end of the JVM, where a running scan is stopped.
 */
public final class WorldScanAddon implements Runnable {

    @Override
    public void run() {
        AddonLifecycle lifecycle = new AddonLifecycle(AddonFolder.resolve(), version());
        lifecycle.register();
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::cancelScan, "bluemap-worldscan-shutdown"));
    }

    private static String version() {
        String version = WorldScanAddon.class.getPackage().getImplementationVersion();
        return version != null ? version : "unknown";
    }
}
