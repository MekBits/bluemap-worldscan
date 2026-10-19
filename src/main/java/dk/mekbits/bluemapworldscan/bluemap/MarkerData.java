package dk.mekbits.bluemapworldscan.bluemap;

/**
 * Ids and popup text of structure markers. The popup holds the name, the block coordinates and a
 * {@code /tp} command to copy (adapted from a24k/bluemap-structures-paper, MIT, see
 * THIRD_PARTY_NOTICES.md).
 */
public final class MarkerData {

    private MarkerData() {
    }

    /** A marker id that is unique in its set: structures do not share a block position. */
    public static String markerId(int x, int y, int z) {
        return x + "_" + y + "_" + z;
    }

    public static String popupHtml(String label, int x, int y, int z) {
        return "<div class=\"worldscan-popup\">"
                + "<strong>" + escapeHtml(label) + "</strong><br>"
                // The label box is as narrow as the name; a pre is a block that does not wrap, so the
                // command stays on one line with plain spaces (Minecraft rejects non-breaking ones) and
                // needs no <br> before it.
                + x + " " + y + " " + z
                + "<pre>/tp @s " + x + " " + y + " " + z + "</pre>"
                + "</div>";
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
