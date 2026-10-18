package dk.mekbits.bluemapworldscan.testsupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

/** The reduced real chunks in {@code src/test/resources/chunks}, by file name without {@code .nbt}. */
public final class ChunkFixtures {

    private ChunkFixtures() {
    }

    /** The NBT bytes of a fixture. */
    public static byte[] bytes(String name) {
        try (InputStream in = ChunkFixtures.class.getResourceAsStream("/chunks/" + name + ".nbt")) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture named " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The NBT tree of a fixture, for assertions that must not use the code under test. */
    public static CompoundBinaryTag tree(String name) {
        try {
            return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(bytes(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The names of all fixtures, sorted. */
    public static List<String> names() {
        try {
            Path folder = Path.of(ChunkFixtures.class.getResource("/chunks").toURI());
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.nbt")) {
                for (Path file : files) {
                    String fileName = file.getFileName().toString();
                    names.add(fileName.substring(0, fileName.length() - ".nbt".length()));
                }
            }
            names.sort(null);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
