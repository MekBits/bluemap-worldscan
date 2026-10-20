package dk.mekbits.bluemapworldscan.icon;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Composes marker icons from vanilla client-jar textures (adapted from
 * a24k/bluemap-structures-paper, MIT, see THIRD_PARTY_NOTICES.md). Animated block textures are
 * tall strips of stacked frames, of which only the first is used. The picture is cropped to its
 * visible pixels, scaled with nearest-neighbour sampling so pixel art stays crisp, and outlined in
 * dark and then light so it stands out on any terrain when the map is zoomed out.
 */
public final class IconComposer {

    /** The longest side of the scaled picture. */
    static final int PICTURE_SIZE = 32;
    static final int DARK_OUTLINE = 2;
    static final int LIGHT_OUTLINE = 1;
    static final int DARK_ARGB = 0xFF101010;
    static final int LIGHT_ARGB = 0xFFFFFFFF;

    public static final int ICON_SIZE = PICTURE_SIZE + 2 * (DARK_OUTLINE + LIGHT_OUTLINE);

    private IconComposer() {
    }

    /**
     * Composes a square ARGB icon of {@link #ICON_SIZE}: an animation strip is cropped to its top
     * frame and the frame to its visible pixels. A picture smaller than {@link #PICTURE_SIZE} is
     * scaled up by the largest whole factor that fits, a larger one is scaled down to fit (aspect
     * kept). The picture is centred on a transparent canvas and outlined. A texture without a
     * visible pixel gives a transparent icon.
     */
    public static BufferedImage compose(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage frame = cropToTopFrame(source);
        int[] bounds = visibleBounds(frame);
        if (bounds == null) {
            return canvas;
        }
        BufferedImage picture = scaleToFit(frame.getSubimage(bounds[0], bounds[1], bounds[2], bounds[3]));

        int offsetX = (ICON_SIZE - picture.getWidth()) / 2;
        int offsetY = (ICON_SIZE - picture.getHeight()) / 2;
        boolean[][] visible = new boolean[ICON_SIZE][ICON_SIZE];
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                visible[offsetY + y][offsetX + x] = alpha(picture.getRGB(x, y)) != 0;
            }
        }
        paint(canvas, grow(visible, DARK_OUTLINE + LIGHT_OUTLINE), LIGHT_ARGB);
        paint(canvas, grow(visible, DARK_OUTLINE), DARK_ARGB);

        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(picture, offsetX, offsetY, null);
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    private static BufferedImage cropToTopFrame(BufferedImage source) {
        int width = source.getWidth();
        if (source.getHeight() <= width) {
            return source;
        }
        return source.getSubimage(0, 0, width, width);
    }

    /** {x, y, width, height} of the pixels that are not fully transparent, or null when there are none. */
    private static int[] visibleBounds(BufferedImage frame) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                if (alpha(frame.getRGB(x, y)) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    private static BufferedImage scaleToFit(BufferedImage picture) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        int longest = Math.max(width, height);
        // Up by a whole factor, so every texture pixel becomes a square of the same size.
        double scale = longest <= PICTURE_SIZE ? PICTURE_SIZE / longest : (double) PICTURE_SIZE / longest;
        if (scale == 1) {
            return picture;
        }
        int newWidth = clamp((int) Math.floor(width * scale));
        int newHeight = clamp((int) Math.floor(height * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < newHeight; y++) {
            int sourceY = Math.min(height - 1, (int) (y / scale));
            for (int x = 0; x < newWidth; x++) {
                int sourceX = Math.min(width - 1, (int) (x / scale));
                scaled.setRGB(x, y, picture.getRGB(sourceX, sourceY));
            }
        }
        return scaled;
    }

    /** The pixels within {@code radius} of a set pixel, both ways, so the outline has square corners. */
    private static boolean[][] grow(boolean[][] mask, int radius) {
        boolean[][] grown = new boolean[ICON_SIZE][ICON_SIZE];
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                grown[y][x] = anySet(mask, x, y, radius);
            }
        }
        return grown;
    }

    private static boolean anySet(boolean[][] mask, int x, int y, int radius) {
        for (int my = Math.max(0, y - radius); my <= Math.min(ICON_SIZE - 1, y + radius); my++) {
            for (int mx = Math.max(0, x - radius); mx <= Math.min(ICON_SIZE - 1, x + radius); mx++) {
                if (mask[my][mx]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void paint(BufferedImage canvas, boolean[][] mask, int argb) {
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                if (mask[y][x]) {
                    canvas.setRGB(x, y, argb);
                }
            }
        }
    }

    private static int clamp(int dimension) {
        return Math.max(1, Math.min(PICTURE_SIZE, dimension));
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
