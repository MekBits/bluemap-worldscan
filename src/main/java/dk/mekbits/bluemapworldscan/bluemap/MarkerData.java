package dk.mekbits.bluemapworldscan.bluemap;

/**
 * Ids and popup text of structure markers. The popup holds the name and the block coordinates
 * (adapted from a24k/bluemap-structures-paper, MIT, see THIRD_PARTY_NOTICES.md).
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
                + x + " " + y + " " + z
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
