package dk.mekbits.bluemapworldscan.state;

import java.util.List;
import java.util.SortedMap;

/**
 * What the scan state holds of one region file.
 *
 * <p>Record equality compares the arrays by reference; tests compare them with
 * {@code assertArrayEquals}.
 *
 * @param timestamps per chunk index (1024) the header save timestamp the chunk was read at; -1
 *     means never read (or absent), so a present chunk whose header timestamp is 0 is still read
 * @param structures the structures of all chunks of the region, ordered by chunk index
 * @param biomes per biome id the chunks that hold it: a {@code long[16]}, bit {@code i} for chunk
 *     index {@code i}
 */
public record RegionEntry(int[] timestamps, List<StructureEntry> structures, SortedMap<String, long[]> biomes) {
}
