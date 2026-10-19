package dk.mekbits.bluemapworldscan.testsupport;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.RenderManager;
import de.bluecolored.bluemap.api.WebApp;
import de.bluecolored.bluemap.api.plugin.Plugin;

/**
 * A BlueMap API with its worlds and their maps, to hand to the addon's enable and disable listeners.
 * Like the command line tool, it has no plugin unless {@code platformIsPlugin} is set.
 */
public final class FakeBlueMap extends BlueMapAPI {

    /** A world that is a folder with a {@code region} folder. */
    public static final class FakeWorld implements BlueMapWorld {
        private final String id;
        private final Path saveFolder;
        private final List<BlueMapMap> maps;

        public FakeWorld(String id, Path saveFolder, List<InMemoryBlueMapMap> maps) {
            this.id = id;
            this.saveFolder = saveFolder;
            this.maps = List.copyOf(maps);
            maps.forEach(map -> map.setWorld(this));
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Path getSaveFolder() {
            return saveFolder;
        }

        @Override
        public Collection<BlueMapMap> getMaps() {
            return maps;
        }
    }

    private final List<BlueMapWorld> worlds;
    private final boolean platformIsPlugin;

    public FakeBlueMap(boolean platformIsPlugin, FakeWorld... worlds) {
        this.platformIsPlugin = platformIsPlugin;
        this.worlds = List.of(worlds);
    }

    @Override
    public RenderManager getRenderManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebApp getWebApp() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Plugin getPlugin() {
        if (!platformIsPlugin) {
            throw new UnsupportedOperationException("the command line tool has no plugin");
        }
        return null;
    }

    @Override
    public Collection<BlueMapMap> getMaps() {
        return worlds.stream().flatMap(world -> world.getMaps().stream()).toList();
    }

    @Override
    public Collection<BlueMapWorld> getWorlds() {
        return worlds;
    }

    @Override
    public Optional<BlueMapWorld> getWorld(Object world) {
        return worlds.stream().filter(candidate -> candidate.getId().equals(world)).findFirst();
    }

    @Override
    public Optional<BlueMapMap> getMap(String id) {
        return getMaps().stream().filter(map -> map.getId().equals(id)).findFirst();
    }

    @Override
    public String getBlueMapVersion() {
        return "test";
    }
}
