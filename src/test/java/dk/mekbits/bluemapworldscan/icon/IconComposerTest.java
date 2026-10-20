package dk.mekbits.bluemapworldscan.icon;

import static dk.mekbits.bluemapworldscan.icon.IconComposer.DARK_ARGB;
import static dk.mekbits.bluemapworldscan.icon.IconComposer.ICON_SIZE;
import static dk.mekbits.bluemapworldscan.icon.IconComposer.LIGHT_ARGB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** Ported from a24k/bluemap-structures-paper (MIT, see THIRD_PARTY_NOTICES.md). */
class IconComposerTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    @Test
    void sixteenSquareFrameIsDoubledCenteredAndOutlinedDarkThenLight() {
        BufferedImage icon = IconComposer.compose(solid(16, 16, RED));

        assertEquals(38, ICON_SIZE);
        assertEquals(ICON_SIZE, icon.getWidth());
        assertEquals(ICON_SIZE, icon.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, icon.getType());
        // 16 doubles to 32; (38 - 32) / 2 = 3: the picture spans (3,3)..(34,34) inclusive.
        assertEquals(RED, icon.getRGB(3, 3));
        assertEquals(RED, icon.getRGB(34, 34));
        assertEquals(DARK_ARGB, icon.getRGB(2, 10));
        assertEquals(DARK_ARGB, icon.getRGB(1, 10));
        assertEquals(LIGHT_ARGB, icon.getRGB(0, 10));
        assertEquals(DARK_ARGB, icon.getRGB(35, 20));
        assertEquals(DARK_ARGB, icon.getRGB(36, 20));
        assertEquals(LIGHT_ARGB, icon.getRGB(37, 20));
        assertEquals(LIGHT_ARGB, icon.getRGB(0, 0), "the outline has square corners");
    }

    @Test
    void transparentMarginIsCroppedSoASmallPictureIsScaledByTheLargestWholeFactor() {
        // A 4x8 picture in a 16x16 texture: 8 fits 32 four times, so it becomes 16x32 at (11, 3).
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(source, 6, 4, 4, 8, RED);

        BufferedImage icon = IconComposer.compose(source);

        assertEquals(RED, icon.getRGB(11, 3));
        assertEquals(RED, icon.getRGB(26, 34));
        assertEquals(DARK_ARGB, icon.getRGB(10, 20));
        assertEquals(DARK_ARGB, icon.getRGB(9, 20));
        assertEquals(LIGHT_ARGB, icon.getRGB(8, 20));
        assertEquals(0, alpha(icon.getRGB(7, 20)), "outside the outline stays fully transparent");
        assertEquals(0, alpha(icon.getRGB(30, 20)), "outside the outline stays fully transparent");
    }

    @Test
    void pictureThatCannotDoubleKeepsItsSize() {
        // 20 fits 32 only once; (38 - 20) / 2 = 9.
        BufferedImage icon = IconComposer.compose(solid(20, 20, GREEN));

        assertEquals(GREEN, icon.getRGB(9, 9));
        assertEquals(GREEN, icon.getRGB(28, 28));
        assertEquals(DARK_ARGB, icon.getRGB(8, 15));
        assertEquals(DARK_ARGB, icon.getRGB(29, 15));
        assertEquals(0, alpha(icon.getRGB(5, 15)));
    }

    @Test
    void verticalAnimationStripUsesOnlyTheTopFrame() {
        BufferedImage strip = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        fill(strip, 0, 0, 16, 16, RED);
        fill(strip, 0, 16, 16, 16, BLUE);

        BufferedImage icon = IconComposer.compose(strip);

        assertEquals(ICON_SIZE, icon.getWidth());
        assertEquals(ICON_SIZE, icon.getHeight());
        assertEquals(RED, icon.getRGB(3, 3));
        assertEquals(RED, icon.getRGB(34, 34));
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                assertNotEquals(BLUE, icon.getRGB(x, y), "the blue frame leaked into the icon at " + x + "," + y);
            }
        }
    }

    @Test
    void oversizedFrameIsScaledDownToFit() {
        // 64x64 scales by 32/64 to 32x32.
        BufferedImage icon = IconComposer.compose(solid(64, 64, GREEN));

        assertEquals(ICON_SIZE, icon.getWidth());
        assertEquals(GREEN, icon.getRGB(3, 3));
        assertEquals(GREEN, icon.getRGB(34, 34));
        assertEquals(DARK_ARGB, icon.getRGB(2, 2));
    }

    @Test
    void oversizedNonSquareFrameKeepsAspectRatioAndIsCentered() {
        // 128x32 scales by 0.25 to 32x8, centered vertically at offset (3, 15).
        BufferedImage icon = IconComposer.compose(solid(128, 32, GREEN));

        assertEquals(GREEN, icon.getRGB(3, 15));
        assertEquals(GREEN, icon.getRGB(34, 22));
        assertEquals(DARK_ARGB, icon.getRGB(10, 14));
        assertEquals(DARK_ARGB, icon.getRGB(10, 13));
        assertEquals(LIGHT_ARGB, icon.getRGB(10, 12));
        assertEquals(0, alpha(icon.getRGB(10, 11)), "above the outline stays transparent");
        assertEquals(0, alpha(icon.getRGB(10, 26)), "below the outline stays transparent");
    }

    @Test
    void transparentAndHalfTransparentPixelsInsideThePictureShowTheDarkOutlineBehindThem() {
        BufferedImage source = solid(16, 16, GREEN);
        source.setRGB(5, 7, 0x00000000); // fully transparent interior pixel
        source.setRGB(6, 7, 0x80FF0000); // half-transparent red pixel

        BufferedImage icon = IconComposer.compose(source);

        // Source pixel (x, y) covers canvas (3 + 2x, 3 + 2y) and its three neighbours.
        assertEquals(DARK_ARGB, icon.getRGB(13, 17), "a transparent source pixel shows the outline");
        int blended = icon.getRGB(15, 17);
        assertEquals(0xFF, alpha(blended), "a half-transparent pixel is drawn over the opaque outline");
        assertTrue(red(blended) > 0x80 && red(blended) < 0xFF, "the red is blended with the dark: " + Integer.toHexString(blended));
        assertEquals(GREEN, icon.getRGB(11, 17), "opaque neighbours keep their colour");
    }

    @Test
    void textureWithoutAVisiblePixelGivesATransparentIcon() {
        BufferedImage icon = IconComposer.compose(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));

        assertEquals(ICON_SIZE, icon.getWidth());
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                assertEquals(0, alpha(icon.getRGB(x, y)), "pixel " + x + "," + y);
            }
        }
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

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }
}
