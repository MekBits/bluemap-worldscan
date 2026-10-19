package dk.mekbits.bluemapworldscan.bluemap;

import java.util.Objects;

import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.BlueMapMap;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.util.Loaded;
import dk.mekbits.bluemapworldscan.util.Log;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps one map's marker sets in step with the layers stored in its asset storage.
 *
 * <p>A stored generation that cannot be read leaves the published sets as they are and is logged
 * once, not at every refresh.
 */
public final class MapLayers {

    private final BlueMapMap map;
    private final AssetStorage storage;
    private final LayerPublisher publisher;
    private long publishedGeneration = -1;
    private @Nullable String lastProblem;

    public MapLayers(BlueMapMap map, AssetStorage storage, LayerPublisher publisher) {
        this.map = map;
        this.storage = storage;
        this.publisher = publisher;
    }

    public BlueMapMap map() {
        return map;
    }

    public AssetStorage storage() {
        return storage;
    }

    /**
     * Publishes the stored layers when their generation differs from the one published last.
     *
     * @return whether sets were published
     */
    public boolean refreshIfNewer() {
        Loaded<Manifest> manifest = LayerStore.readManifest(storage);
        if (manifest instanceof Loaded.Failed<Manifest>(String reason)) {
            reportOnce(reason, "map " + map.getId() + ": " + reason);
            return false;
        }
        if (!(manifest instanceof Loaded.Ok<Manifest>(Manifest stored)) || stored.generation() == publishedGeneration) {
            return false;
        }
        String generation = "generation " + stored.generation() + " of map " + map.getId();
        Loaded<LayersFile> layers = LayerStore.readLayers(storage);
        if (!(layers instanceof Loaded.Ok<LayersFile>(LayersFile file))) {
            String reason = layers instanceof Loaded.Failed<LayersFile>(String failure) ? failure : "the layers are missing";
            reportOnce(generation + reason, "cannot show " + generation + ", keeping the layers shown now: " + reason);
            return false;
        }
        try {
            publisher.publish(map, file);
        } catch (RuntimeException e) {
            // BlueMap reloaded, so this reference to the map is dead; the next onEnable brings a new one.
            reportOnce(generation + e, "cannot show " + generation + ": " + e);
            return false;
        }
        publishedGeneration = stored.generation();
        lastProblem = null;
        Log.info("showing " + generation + " (" + file.sets().size() + " layers)");
        return true;
    }

    private void reportOnce(String problem, String message) {
        if (!Objects.equals(problem, lastProblem)) {
            lastProblem = problem;
            Log.warn(message);
        }
    }
}
