package dk.mekbits.bluemapworldscan.state;

import org.jetbrains.annotations.Nullable;

/**
 * One structure found in one chunk of a region.
 *
 * <p>Record equality compares the arrays by reference; tests compare them with
 * {@code assertArrayEquals}.
 *
 * @param chunkIndex the chunk's index in its region ({@code x + 32 * z}, 0-1023), so one chunk's
 *     contribution can be replaced
 * @param structureId the current structure id
 * @param box the bounding box {minX, minY, minZ, maxX, maxY, maxZ}
 * @param endShipBox the bounding box of an end city's ship, null for any other structure or an end
 *     city without a ship
 */
public record StructureEntry(int chunkIndex, String structureId, int[] box, @Nullable int[] endShipBox) {
}
