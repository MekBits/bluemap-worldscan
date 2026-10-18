package dk.mekbits.bluemapworldscan.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dk.mekbits.bluemapworldscan.util.Log;

/**
 * The addon's {@code config.json}.
 *
 * <p>Only {@code scan} and {@code refreshMinutes} matter on an instance that does not scan; the
 * other settings decide what the scanning instance stores, and every instance shows that.
 *
 * @param scan whether this instance scans the world
 * @param maxChunksPerSecond the most chunks a scan reads per second
 * @param refreshMinutes how often an instance that does not scan looks for new layers; 0 turns it off
 * @param structures the structure layer settings
 * @param biomes the biome layer settings
 */
public record AddonConfig(
        boolean scan, int maxChunksPerSecond, int refreshMinutes, Structures structures, Biomes biomes) {

    /**
     * @param disabledLayers ids of the structure layers that are not built. The default holds
     *     mineshaft (more than half of all structures of a large world) and buried_treasure: the
     *     markers of a layer, hidden ones too, are part of the payload every browser loads
     */
    public record Structures(List<String> disabledLayers) {
    }

    /**
     * @param enabled whether biome layers are built
     * @param cellSizeChunks the resolution of the biome areas, in chunks per cell side
     * @param disabled ids of the biomes that are not built
     */
    public record Biomes(boolean enabled, int cellSizeChunks, List<String> disabled) {
    }

    static final String FILE_NAME = "config.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public AddonConfig {
        if (maxChunksPerSecond < 1) {
            throw new IllegalArgumentException("maxChunksPerSecond must be at least 1: " + maxChunksPerSecond);
        }
        if (refreshMinutes < 0) {
            throw new IllegalArgumentException("refreshMinutes must not be negative: " + refreshMinutes);
        }
        if (biomes.cellSizeChunks() < 1) {
            throw new IllegalArgumentException("biomes.cellSizeChunks must be at least 1: " + biomes.cellSizeChunks());
        }
        structures = new Structures(List.copyOf(structures.disabledLayers()));
        biomes = new Biomes(biomes.enabled(), biomes.cellSizeChunks(), List.copyOf(biomes.disabled()));
    }

    /** The settings of a folder without a config file. */
    public static AddonConfig defaults() {
        return new AddonConfig(true, 500, 5, new Structures(List.of("mineshaft", "buried_treasure")), new Biomes(true, 4, List.of()));
    }

    /**
     * Reads {@code config.json} in the addon folder; keys the file leaves out keep their default.
     * Without a file the defaults are written there. A file that cannot be read, parsed or that
     * holds an impossible value logs one line and gives the defaults with {@code scan} off, so a
     * typo never starts a scan nobody asked for.
     */
    public static AddonConfig loadOrCreate(Path folder) {
        Path file = folder.resolve(FILE_NAME);
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(in).getAsJsonObject());
        } catch (NoSuchFileException e) {
            return createDefaults(file);
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot use " + file + " (" + e.getMessage() + "); scanning is off until it is fixed");
            AddonConfig defaults = defaults();
            return new AddonConfig(false, defaults.maxChunksPerSecond(), defaults.refreshMinutes(),
                    defaults.structures(), defaults.biomes());
        }
    }

    private static AddonConfig createDefaults(Path file) {
        AddonConfig defaults = defaults();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(defaults, out);
            }
            Log.info("wrote the default configuration to " + file);
        } catch (IOException e) {
            Log.warn("cannot write the default configuration to " + file + ": " + e);
        }
        return defaults;
    }

    private static AddonConfig parse(JsonObject user) {
        JsonObject merged = GSON.toJsonTree(defaults()).getAsJsonObject();
        merge(merged, user);
        return GSON.fromJson(merged, AddonConfig.class);
    }

    /** Lays {@code overrides} over {@code base}, key by key down through nested objects. */
    private static void merge(JsonObject base, JsonObject overrides) {
        for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
            JsonElement current = base.get(entry.getKey());
            if (current != null && current.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                base.add(entry.getKey(), entry.getValue());
            }
        }
    }
}
