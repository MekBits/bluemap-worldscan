package dk.mekbits.bluemapworldscan.chunk;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the structure starts and biomes of one chunk, in the layouts of Minecraft 1.13 to 26.3.
 *
 * <p>The chunk is read as a whole NBT tree and walked with typed getters: a tag of another type
 * than expected counts as missing. Two layouts exist. Up to DataVersion 2841 everything sits under
 * a {@code Level} compound; from 2842 it sits in the root. Biomes are numbers per cell up to
 * DataVersion 2831 ({@code Level.Biomes}) and palettes of names per section from 2832.
 *
 * <p>A structure start or biome whose id is not a valid resource location is skipped, not the
 * whole chunk: an id ends up in the stored layers and in the log, so only what Minecraft accepts
 * as an id gets through. A section whose biome palette has more than 64 names (a section holds
 * 64 biome cells) is malformed in the same way: its biomes are skipped, the rest of the chunk is kept.
 */
public final class ChunkReader {

    /** The largest NBT document accepted; also bounds what a corrupt length may make the reader allocate. */
    public static final long MAX_NBT_BYTES = 16L << 20;

    private static final int FIRST_1_13_DATA_VERSION = 1466;
    private static final int FIRST_PALETTE_BIOMES_DATA_VERSION = 2832;
    private static final int NO_POSITION = Integer.MIN_VALUE;
    private static final int BOX_LENGTH = 6;
    /** A section's biomes are 4x4x4 cells, so a palette of more than 64 names is not one the game writes. */
    static final int MAX_BIOME_PALETTE = 64;
    /** The placeholder id old chunks store for every structure type that has no start in them. */
    private static final String INVALID_START = "INVALID";
    private static final String END_CITY_SHIP_TEMPLATE = "ship";

    /** The tag names that differ between the two layouts. */
    private record Layout(String sections, String structures, String starts) {
    }

    private static final Layout LEVEL_LAYOUT = new Layout("Sections", "Structures", "Starts");
    private static final Layout ROOT_LAYOUT = new Layout("sections", "structures", "starts");

    private ChunkReader() {
    }

    /**
     * Reads a chunk from its decompressed NBT bytes.
     *
     * @throws IOException when the chunk cannot be read: truncated or malformed NBT, a document
     *     over {@link #MAX_NBT_BYTES}, or a root that is not a compound
     */
    public static ChunkInfo read(InputStream nbt) throws IOException {
        try {
            return walk(BinaryTagIO.reader(MAX_NBT_BYTES).read(nbt));
        } catch (RuntimeException e) {
            // adventure-nbt reports some malformed input (an unknown tag id, a negative length) unchecked.
            throw new IOException("unreadable chunk NBT: " + e, e);
        }
    }

    private static ChunkInfo walk(CompoundBinaryTag root) {
        int dataVersion = root.getInt("DataVersion", -1);
        if (dataVersion < FIRST_1_13_DATA_VERSION) {
            return new ChunkInfo(dataVersion, Kind.PRE_1_13, NO_POSITION, NO_POSITION, List.of(), Set.of(), Set.of(), Set.of());
        }

        CompoundBinaryTag level = root.getCompound("Level", null);
        Layout layout = level != null ? LEVEL_LAYOUT : ROOT_LAYOUT;
        CompoundBinaryTag chunk = level != null ? level : root;

        String status = chunk.getString("Status", null);
        if (status == null && layout == ROOT_LAYOUT) {
            status = chunk.getString("status", null);
        }
        CompoundBinaryTag retrogen = chunk.getCompound("below_zero_retrogen", null);
        String retrogenTarget = retrogen != null ? retrogen.getString("target_status", null) : null;

        Set<String> unknownStructureIds = new TreeSet<>();
        List<FoundStructure> structures = structures(chunk, layout, dataVersion, unknownStructureIds);

        Set<String> biomes = new TreeSet<>();
        Set<String> unknownBiomeIds = new TreeSet<>();
        if (ChunkStatuses.biomesGenerated(status, retrogenTarget, dataVersion)) {
            if (dataVersion < FIRST_PALETTE_BIOMES_DATA_VERSION) {
                numericBiomes(chunk, dataVersion, biomes, unknownBiomeIds);
            } else {
                paletteBiomes(chunk, layout, dataVersion, biomes, unknownBiomeIds);
            }
        }

        return new ChunkInfo(
                dataVersion,
                Kind.MODERN,
                chunk.getInt("xPos", NO_POSITION),
                chunk.getInt("zPos", NO_POSITION),
                structures,
                biomes,
                unknownStructureIds,
                unknownBiomeIds);
    }

