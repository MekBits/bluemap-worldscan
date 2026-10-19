package dk.mekbits.bluemapworldscan.bluemap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.IconComposer;
import dk.mekbits.bluemapworldscan.layer.Layer;
import dk.mekbits.bluemapworldscan.layer.LayerCatalog;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;

/**
 * Turns a scan state into the structure layers of a map. The same state, configuration and icon
 * URLs always give the same layers in the same order, so the JSON that is stored is byte-identical.
 */
public final class LayerBuilder {

    static final String STRUCTURE_SET_PREFIX = "worldscan-structure-";

    private static final int ICON_ANCHOR = IconComposer.ICON_SIZE / 2;

    private LayerBuilder() {
    }

    /**
     * Builds one set per layer that has structures and is not in {@code structures.disabledLayers}.
     *
     * @param iconUrls layer id to icon URL; the URL names the map, so this is called once per map
     */
    public static List<LayerSet> buildStructures(ScanState state, AddonConfig config, Map<String, String> iconUrls) {
        Set<String> disabled = Set.copyOf(config.structures().disabledLayers());
        Map<Layer, Map<String, Poi>> poisByLayer = new LinkedHashMap<>();

        for (RegionEntry region : state.regions().values()) {
            for (StructureEntry structure : region.structures()) {
                addPoi(poisByLayer, disabled, iconUrls, LayerCatalog.layerFor(structure.structureId()), structure.box());
                if (structure.endShipBox() != null) {
                    addPoi(poisByLayer, disabled, iconUrls, LayerCatalog.END_CITY_SHIP, structure.endShipBox());
                }
            }
        }

        List<Layer> layers = new ArrayList<>(poisByLayer.keySet());
        layers.sort(Comparator.comparingInt(LayerCatalog::order).thenComparing(Layer::id));
        List<LayerSet> sets = new ArrayList<>();
        for (Layer layer : layers) {
            sets.add(new LayerSet(
                    STRUCTURE_SET_PREFIX + layer.id(), layer.label(), layer.defaultHidden(), sets.size(),
                    List.copyOf(poisByLayer.get(layer).values())));
        }
        return sets;
    }

    /**
     * Where a structure's marker and its {@code /tp} go: the middle of its box, on top of it. The
     * middle of the box is inside rock for buried structures, so the height is the top.
     *
     * @param box {minX, minY, minZ, maxX, maxY, maxZ}
     * @return {x, y, z}
     */
    public static int[] markerPoint(int[] box) {
        return new int[] {Math.floorDiv(box[0] + box[3], 2), box[4] + 1, Math.floorDiv(box[2] + box[5], 2)};
    }

    private static void addPoi(
            Map<Layer, Map<String, Poi>> poisByLayer, Set<String> disabled, Map<String, String> iconUrls,
            Layer layer, int[] box) {
        if (disabled.contains(layer.id())) {
            return;
        }
        int[] point = markerPoint(box);
        Poi poi = new Poi(
                MarkerData.markerId(point[0], point[1], point[2]),
                layer.label(), point[0], point[1], point[2],
                MarkerData.popupHtml(layer.label(), point[0], point[1], point[2]),
                iconUrls.get(layer.id()), ICON_ANCHOR, ICON_ANCHOR, layer.maxDistance());
        poisByLayer.computeIfAbsent(layer, key -> new LinkedHashMap<>()).putIfAbsent(poi.id(), poi);
    }
}
