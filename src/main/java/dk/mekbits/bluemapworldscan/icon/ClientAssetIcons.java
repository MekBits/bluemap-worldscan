package dk.mekbits.bluemapworldscan.icon;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

import dk.mekbits.bluemapworldscan.layer.Layer;
import dk.mekbits.bluemapworldscan.layer.LayerCatalog;
import dk.mekbits.bluemapworldscan.util.Log;

/**
 * Makes the layer icons from the vanilla textures in the Minecraft client jar (the approach of
 * a24k/bluemap-structures-paper, MIT, see THIRD_PARTY_NOTICES.md). No Mojang asset is bundled
 * with the addon.
 *
 * <p>The icons are made once per JVM, on first use. When that fails the layers carry no icon and
 * BlueMap draws its default marker, which is logged once and is the same on every instance.
 */
public final class ClientAssetIcons {

    private final Supplier<Optional<Path>> clientJar;
    private Map<String, byte[]> pngByLayer;

    /** @param clientJar finds the client jar, slowly if it has to be downloaded ({@link ClientJar#ensure()}) */
    public ClientAssetIcons(Supplier<Optional<Path>> clientJar) {
        this.clientJar = clientJar;
    }

    /** The PNG of each icon by layer id; layers whose texture is not available are left out. */
    public synchronized Map<String, byte[]> pngByLayer() {
        if (pngByLayer == null) {
            pngByLayer = clientJar.get().map(ClientAssetIcons::composeOrNone).orElse(Map.of());
        }
        return pngByLayer;
    }

    private static Map<String, byte[]> composeOrNone(Path jar) {
        try {
            return compose(jar);
        } catch (IOException e) {
            Log.warn("cannot make the layer icons from " + jar + ": " + e);
            return Map.of();
        }
    }

    static Map<String, byte[]> compose(Path jar) throws IOException {
        Map<String, byte[]> icons = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Layer layer : LayerCatalog.layers()) {
                if (layer.icon() != null) {
                    icon(zip, layer).ifPresent(png -> icons.put(layer.id(), png));
                }
            }
        }
        return icons;
    }

    private static Optional<byte[]> icon(ZipFile zip, Layer layer) throws IOException {
        ZipEntry entry = zip.getEntry(layer.icon());
        if (entry == null) {
            Log.warn("the client jar has no texture " + layer.icon() + " for the " + layer.label() + " icon");
            return Optional.empty();
        }
        BufferedImage texture;
        try (InputStream in = zip.getInputStream(entry)) {
            texture = ImageIO.read(in);
        }
        if (texture == null) {
            Log.warn("cannot decode the texture " + layer.icon() + " for the " + layer.label() + " icon");
            return Optional.empty();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(IconComposer.compose(texture), "png", png);
        return Optional.of(png.toByteArray());
    }
}
