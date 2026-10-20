package dk.mekbits.bluemapworldscan.bluemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkerDataTest {

    @Test
    void theMarkerIdIsTheBlockPosition() {
        assertEquals("160_63_-320", MarkerData.markerId(160, 63, -320));
    }

    @Test
    void thePopupHoldsTheNameAndTheCoordinates() {
        String html = MarkerData.popupHtml("Village", 160, 63, -320);

        assertEquals("<div class=\"worldscan-popup\"><strong>Village</strong><br>160 63 -320</div>", html);
    }

    @Test
    void thePopupHasNoTpCommand() {
        String html = MarkerData.popupHtml("Ocean monument", 207, 62, -897);

        assertFalse(html.contains("/tp"), html);
    }

    @Test
    void thePopupEscapesHtmlInTheName() {
        String html = MarkerData.popupHtml("<script>alert(1)</script>", 0, 63, 0);

        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void escapingCoversTheFiveSpecialCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", MarkerData.escapeHtml("&<>\"'"));
    }
}
