package dk.mekbits.bluemapworldscan.biome;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dk.mekbits.bluemapworldscan.layer.IdLabels;
import dk.mekbits.bluemapworldscan.util.Log;
import org.jetbrains.annotations.Nullable;

/**
 * The English names of biomes, from the language file of the Minecraft client jar
 * ({@code assets/minecraft/lang/en_us.json}, key {@code biome.<namespace>.<path>}). A biome the
 * file does not name, and every biome when there is no client jar, is named from its id.
 */
public final class BiomeNames {

    static final String LANG_FILE = "assets/minecraft/lang/en_us.json";
    private static final String KEY_PREFIX = "biome.";
    /** The English file of Minecraft 26.3 is 0.5 MB. */
    private static final long MAX_LANG_BYTES = 16L << 20;

    private final Map<String, String> byKey;

    private BiomeNames(Map<String, String> byKey) {
        this.byKey = byKey;
    }

    /** Names made from the ids only. */
    public static BiomeNames fromIds() {
        return new BiomeNames(Map.of());
    }

    /**
     * Reads the names of the biomes from the client jar. A missing or unreadable jar or language
     * file is logged and gives names made from the ids.
     *
     * @param clientJar the client jar, or null when there is none
     */
    public static BiomeNames fromClientJar(@Nullable Path clientJar) {
        if (clientJar == null) {
            return fromIds();
        }
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            ZipEntry entry = zip.getEntry(LANG_FILE);
            if (entry == null) {
                Log.warn("the client jar has no " + LANG_FILE + "; the biome layers are named from the biome ids");
                return fromIds();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new BiomeNames(biomeKeys(in));
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot read the biome names from " + clientJar + " (" + e + "); the biome layers are named from the biome ids");
            return fromIds();
        }
    }

    private static Map<String, String> biomeKeys(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes((int) MAX_LANG_BYTES + 1);
        if (bytes.length > MAX_LANG_BYTES) {
            throw new IOException("the language file is larger than " + MAX_LANG_BYTES + " bytes");
        }
        Map<String, String> names = new HashMap<>();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            JsonObject lang = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : lang.entrySet()) {
                if (entry.getKey().startsWith(KEY_PREFIX) && entry.getValue().isJsonPrimitive()) {
                    names.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return names;
    }

    /**
     * The name of a biome: the language file's, else one made from the id ({@link IdLabels#fromId}).
     *
     * @param biomeId the namespaced id
     */
    public String label(String biomeId) {
        String namespace = namespaceOf(biomeId);
        String path = biomeId.substring(biomeId.indexOf(':') + 1);
        String named = byKey.get(KEY_PREFIX + namespace + "." + path.replace('/', '.'));
        if (named != null && !named.isBlank()) {
            return named;
        }
        return IdLabels.fromId(biomeId);
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
