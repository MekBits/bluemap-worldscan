package dk.mekbits.bluemapworldscan.config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import dk.mekbits.bluemapworldscan.WorldScanAddon;
import dk.mekbits.bluemapworldscan.util.Sha1;

/**
 * The folder the addon keeps its own files in: {@code bluemap-worldscan/} beside BlueMap's
 * {@code packs/} folder. BlueMap treats every file in {@code packs/} as a resource pack and its API
 * offers no folder for addons, so the folder is found from the location of the addon's jar.
 *
 * <p>It holds {@code config.json}, the {@code reset} file, {@code scanner-id}, {@code state/} (the
 * scan state per world) and {@code client-jar/} (the Minecraft client jar the icons are made from).
 */
public final class AddonFolder {

    private static final String FOLDER_NAME = "bluemap-worldscan";
    private static final String RESET_FILE = "reset";
    private static final String SCANNER_ID_FILE = "scanner-id";
    private static final String STATE_FOLDER = "state";
    private static final String STATE_SUFFIX = ".nbt.gz";
    private static final String CLIENT_JAR_FOLDER = "client-jar";
    private static final int STATE_NAME_HEX_DIGITS = 16;

    private AddonFolder() {
    }

    /** The folder of the running addon, from the jar that holds {@link WorldScanAddon}. */
    public static Path resolve() {
        try {
            var location = WorldScanAddon.class.getProtectionDomain().getCodeSource().getLocation();
            return fromJar(Path.of(location.toURI()));
        } catch (URISyntaxException | RuntimeException e) {
            throw new IllegalStateException("cannot find the jar the addon was loaded from", e);
        }
    }

    /** The addon folder for a jar in {@code <bluemap config>/packs/}: {@code <bluemap config>/bluemap-worldscan}. */
    public static Path fromJar(Path jar) {
        Path packs = jar.toAbsolutePath().getParent();
        return packs.resolveSibling(FOLDER_NAME);
    }

    /**
     * The id that tells this installation's scanner from another one's in a layers manifest. It
     * is a random UUID written on first use.
     */
    public static String scannerId(Path folder) throws IOException {
        Path file = folder.resolve(SCANNER_ID_FILE);
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        }
        String id = UUID.randomUUID().toString();
        Files.createDirectories(folder);
        Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
        return id;
    }

    /** The scan state file of a world: {@code state/<first 16 hex of sha1(world id)>.nbt.gz}. */
    public static Path stateFile(Path folder, String worldId) {
        return folder.resolve(STATE_FOLDER).resolve(Sha1.hex(worldId).substring(0, STATE_NAME_HEX_DIGITS) + STATE_SUFFIX);
    }

    /** Where the Minecraft client jar is cached. */
    public static Path clientJarFolder(Path folder) {
        return folder.resolve(CLIENT_JAR_FOLDER);
    }

    /**
     * Carries out a requested reset: when the {@code reset} file exists, deletes it and the stored
     * scan states, so the next scan starts from zero.
     *
     * @return whether a reset was requested
     */
    public static boolean consumeReset(Path folder) throws IOException {
        Path reset = folder.resolve(RESET_FILE);
        if (!Files.exists(reset)) {
            return false;
        }
        Path states = folder.resolve(STATE_FOLDER);
        if (Files.isDirectory(states)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(states)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(STATE_SUFFIX) || name.endsWith(STATE_SUFFIX + ".tmp")) {
                        Files.delete(file);
                    }
                }
            }
        }
        Files.delete(reset);
        return true;
    }
}
