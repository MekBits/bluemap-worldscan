package dk.mekbits.bluemapworldscan.state;

import java.util.SortedMap;

/**
 * Everything a scan remembers of one world: per region file name ({@code r.<x>.<z>.mca}) what
 * was found in it and the timestamps it was read at.
 *
 * <p>A state is never changed after it is made; the scanner builds a new one from the previous.
 *
 * @param formatVersion {@link #FORMAT_VERSION} for states this version of the addon writes
 * @param worldId the id of the world the state belongs to; a state of another world is not used
 * @param regions the regions by file name, iterating in sorted order
 */
public record ScanState(int formatVersion, String worldId, SortedMap<String, RegionEntry> regions) {

    /** The version of the stored layout; a stored state of another version is not used. */
    public static final int FORMAT_VERSION = 1;
}
