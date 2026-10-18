package dk.mekbits.bluemapworldscan.layer;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * The finished layers of one map, as the scanning instance stores them and every instance turns
 * them into marker sets. The records are read and written as JSON with the addon's own Gson,
 * because the API's {@code MarkerGson} is not reachable through the relocated libraries.
 *
 * @param formatVersion {@link #FORMAT_VERSION} for files this version of the addon writes
 * @param sets the layers, in sidebar order
 */
public record LayersFile(int formatVersion, List<LayerSet> sets) {

    /** The version of the stored layout; an instance leaves a file of another version alone. */
    public static final int FORMAT_VERSION = 1;

    public LayersFile {
        sets = sets == null ? List.of() : List.copyOf(sets);
    }

    /**
     * One marker set.
     *
     * @param id the marker set id
     * @param label the name in the sidebar
     * @param defaultHidden whether the set starts switched off
     * @param sorting the position in the sidebar
     * @param pois the point markers, in a fixed order
     * @param areas the shape markers, in a fixed order
     */
    public record LayerSet(
            String id, String label, boolean defaultHidden, int sorting, List<Poi> pois, List<Area> areas) {

        public LayerSet {
            pois = pois == null ? List.of() : List.copyOf(pois);
            areas = areas == null ? List.of() : List.copyOf(areas);
        }
    }

    /**
     * A point marker.
     *
     * @param id the marker id, unique in its set
     * @param label the marker label
     * @param x the block x
     * @param y the block y
     * @param z the block z
     * @param detail the popup HTML
     * @param icon the URL of the icon, relative to the web root, or null for BlueMap's default marker
     * @param anchorX the x of the icon pixel that sits on the position
     * @param anchorY the y of the icon pixel that sits on the position
     * @param maxDistance how close the camera must be for the marker to show
     */
    public record Poi(
            String id, String label, int x, int y, int z, String detail,
            @Nullable String icon, int anchorX, int anchorY, double maxDistance) {
    }

    /**
     * A shape marker: an outline with holes, flat at one height.
     *
     * @param id the marker id, unique in its set
     * @param label the marker label
     * @param y the height the shape is drawn at
     * @param outline the outer ring as {x, z} block coordinates
     * @param holes the inner rings
     * @param fillArgb the fill colour as 0xAARRGGBB
     */
    public record Area(String id, String label, float y, List<int[]> outline, List<List<int[]>> holes, int fillArgb) {
    }
}
