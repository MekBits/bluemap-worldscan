package dk.mekbits.bluemapworldscan.chunk;

import org.jetbrains.annotations.Nullable;

/**
 * A structure start found in a chunk.
 *
 * <p>Record equality compares the box arrays by reference.
 *
 * @param structureId the current structure id
 * @param box the union of the start's pieces, {@code {minX, minY, minZ, maxX, maxY, maxZ}}
 * @param endShipBox the box of the ship piece of an end city, or null
 */
public record FoundStructure(String structureId, int[] box, @Nullable int[] endShipBox) {

    private static final int BOX_LENGTH = 6;

    public FoundStructure {
        if (box.length != BOX_LENGTH) {
            throw new IllegalArgumentException("a box has " + BOX_LENGTH + " coordinates, not " + box.length);
        }
        if (endShipBox != null && endShipBox.length != BOX_LENGTH) {
            throw new IllegalArgumentException("a box has " + BOX_LENGTH + " coordinates, not " + endShipBox.length);
        }
    }
}
