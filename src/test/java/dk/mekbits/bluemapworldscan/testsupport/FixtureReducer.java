package dk.mekbits.bluemapworldscan.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

/**
 * Copies only the tags the addon reads out of a raw chunk, so a real chunk of a private world can
 * be committed as a test fixture without player content (signs, books, named items in block
 * entities). Everything else is dropped: block entities, entities, block states, heightmaps,
 * ticks, lighting, carving masks, {@code fabric:attachments}, {@code structures.References},
 * {@code InhabitedTime} and {@code LastUpdate}.
 *
 * <p>Kept, root layout: {@code DataVersion}, {@code Status}, {@code status}, {@code xPos},
 * {@code zPos}, {@code below_zero_retrogen.target_status}, {@code structures.starts} (whole
 * subtree), {@code sections[].biomes} (whole). Kept, {@code Level} layout: {@code DataVersion},
 * {@code Level.Status}, {@code Level.xPos}, {@code Level.zPos}, {@code Level.Biomes},
 * {@code Level.below_zero_retrogen.target_status}, {@code Level.Structures.Starts} (whole
 * subtree), {@code Level.Sections[].biomes} (whole).
 * {@code FixtureHygieneTest} checks the committed result against the same list.
 */
public final class FixtureReducer {

    private FixtureReducer() {
    }

    /** Reduces one uncompressed raw chunk to the allow-listed tags, uncompressed. */
    public static byte[] reduce(byte[] rawNbt) throws IOException {
        CompoundBinaryTag root;
        try (InputStream in = new ByteArrayInputStream(rawNbt)) {
            root = BinaryTagIO.unlimitedReader().read(in);
        }

        CompoundBinaryTag.Builder reduced = CompoundBinaryTag.builder();
        copy(root, reduced, "DataVersion", "Status", "status", "xPos", "zPos");
        putPicked(reduced, "below_zero_retrogen", root.getCompound("below_zero_retrogen", null), "target_status");
        putPicked(reduced, "structures", root.getCompound("structures", null), "starts");
        putSections(reduced, "sections", root.getList("sections", BinaryTagTypes.COMPOUND, null));

        CompoundBinaryTag level = root.getCompound("Level", null);
        if (level != null) {
            CompoundBinaryTag.Builder reducedLevel = CompoundBinaryTag.builder();
            copy(level, reducedLevel, "Status", "xPos", "zPos", "Biomes");
            putPicked(reducedLevel, "below_zero_retrogen", level.getCompound("below_zero_retrogen", null), "target_status");
            putPicked(reducedLevel, "Structures", level.getCompound("Structures", null), "Starts");
            putSections(reducedLevel, "Sections", level.getList("Sections", BinaryTagTypes.COMPOUND, null));
            reduced.put("Level", reducedLevel.build());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(reduced.build(), out);
        return out.toByteArray();
    }

    /** Copies the named tags that exist. */
    private static void copy(CompoundBinaryTag from, CompoundBinaryTag.Builder to, String... keys) {
        for (String key : keys) {
            BinaryTag tag = from.get(key);
            if (tag != null) {
                to.put(key, tag);
            }
        }
    }

    /** Puts a compound with only the named tags of {@code from}, unless {@code from} is missing. */
    private static void putPicked(CompoundBinaryTag.Builder to, String key, CompoundBinaryTag from, String... keys) {
        if (from == null) {
            return;
        }
        CompoundBinaryTag.Builder picked = CompoundBinaryTag.builder();
        copy(from, picked, keys);
        to.put(key, picked.build());
    }

    /** Puts a list of the sections reduced to their {@code biomes} tag; a section without one stays an empty compound. */
    private static void putSections(CompoundBinaryTag.Builder to, String key, ListBinaryTag sections) {
        if (sections == null) {
            return;
        }
        ListBinaryTag.Builder<CompoundBinaryTag> reduced = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (BinaryTag section : sections) {
            CompoundBinaryTag.Builder picked = CompoundBinaryTag.builder();
            copy((CompoundBinaryTag) section, picked, "biomes");
            reduced.add(picked.build());
        }
        to.put(key, reduced.build());
    }

    /**
     * Reduces every {@code .nbt} file of the source directory into the target directory, under
     * the same file name. Other files (such as {@code level.dat.gz}) are skipped.
     *
     * @param args source directory, target directory
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: FixtureReducer <source dir> <target dir>");
        }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Files.createDirectories(target);

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source, "*.nbt")) {
            stream.forEach(files::add);
        }
        files.sort(null);

        for (Path file : files) {
            Files.write(target.resolve(file.getFileName().toString()), reduce(Files.readAllBytes(file)));
        }
        System.out.println("reduced " + files.size() + " chunks into " + target);
    }
}
