package dk.mekbits.bluemapworldscan.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.bluecolored.bluemap.api.AssetStorage;

/**
 * An {@link AssetStorage} that keeps its assets in memory. Its URLs have the form of BlueMap's
 * ({@code maps/<map id>/assets/<name>}), which names the map.
 */
public final class InMemoryAssetStorage implements AssetStorage {

    private final String mapId;
    private final Map<String, byte[]> assets = new ConcurrentHashMap<>();
    private final List<String> completedWrites = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failingWrites = Collections.synchronizedSet(new HashSet<>());

    public InMemoryAssetStorage(String mapId) {
        this.mapId = mapId;
    }

    /** Makes writing the named asset fail with an {@link IOException}. */
    public void failWritesOf(String name) {
        failingWrites.add(name);
    }

    /** The names of the assets written so far, in the order their writes were completed. */
    public List<String> completedWrites() {
        synchronized (completedWrites) {
            return List.copyOf(completedWrites);
        }
    }

    public byte[] bytes(String name) {
        return assets.get(name);
    }

    public void put(String name, byte[] bytes) {
        assets.put(name, bytes);
    }

    public Set<String> names() {
        return Set.copyOf(assets.keySet());
    }

    @Override
    public OutputStream writeAsset(String name) throws IOException {
        if (failingWrites.contains(name)) {
            throw new IOException("cannot write " + name);
        }
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                assets.put(name, toByteArray());
                completedWrites.add(name);
            }
        };
    }

    @Override
    public Optional<InputStream> readAsset(String name) {
        byte[] bytes = assets.get(name);
        return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean assetExists(String name) {
        return assets.containsKey(name);
    }

    @Override
    public String getAssetUrl(String name) {
        return "maps/" + mapId + "/assets/" + name;
    }

    @Override
    public void deleteAsset(String name) {
        assets.remove(name);
    }
}
