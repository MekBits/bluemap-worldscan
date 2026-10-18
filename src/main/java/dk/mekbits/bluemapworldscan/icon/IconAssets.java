package dk.mekbits.bluemapworldscan.icon;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;

import de.bluecolored.bluemap.api.AssetStorage;
import dk.mekbits.bluemapworldscan.util.Sha1;

/**
 * Stores layer icons in a map's asset storage. The asset name holds a hash of the PNG, so every
 * instance names the same picture the same, and a changed picture gets a new name that browsers
 * do not have cached.
 */
public final class IconAssets {

    private static final int HASH_DIGITS = 12;

    private IconAssets() {
    }

    /** The asset name of a layer's icon. */
    public static String assetName(String layerId, byte[] png) {
        return "bluemap-worldscan-icon-" + layerId + "-" + Sha1.hex(png).substring(0, HASH_DIGITS) + ".png";
    }

    /**
     * Writes the icons that are not in the storage yet.
     *
     * @param pngByLayer the PNG of each layer's icon by layer id
     * @return the URL of each icon by layer id
     */
    public static Map<String, String> writeAll(AssetStorage storage, Map<String, byte[]> pngByLayer) throws IOException {
        Map<String, String> urls = new TreeMap<>();
        for (Map.Entry<String, byte[]> icon : pngByLayer.entrySet()) {
            String name = assetName(icon.getKey(), icon.getValue());
            if (!storage.assetExists(name)) {
                try (OutputStream out = storage.writeAsset(name)) {
                    out.write(icon.getValue());
                }
            }
            urls.put(icon.getKey(), storage.getAssetUrl(name));
        }
        return urls;
    }
}
