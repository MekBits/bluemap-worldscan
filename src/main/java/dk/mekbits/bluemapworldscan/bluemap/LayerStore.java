package dk.mekbits.bluemapworldscan.bluemap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.bluecolored.bluemap.api.AssetStorage;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.util.Loaded;

/**
 * Keeps one map's finished layers in the map's asset storage: the layers as gzip JSON, then the
 * manifest. The storage is public, so these are the only things the addon puts there next to the
 * icons, and the manifest is written last so a reader never sees it ahead of its layers.
 */
public final class LayerStore {

    static final String LAYERS_ASSET = "bluemap-worldscan-layers.json.gz";
    static final String MANIFEST_ASSET = "bluemap-worldscan-manifest.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LayerStore() {
    }

    /** The JSON of the layers: the same layers always give the same bytes. */
    public static byte[] toJson(LayersFile layers) {
        return GSON.toJson(layers).getBytes(StandardCharsets.UTF_8);
    }

    /** Stores the layers, then the manifest; when the layers cannot be stored, the manifest is left as it was. */
    public static void write(AssetStorage storage, LayersFile layers, Manifest manifest) throws IOException {
        try (OutputStream out = new GZIPOutputStream(storage.writeAsset(LAYERS_ASSET))) {
            out.write(toJson(layers));
        }
        try (OutputStream out = storage.writeAsset(MANIFEST_ASSET)) {
            out.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Reads the manifest.
     *
     * @return {@code Missing} without one; {@code Failed} when it cannot be read or parsed or is of a format this version does not know
     */
    public static Loaded<Manifest> readManifest(AssetStorage storage) {
        return read(storage, MANIFEST_ASSET, false, Manifest.class, Manifest.FORMAT_VERSION, Manifest::formatVersion);
    }

    /** Reads the layers; the results are those of {@link #readManifest}. */
    public static Loaded<LayersFile> readLayers(AssetStorage storage) {
        return read(storage, LAYERS_ASSET, true, LayersFile.class, LayersFile.FORMAT_VERSION, LayersFile::formatVersion);
    }

    /**
     * Whether the storage holds a manifest or layers of a format newer than this addon writes. A
     * scanner must not replace those: the instances that run the newer addon would lose their layers.
     */
    public static boolean holdsNewerFormat(AssetStorage storage) {
        return isNewer(storage, MANIFEST_ASSET, false, Manifest.FORMAT_VERSION)
                || isNewer(storage, LAYERS_ASSET, true, LayersFile.FORMAT_VERSION);
    }

    private static boolean isNewer(AssetStorage storage, String asset, boolean gzip, int knownVersion) {
        return parse(storage, asset, gzip, FormatProbe.class) instanceof Loaded.Ok<FormatProbe>(FormatProbe probe)
                && probe.formatVersion() > knownVersion;
    }

    private static <T> Loaded<T> read(
            AssetStorage storage, String asset, boolean gzip, Class<T> type, int knownVersion,
            ToIntFunction<T> versionOf) {
        Loaded<T> parsed = parse(storage, asset, gzip, type);
        if (parsed instanceof Loaded.Ok<T>(T value) && versionOf.applyAsInt(value) != knownVersion) {
            return new Loaded.Failed<>(asset + " has format version " + versionOf.applyAsInt(value)
                    + ", this addon reads version " + knownVersion + ": update bluemap-worldscan on this instance");
        }
        return parsed;
    }

    private static <T> Loaded<T> parse(AssetStorage storage, String asset, boolean gzip, Class<T> type) {
        try {
            Optional<InputStream> stored = storage.readAsset(asset);
            if (stored.isEmpty()) {
                return new Loaded.Missing<>();
            }
            try (InputStream in = gzip ? new GZIPInputStream(stored.get()) : stored.get();
                    InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                T value = GSON.fromJson(reader, type);
                return value == null ? new Loaded.Failed<>(asset + " is empty") : new Loaded.Ok<>(value);
            }
        } catch (IOException | RuntimeException e) {
            return new Loaded.Failed<>(asset + " cannot be read: " + e);
        }
    }

    /** Only the version of a stored file, so a newer format with other fields can still be told from a damaged file. */
    private record FormatProbe(int formatVersion) {
    }
}
