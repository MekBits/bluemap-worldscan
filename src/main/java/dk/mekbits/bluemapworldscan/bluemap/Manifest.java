package dk.mekbits.bluemapworldscan.bluemap;

import java.util.Objects;

/**
 * The small public file that says which layers a map's storage holds. Instances that do not scan
 * read it to find out whether a newer generation is there.
 *
 * @param formatVersion {@link #FORMAT_VERSION} for manifests this version of the addon writes
 * @param generation grows with every set of layers the scanner stores (epoch milliseconds)
 * @param scannerId the scanner that stored them ({@code AddonFolder.scannerId})
 * @param addonVersion the addon version of the scanner
 * @param layersSha1 SHA-1 of the stored layers' JSON, so a scan that finds nothing new stores nothing
 */
public record Manifest(int formatVersion, long generation, String scannerId, String addonVersion, String layersSha1) {

    public static final int FORMAT_VERSION = 1;

    public Manifest {
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(addonVersion, "addonVersion");
        Objects.requireNonNull(layersSha1, "layersSha1");
    }
}
