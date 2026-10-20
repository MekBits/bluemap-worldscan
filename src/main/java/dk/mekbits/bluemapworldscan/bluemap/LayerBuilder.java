package dk.mekbits.bluemapworldscan.bluemap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dk.mekbits.bluemapworldscan.biome.BiomeColors;
import dk.mekbits.bluemapworldscan.biome.BiomeNames;
import dk.mekbits.bluemapworldscan.biome.BiomeShapes;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.icon.IconComposer;
import dk.mekbits.bluemapworldscan.layer.Layer;
import dk.mekbits.bluemapworldscan.layer.LayerCatalog;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.state.RegionEntry;
import dk.mekbits.bluemapworldscan.state.ScanState;
import dk.mekbits.bluemapworldscan.state.StructureEntry;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * Turns a scan state into the structure layers and the biome layers of a map. The same state,
 * configuration and icon URLs always give the same layers in the same order, so the JSON that is
 * stored is byte-identical.
 */
public final class LayerBuilder {

    static final String STRUCTURE_SET_PREFIX = "worldscan-structure-";
    static final String BIOME_SET_PREFIX = "worldscan-biome-";
    static final String BIOME_LABEL_PREFIX = "Biome: ";
    /** The height biome areas are drawn at, about sea level. */
    static final float BIOME_AREA_Y = 63f;

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
     * Builds one hidden set per biome in the state that is not in {@code biomes.disabled}, with one
     * shape marker per connected area of the biome (holes included). The sets hold no icon URLs,
     * so they are built once per world and are the same on every map. The result is empty when
     * {@code biomes.enabled} is off.
     *
     * <p>The sets are in the order of their names; {@link #combine} puts them after the structure
     * sets in the sidebar.
     *
     * @param names the English names of the biomes
     */
    public static List<LayerSet> buildBiomes(ScanState state, AddonConfig config, BiomeNames names) {
        if (!config.biomes().enabled()) {
            return List.of();
        }
        Set<String> disabled = config.biomes().disabled().stream()
                .map(id -> id.indexOf(':') < 0 ? "minecraft:" + id : id)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, List<Polygon>> shapes = BiomeShapes.build(state, config.biomes().cellSizeChunks(), biome -> !disabled.contains(biome));

        List<LayerSet> sets = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (Map.Entry<String, List<Polygon>> biome : shapes.entrySet()) {
            String biomeId = biome.getKey();
            if (disabled.contains(biomeId)) {
                continue;
            }
            String label = names.label(biomeId);
            sets.add(new LayerSet(
                    uniqueSetId(biomeId, usedIds), BIOME_LABEL_PREFIX + label, true, 0, List.of(),
                    areas(biomeId, label, biome.getValue())));
        }
        sets.sort(Comparator.comparing(LayerSet::label).thenComparing(LayerSet::id));
        return renumbered(sets, 0);
    }

    /** The structure sets followed by the biome sets, numbered in that order for the sidebar. */
    public static List<LayerSet> combine(List<LayerSet> structures, List<LayerSet> biomes) {
        List<LayerSet> all = new ArrayList<>(renumbered(structures, 0));
        all.addAll(renumbered(biomes, structures.size()));
        return List.copyOf(all);
    }

    private static List<LayerSet> renumbered(List<LayerSet> sets, int first) {
        List<LayerSet> numbered = new ArrayList<>();
        for (LayerSet set : sets) {
            numbered.add(set.withSorting(first + numbered.size()));
        }
        return numbered;
    }

    /** {@code worldscan-biome-<namespace>-<path>}, with characters a set id should not hold replaced and clashes numbered. */
    private static String uniqueSetId(String biomeId, Set<String> usedIds) {
        int colon = biomeId.indexOf(':');
        // An id without a namespace is minecraft's, as in biomes.disabled.
        String namespace = colon < 0 ? "minecraft" : biomeId.substring(0, colon);
        String base = BIOME_SET_PREFIX + (namespace + "-" + biomeId.substring(colon + 1))
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        String id = base;
        for (int n = 2; !usedIds.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private static List<Area> areas(String biomeId, String label, List<Polygon> polygons) {
        List<Polygon> normalized = new ArrayList<>();
        for (Polygon polygon : polygons) {
            // Normal form: the same rings start at the same corner in the same direction on every JVM.
            Polygon copy = (Polygon) polygon.copy();
            copy.normalize();
            normalized.add(copy);
        }
        normalized.sort(Comparator.<Polygon>comparingDouble(p -> p.getEnvelopeInternal().getMinY())
                .thenComparingDouble(p -> p.getEnvelopeInternal().getMinX())
                .thenComparingInt(Polygon::getNumPoints));
        int fillArgb = BiomeColors.fillArgb(biomeId);
        List<Area> areas = new ArrayList<>();
        for (Polygon polygon : normalized) {
            List<List<int[]>> holes = new ArrayList<>();
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                holes.add(corners(polygon.getInteriorRingN(i)));
            }
            areas.add(new Area(Integer.toString(areas.size()), label, BIOME_AREA_Y, corners(polygon.getExteriorRing()), holes, fillArgb));
        }
        return areas;
    }

    /** The corners of a ring as {x, z} block coordinates, without the repeat of the first corner that closes a JTS ring. */
    private static List<int[]> corners(LinearRing ring) {
        Coordinate[] coordinates = ring.getCoordinates();
        List<int[]> corners = new ArrayList<>(coordinates.length - 1);
        for (int i = 0; i < coordinates.length - 1; i++) {
            corners.add(new int[] {block(coordinates[i].x), block(coordinates[i].y)});
        }
        return corners;
    }

    /** A block coordinate that stays on its side of the world when it is far out of the int range. */
    private static int block(double coordinate) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(coordinate)));
    }

    /**
     * Where a structure's marker goes: the middle of its box, on top of it. The
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