    private static List<FoundStructure> structures(
            CompoundBinaryTag chunk, Layout layout, int dataVersion, Set<String> unknownIds) {
        CompoundBinaryTag starts = chunk.getCompound(layout.structures(), CompoundBinaryTag.empty())
                .getCompound(layout.starts(), CompoundBinaryTag.empty());

        List<FoundStructure> found = new ArrayList<>();
        // keySet() has no stable order; sorting makes the same chunk always give the same list.
        for (String key : new TreeSet<>(starts.keySet())) {
            CompoundBinaryTag start = starts.getCompound(key, null);
            FoundStructure structure = start == null ? null : readStart(key, start, dataVersion);
            if (structure != null) {
                found.add(structure);
                if (!StructureIds.known(structure.structureId())) {
                    unknownIds.add(structure.structureId());
                }
            }
        }
        return found;
    }

    /**
     * One structure start, or null when it is not one the game would load: the placeholder old
     * chunks store for structures without a start, a start without an id, or one without a piece
     * that has a bounding box, or one whose id is not a valid resource location.
     */
    private static @Nullable FoundStructure readStart(String key, CompoundBinaryTag start, int dataVersion) {
        String id = start.getString("id", null);
        if (id == null || id.equals(INVALID_START)) {
            return null;
        }

        int[] box = null;
        int[] shipBox = null;
        ListBinaryTag pieces = start.getList("Children", BinaryTagTypes.COMPOUND, ListBinaryTag.empty());
        for (int i = 0; i < pieces.size(); i++) {
            CompoundBinaryTag piece = pieces.getCompound(i);
            int[] pieceBox = piece.getIntArray("BB", null);
            if (pieceBox == null || pieceBox.length != BOX_LENGTH) {
                continue;
            }
            box = box == null ? pieceBox.clone() : union(box, pieceBox);
            String template = piece.getString("Template", null);
            if (shipBox == null && template != null
                    && ResourceNames.withoutNamespace(template).equals(END_CITY_SHIP_TEMPLATE)) {
                shipBox = pieceBox;
            }
        }
        if (box == null) {
            return null;
        }

        String structureId = StructureIds.current(key, dataVersion);
        if (!ResourceNames.isValid(structureId)) {
            return null;
        }
        return new FoundStructure(structureId, box, structureId.equals(StructureIds.END_CITY) ? shipBox : null);
    }

    private static int[] union(int[] a, int[] b) {
        return new int[] {
            Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
            Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])
        };
    }

    /** {@code Biomes}: one number per cell, read as the set of distinct numbers. */
    private static void numericBiomes(CompoundBinaryTag chunk, int dataVersion, Set<String> biomes, Set<String> unknownIds) {
        int[] cells = chunk.getIntArray("Biomes", null);
        if (cells == null) {
            return;
        }
        IntStream.of(cells).distinct().sorted().forEach(number -> {
            String id = BiomeIds.current(null, number, dataVersion);
            if (id == null) {
                unknownIds.add(Integer.toString(number));
            } else {
                biomes.add(id);
            }
        });
    }

    /** {@code sections[].biomes.palette}: the names used in each section. */
    private static void paletteBiomes(
            CompoundBinaryTag chunk, Layout layout, int dataVersion, Set<String> biomes, Set<String> unknownIds) {
        ListBinaryTag sections = chunk.getList(layout.sections(), BinaryTagTypes.COMPOUND, ListBinaryTag.empty());
        for (int i = 0; i < sections.size(); i++) {
            ListBinaryTag palette = sections.getCompound(i)
                    .getCompound("biomes", CompoundBinaryTag.empty())
                    .getList("palette", BinaryTagTypes.STRING, ListBinaryTag.empty());
            if (palette.size() > MAX_BIOME_PALETTE) {
                continue;
            }
            for (int j = 0; j < palette.size(); j++) {
                String name = palette.getString(j);
                if (name.isBlank()) {
                    continue;
                }
                String id = BiomeIds.current(name, -1, dataVersion);
                if (!ResourceNames.isValid(id)) {
                    continue;
                }
                biomes.add(id);
                if (!BiomeIds.known(id)) {
                    unknownIds.add(id);
                }
            }
        }
    }
}
