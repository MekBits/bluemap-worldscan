package dk.mekbits.bluemapworldscan.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/**
 * Builds the NBT of chunks for the formats no real chunk of the fixture world has (old snapshots,
 * odd statuses, malformed tags). Only the tags the addon reads are built.
 */
public final class SyntheticChunks {

    private SyntheticChunks() {
    }

    /** The uncompressed NBT bytes of a chunk, as stored inside a region file. */
    public static byte[] toBytes(CompoundBinaryTag root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BinaryTagIO.writer().write(root, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A structure piece with a template name (null for none) and a bounding box. */
    public static CompoundBinaryTag piece(String template, int... box) {
        CompoundBinaryTag.Builder piece = CompoundBinaryTag.builder()
                .putString("id", "minecraft:ecp")
                .putIntArray("BB", box);
        if (template != null) {
            piece.putString("Template", template);
        }
        return piece.build();
    }

    /** A structure start with the given id and pieces. */
    public static CompoundBinaryTag start(String id, CompoundBinaryTag... pieces) {
        ListBinaryTag.Builder<CompoundBinaryTag> children = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag piece : pieces) {
            children.add(piece);
        }
        return CompoundBinaryTag.builder()
                .putString("id", id)
                .put("Children", children.build())
                .build();
    }

    /** One section per palette, each holding only {@code biomes.palette}. */
    @SafeVarargs
    public static ListBinaryTag sections(List<String>... palettes) {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (List<String> palette : palettes) {
            ListBinaryTag.Builder<StringBinaryTag> names = ListBinaryTag.builder(BinaryTagTypes.STRING);
            for (String name : palette) {
                names.add(StringBinaryTag.stringBinaryTag(name));
            }
            sections.add(CompoundBinaryTag.builder()
                    .put("biomes", CompoundBinaryTag.builder().put("palette", names.build()).build())
                    .build());
        }
        return sections.build();
    }

    /** The box of the structure that {@link #chunk} puts in a chunk: the whole chunk, from y 60 to 70. */
    public static int[] chunkBox(int chunkX, int chunkZ) {
        return new int[] {chunkX * 16, 60, chunkZ * 16, chunkX * 16 + 15, 70, chunkZ * 16 + 15};
    }

    /**
     * A chunk of the given DataVersion at the given position: the Level layout up to 2841, the root
     * layout from 2842. Only the root layout carries the given biome and structures, each a start
     * with one piece that fills the chunk ({@link #chunkBox}).
     */
    public static byte[] chunk(int dataVersion, int chunkX, int chunkZ, String biome, String... structureIds) {
        boolean level = dataVersion <= 2841;
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder().putInt("DataVersion", dataVersion);
        CompoundBinaryTag.Builder content = level ? CompoundBinaryTag.builder() : root;
        content.putInt("xPos", chunkX)
                .putInt("zPos", chunkZ)
                .putString("Status", dataVersion < 1911 ? "fullchunk" : "full");
        if (level) {
            root.put("Level", content.build());
        } else {
            CompoundBinaryTag.Builder starts = CompoundBinaryTag.builder();
            for (String id : structureIds) {
                starts.put(id, start(id, piece(null, chunkBox(chunkX, chunkZ))));
            }
            content.put("structures", CompoundBinaryTag.builder().put("starts", starts.build()).build());
            content.put("sections", sections(List.of(biome)));
        }
        return toBytes(root.build());
    }
}
