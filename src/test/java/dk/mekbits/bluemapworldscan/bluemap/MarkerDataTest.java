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
    void thePopupHoldsTheNameTheCoordinatesAndACopyableTpCommand() {
        String html = MarkerData.popupHtml("Village", 160, 63, -320);

        assertTrue(html.contains("<strong>Village</strong>"), html);
        assertTrue(html.contains("160 63 -320<pre>"), html);
        assertTrue(html.contains("<pre>/tp @s 160 63 -320</pre>"), html);
    }

    /**
     * BlueMap's label box is as narrow as the name, so a command in an inline element breaks at its
     * spaces. A {@code pre} does not wrap, and its text must keep plain ASCII spaces because Minecraft's
     * command parser rejects non-breaking ones.
     */
    @Test
    void theTpCommandIsOneLineOfPlainSpaces() {
        String html = MarkerData.popupHtml("Ocean monument", 207, 62, -897);

        assertTrue(html.contains("<pre>/tp @s 207 62 -897</pre>"), html);
        assertFalse(html.contains("<code>"), html);
        assertFalse(html.contains("&nbsp;") || html.contains(" "), html);
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
