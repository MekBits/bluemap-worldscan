package dk.mekbits.bluemapworldscan.addon;

import java.util.List;

import de.bluecolored.bluemap.api.BlueMapWorld;
import dk.mekbits.bluemapworldscan.bluemap.MapLayers;

/**
 * A world as BlueMap's API showed it when it was enabled: the world and, for each of its maps,
 * the object that keeps the map's marker sets in step with its storage.
 *
 * @param id the world id
 * @param world the API object of the world
 * @param maps the world's maps
 */
record CapturedWorld(String id, BlueMapWorld world, List<MapLayers> maps) {
}
