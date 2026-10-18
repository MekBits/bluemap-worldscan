package dk.mekbits.bluemapworldscan.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

import dk.mekbits.bluemapworldscan.layer.Layer;
import dk.mekbits.bluemapworldscan.layer.LayerCatalog;
import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAssetIconsTest {

    private static final int RED = 0xFFFF0000;

    @TempDir
    Path folder;

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A client jar holding a texture for every layer except those named in {@code without}. */
    private Path jar(String... without) throws IOException {
        Path jar = folder.resolve("client.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Layer layer : LayerCatalog.layers()) {
                if (Arrays.asList(without).contains(layer.id())) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(layer.icon()));
                zip.write(png(16, 16, RED));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void everyCatalogLayerGetsA22x22PngFromItsTexture() throws IOException {
        Map<String, byte[]> icons = ClientAssetIcons.compose(jar());

        assertEquals(LayerCatalog.layers().size(), icons.size());
        BufferedImage village = ImageIO.read(new ByteArrayInputStream(icons.get("village")));
        assertEquals(IconComposer.ICON_SIZE, village.getWidth());
        assertEquals(IconComposer.ICON_SIZE, village.getHeight());
        assertEquals(RED, village.getRGB(11, 11));
    }

    @Test
    void aLayerWhoseTextureIsMissingFromTheJarGetsNoIconAndIsLogged() throws IOException {
        try (LogCapture log = new LogCapture()) {
            Map<String, byte[]> icons = ClientAssetIcons.compose(jar("village"));

            assertFalse(icons.containsKey("village"));
            assertNotNull(icons.get("igloo"));
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("Village")), log.warnings().toString());
        }
    }

    @Test
    void aTextureThatIsNotAnImageGetsNoIcon() throws IOException {
        Path jar = folder.resolve("bad.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(LayerCatalog.layerFor("minecraft:village").icon()));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }

        try (LogCapture log = new LogCapture()) {
            assertEquals(Map.of(), ClientAssetIcons.compose(jar));
            assertTrue(log.warnings().stream().anyMatch(message -> message.contains("cannot decode")), log.warnings().toString());
        }
    }

    @Test
    void withoutAClientJarThereAreNoIcons() {
        ClientAssetIcons icons = new ClientAssetIcons(new ClientJar(folder.resolve("none"), URI.create("http://127.0.0.1:1/"))::ensure);

        try (LogCapture log = new LogCapture()) {
            assertEquals(Map.of(), icons.pngByLayer());
            assertEquals(Map.of(), icons.pngByLayer());
            assertEquals(1, log.warnings().size(), "logged once: " + log.warnings());
        }
    }
}
