package dk.mekbits.bluemapworldscan.bluemap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
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
    private static final int GZIP_BUFFER_BYTES = 64 << 10;

    private LayerStore() {
    }

    /** The JSON of the layers: the same layers always give the same bytes. */
    public static byte[] toJson(LayersFile layers) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 << 10);
        // Written as UTF-8 straight away, without the whole document as a String first.
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            GSON.toJson(layers, writer);
        } catch (IOException e) {
            throw new IllegalStateException("writing to memory cannot fail", e);
        }
        return bytes.toByteArray();
    }

    /** Stores the layers, then the manifest; when the layers cannot be stored, the manifest is left as it was. */
    public static void write(AssetStorage storage, LayersFile layers, Manifest manifest) throws IOException {
        write(storage, toJson(layers), manifest);
    }

    /** As {@link #write(AssetStorage, LayersFile, Manifest)}, with the layers already as {@link #toJson} bytes. */
    public static void write(AssetStorage storage, byte[] layersJson, Manifest manifest) throws IOException {
        try (OutputStream out = new GZIPOutputStream(storage.writeAsset(LAYERS_ASSET), GZIP_BUFFER_BYTES)) {
            out.write(layersJson);
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
        return read(storage, MANIFEST_ASSET, false, Manifest.class, Manifest::formatVersion,
                version -> version == Manifest.FORMAT_VERSION, "version " + Manifest.FORMAT_VERSION);
    }

    /**
     * Reads the layers; the results are those of {@link #readManifest}. Layers of the format of
     * release 1.0.0 are read too: they hold structure sets only.
     */
    public static Loaded<LayersFile> readLayers(AssetStorage storage) {
        return read(storage, LAYERS_ASSET, true, LayersFile.class, LayersFile::formatVersion, LayersFile::isReadable,
                "versions " + LayersFile.OLDEST_READABLE_FORMAT_VERSION + " to " + LayersFile.FORMAT_VERSION);
    }

    /**
     * Whether the storage holds a manifest or layers of a format newer than this addon reads. A
     * scanner must not replace those: the instances that run the newer addon would lose their layers.
     */
    public static boolean holdsNewerFormat(AssetStorage storage) {
        return isNewer(storage, MANIFEST_ASSET, false, Manifest.FORMAT_VERSION)
                || isNewer(storage, LAYERS_ASSET, true, LayersFile.FORMAT_VERSION);
    }

    private static boolean isNewer(AssetStorage storage, String asset, boolean gzip, int knownVersion) {
        return formatVersion(storage, asset, gzip) > knownVersion;
    }

    /**
     * The {@code formatVersion} of a stored file, read with a stream that stops at it: it is the
     * first field this addon writes, so the layers are not parsed to their end for one number. A
     * file without it, or one that cannot be read, gives 0, so a newer format with other fields can
     * still be told from a damaged file.
     */
    private static int formatVersion(AssetStorage storage, String asset, boolean gzip) {
        try {
            Optional<InputStream> stored = storage.readAsset(asset);
            if (stored.isEmpty()) {
                return 0;
            }
            try (InputStream in = gzip ? new GZIPInputStream(stored.get()) : stored.get();
                    JsonReader json = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                json.beginObject();
                while (json.hasNext()) {
                    if (json.nextName().equals("formatVersion")) {
                        return json.nextInt();
                    }
                    json.skipValue();
                }
                return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static <T> Loaded<T> read(
            AssetStorage storage, String asset, boolean gzip, Class<T> type, ToIntFunction<T> versionOf,
            IntPredicate readable, String readableVersions) {
        Loaded<T> parsed = parse(storage, asset, gzip, type);
        if (parsed instanceof Loaded.Ok<T>(T value) && !readable.test(versionOf.applyAsInt(value))) {
            return new Loaded.Failed<>(asset + " has format version " + versionOf.applyAsInt(value)
                    + ", this addon reads " + readableVersions + ": update bluemap-worldscan on this instance");
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
}
