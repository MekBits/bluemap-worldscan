package dk.mekbits.bluemapworldscan.bluemap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;

/**
 * Turns stored layers into BlueMap marker sets on a map. Every instance does this with the same
 * code from the same file, so the sets of two instances are the same.
 *
 * <p>It remembers which sets it put on each map, so a set that a newer set of layers no longer
 * holds (the last structure of its kind was pruned, or the layer was switched off) is removed
 * again. It is used from the addon's worker thread only, and never from BlueMap's disable
 * callback: BlueMap saves marker sets after that, and an empty map would be stored.
 */
public final class LayerPublisher {

    private final Map<String, Set<String>> publishedSetIds = new HashMap<>();

    /** Puts the layers on the map as marker sets and removes the sets published earlier that are not among them. */
    public void publish(BlueMapMap map, LayersFile layers) {
        Set<String> current = new HashSet<>();
        for (LayerSet layer : layers.sets()) {
            map.getMarkerSets().put(layer.id(), toMarkerSet(layer));
            current.add(layer.id());
        }
        Set<String> previous = publishedSetIds.put(map.getId(), current);
        if (previous != null) {
            previous.removeAll(current);
            previous.forEach(map.getMarkerSets()::remove);
        }
    }

    private static MarkerSet toMarkerSet(LayerSet layer) {
        MarkerSet set = MarkerSet.builder()
                .label(layer.label())
                .toggleable(true)
                .defaultHidden(layer.defaultHidden())
                .sorting(layer.sorting())
                .build();
        for (Poi poi : layer.pois()) {
            set.put(poi.id(), toMarker(poi));
        }
        return set;
    }

    private static POIMarker toMarker(Poi poi) {
        POIMarker.Builder marker = POIMarker.builder()
                .label(poi.label())
                .detail(poi.detail())
                .position(poi.x() + 0.5, poi.y(), poi.z() + 0.5)
                .maxDistance(poi.maxDistance());
        if (poi.icon() != null) {
            marker.icon(poi.icon(), poi.anchorX(), poi.anchorY());
        }
        return marker.build();
    }
}
