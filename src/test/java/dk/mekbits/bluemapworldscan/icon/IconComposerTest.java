package dk.mekbits.bluemapworldscan.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** Ported from a24k/bluemap-structures-paper (MIT, see THIRD_PARTY_NOTICES.md). */
class IconComposerTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    @Test
    void sixteenSquareFrameIsCenteredOnTransparentCanvas() {
        BufferedImage icon = IconComposer.compose(solid(16, 16, RED));

        assertEquals(IconComposer.ICON_SIZE, icon.getWidth());
        assertEquals(IconComposer.ICON_SIZE, icon.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, icon.getType());
        // (22 - 16) / 2 = 3: the frame spans (3,3)..(18,18) inclusive.
        assertEquals(RED, icon.getRGB(3, 3));
        assertEquals(RED, icon.getRGB(18, 18));
        assertEquals(0, alpha(icon.getRGB(0, 0)), "the border stays fully transparent");
        assertEquals(0, alpha(icon.getRGB(21, 21)), "the border stays fully transparent");
        assertEquals(0, alpha(icon.getRGB(2, 10)), "the left margin stays fully transparent");
        assertEquals(0, alpha(icon.getRGB(10, 19)), "the bottom margin stays fully transparent");
    }

    @Test
    void verticalAnimationStripUsesOnlyTheTopFrame() {
        BufferedImage strip = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        fill(strip, 0, 0, 16, 16, RED);
        fill(strip, 0, 16, 16, 16, BLUE);

        BufferedImage icon = IconComposer.compose(strip);

        assertEquals(IconComposer.ICON_SIZE, icon.getWidth());
        assertEquals(IconComposer.ICON_SIZE, icon.getHeight());
        assertEquals(RED, icon.getRGB(3, 3));
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                int argb = icon.getRGB(x, y);
                if (alpha(argb) != 0) {
                    assertEquals(RED, argb, "non-red pixel at " + x + "," + y);
                }
                assertNotEquals(BLUE, argb, "the blue frame leaked into the icon at " + x + "," + y);
            }
        }
    }

    @Test
    void oversizedFrameIsScaledDownToFit() {
        BufferedImage icon = IconComposer.compose(solid(32, 32, GREEN));

        assertEquals(IconComposer.ICON_SIZE, icon.getWidth());
        assertEquals(IconComposer.ICON_SIZE, icon.getHeight());
        // 32x32 scales by 22/32 to exactly 22x22, so the canvas is fully covered.
        assertEquals(GREEN, icon.getRGB(11, 11));
        assertEquals(GREEN, icon.getRGB(0, 0));
        assertEquals(GREEN, icon.getRGB(21, 21));
    }

    @Test
    void oversizedNonSquareFrameKeepsAspectRatioAndIsCentered() {
        // 44x22 scales by 0.5 to 22x11, centered vertically at offset (0, 5).
        BufferedImage icon = IconComposer.compose(solid(44, 22, GREEN));

        assertEquals(IconComposer.ICON_SIZE, icon.getWidth());
        assertEquals(IconComposer.ICON_SIZE, icon.getHeight());
        assertEquals(GREEN, icon.getRGB(0, 5));
        assertEquals(GREEN, icon.getRGB(21, 15));
        assertEquals(0, alpha(icon.getRGB(0, 4)), "above the frame stays transparent");
        assertEquals(0, alpha(icon.getRGB(21, 16)), "below the frame stays transparent");
        assertEquals(0, alpha(icon.getRGB(11, 0)));
        assertEquals(0, alpha(icon.getRGB(11, 21)));
    }

    @Test
    void sourceAlphaIsPreservedPerPixel() {
        BufferedImage source = solid(16, 16, GREEN);
        source.setRGB(5, 7, 0x00000000); // fully transparent interior pixel
        source.setRGB(6, 7, 0x80FF0000); // half-transparent red pixel

        BufferedImage icon = IconComposer.compose(source);

        assertEquals(0, alpha(icon.getRGB(3 + 5, 3 + 7)), "a transparent source pixel stays clear");
        assertEquals(0x80FF0000, icon.getRGB(3 + 6, 3 + 7), "partial alpha survives verbatim");
        assertEquals(GREEN, icon.getRGB(3 + 4, 3 + 7), "opaque neighbors keep full alpha");
        assertEquals(GREEN, icon.getRGB(3 + 5, 3 + 6));
    }

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, width, height, argb);
        return image;
    }

    private static void fill(BufferedImage image, int x0, int y0, int width, int height, int argb) {
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
