package dk.mekbits.bluemapworldscan.icon;

import java.awt.image.BufferedImage;

/**
 * Composes marker icons from vanilla client-jar textures (adapted from
 * a24k/bluemap-structures-paper, MIT, see THIRD_PARTY_NOTICES.md). Animated block textures are
 * tall strips of stacked frames, of which only the first is used. Textures larger than the icon
 * are scaled down with nearest-neighbour sampling so pixel art stays crisp.
 */
public final class IconComposer {

    public static final int ICON_SIZE = 22;

    private IconComposer() {
    }

    /**
     * Composes a 22x22 ARGB icon: an animation strip is cropped to its top frame, a frame larger
     * than the icon is scaled down to fit (aspect kept), and the result is centred on a
     * transparent canvas.
     */
    public static BufferedImage compose(BufferedImage source) {
        BufferedImage frame = scaleDownToFit(cropToTopFrame(source));

        BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        int offsetX = (ICON_SIZE - frame.getWidth()) / 2;
        int offsetY = (ICON_SIZE - frame.getHeight()) / 2;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                canvas.setRGB(offsetX + x, offsetY + y, frame.getRGB(x, y));
            }
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

    private static BufferedImage scaleDownToFit(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= ICON_SIZE && height <= ICON_SIZE) {
            return frame;
        }

        double scale = Math.min((double) ICON_SIZE / width, (double) ICON_SIZE / height);
        int newWidth = clamp((int) Math.floor(width * scale));
        int newHeight = clamp((int) Math.floor(height * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < newHeight; y++) {
            int sourceY = Math.min(height - 1, (int) (y / scale));
            for (int x = 0; x < newWidth; x++) {
                int sourceX = Math.min(width - 1, (int) (x / scale));
                scaled.setRGB(x, y, frame.getRGB(sourceX, sourceY));
            }
        }
        return scaled;
    }

    private static int clamp(int dimension) {
        return Math.max(1, Math.min(ICON_SIZE, dimension));
    }
}
