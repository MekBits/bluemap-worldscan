package dk.mekbits.bluemapworldscan.layer;

import java.util.List;

import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;

/**
 * About how many bytes layers take in BlueMap's {@code live/markers.json}, the file every browser
 * loads. The stored JSON is no guide: a corner is about 9 bytes there and 23 in the served file.
 * The sizes were measured on a large world.
 */
public final class ServedSize {

    /** The web app should load less than this much of markers; more is warned about. */
    public static final long WARNING_BYTES = 10_000_000L;
    /** What a point marker takes, popup and icon URL included: 11,577 markers took 5,614,445 bytes. */
    public static final int POI_BYTES = 485;
    /** What a shape marker takes, before its corners. */
    public static final int AREA_BYTES = 365;
    /** What a corner of a shape takes: {@code {"x":-12345,"z":-12345},}. */
    public static final int CORNER_BYTES = 21;
    /**
     * The most one cell of one biome can add to the biome's areas: a union of squares has at most
     * four corners per square and at most one area per square.
     */
    public static final long CELL_UPPER_BOUND_BYTES = AREA_BYTES + 4L * CORNER_BYTES;

    private ServedSize() {
    }

    /** The estimate for the whole file. */
    public static long estimate(LayersFile layers) {
        long bytes = 0;
        for (LayerSet set : layers.sets()) {
            bytes += (long) set.pois().size() * POI_BYTES;
            for (Area area : set.areas()) {
                bytes += AREA_BYTES + (long) CORNER_BYTES
                        * (area.outline().size() + area.holes().stream().mapToInt(List::size).sum());
            }
        }
        return bytes;
    }
}
