package dk.mekbits.bluemapworldscan.layer;

import org.jetbrains.annotations.Nullable;

/**
 * One toggleable structure layer. A layer can gather several structure ids (all village variants).
 *
 * @param id the layer id, also the name of the icon of the layer and the end of its marker set id
 * @param label the name shown in the sidebar and in marker popups
 * @param defaultHidden whether the layer starts switched off
 * @param icon the path of the vanilla texture in the Minecraft client jar that the layer's icon is
 *     made from, or null when the layer has no icon (BlueMap then draws its default marker)
 * @param maxDistance how close the camera must be for a marker to show: wide for sparse
 *     structures, small for dense ones that would clutter a zoomed-out map
 */
public record Layer(String id, String label, boolean defaultHidden, @Nullable String icon, double maxDistance) {
}
