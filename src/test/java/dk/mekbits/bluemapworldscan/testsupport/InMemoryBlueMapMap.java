package dk.mekbits.bluemapworldscan.testsupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;

/**
 * A {@link BlueMapMap} with an id, marker sets and an asset storage; the rest of the API is not
 * supported. {@link #reload()} makes it behave as a map object of BlueMap before a reload does.
 */
public final class InMemoryBlueMapMap implements BlueMapMap {

    private final String id;
    private final InMemoryAssetStorage storage;
    private final Map<String, MarkerSet> markerSets = new ConcurrentHashMap<>();
    private BlueMapWorld world;
    private volatile boolean reloaded;

    public InMemoryBlueMapMap(String id) {
        this(id, new InMemoryAssetStorage(id));
    }

    /** A map on a storage another map object has used, as a second BlueMap instance on the same storage has. */
    public InMemoryBlueMapMap(String id, InMemoryAssetStorage storage) {
        this.id = id;
        this.storage = storage;
    }

    /** From now on the marker sets are gone, as BlueMap's map objects are after a reload. */
    public void reload() {
        reloaded = true;
    }

    public InMemoryAssetStorage storage() {
        return storage;
    }

    void setWorld(BlueMapWorld world) {
        this.world = world;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return id;
    }

    @Override
    public BlueMapWorld getWorld() {
        return world;
    }

    @Override
    public AssetStorage getAssetStorage() {
        return storage;
    }

    @Override
    public Map<String, MarkerSet> getMarkerSets() {
        if (reloaded) {
            throw new IllegalStateException("BlueMap got reloaded and this map is no longer valid");
        }
        return markerSets;
    }

    @Override
    public Vector2i getTileSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Vector2i getTileOffset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setFrozen(boolean frozen) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFrozen() {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("removal")
    public void setTileFilter(Predicate<Vector2i> filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("removal")
    public Predicate<Vector2i> getTileFilter() {
        throw new UnsupportedOperationException();
    }
}
