package dk.mekbits.bluemapworldscan.chunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the structure starts and biomes of one chunk, in the layouts of Minecraft 1.13 to 26.3.
 *
 * <p>A chunk is mostly block states, light and heightmaps, none of which the addon uses, so the
 * NBT is not built as a tree: it is walked once and only the tags below are read, everything else
 * is skipped in place ({@link NbtInput}). The tags are taken as the typed getters of adventure-nbt
 * would take them from the whole tree: a tag of another type than expected counts as missing, a
 * number of any type is an int (a float or double floored), and of two tags with the same name in
 * one compound the last counts.
 *
 * <p>Two layouts exist. Up to DataVersion 2841 everything sits under a {@code Level} compound; from
 * 2842 it sits in the root. Biomes are numbers per cell up to DataVersion 2831
 * ({@code Level.Biomes}) and palettes of names per section from 2832.
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

    private static final byte[] DATA_VERSION = ascii("DataVersion");
    private static final byte[] LEVEL = ascii("Level");
    private static final byte[] STATUS = ascii("Status");
    private static final byte[] LOWER_CASE_STATUS = ascii("status");
    private static final byte[] RETROGEN = ascii("below_zero_retrogen");
    private static final byte[] TARGET_STATUS = ascii("target_status");
    private static final byte[] X_POS = ascii("xPos");
    private static final byte[] Z_POS = ascii("zPos");
    private static final byte[] NUMERIC_BIOMES = ascii("Biomes");
    private static final byte[] BIOMES = ascii("biomes");
    private static final byte[] PALETTE = ascii("palette");
    private static final byte[] ID = ascii("id");
    private static final byte[] CHILDREN = ascii("Children");
    private static final byte[] BOX = ascii("BB");
    private static final byte[] TEMPLATE = ascii("Template");

    /** The tag names that differ between the two layouts; only the root layout also reads a lower-case {@code status}. */
    private record Layout(byte[] sections, byte[] structures, byte[] starts, boolean lowerCaseStatus) {
    }

    private static final Layout LEVEL_LAYOUT = new Layout(ascii("Sections"), ascii("Structures"), ascii("Starts"), false);
    private static final Layout ROOT_LAYOUT = new Layout(ascii("sections"), ascii("structures"), ascii("starts"), true);

    /** The tags read from the compound that holds the chunk: the root, or {@code Level}. */
    private static final class ChunkTags {
        @Nullable String status;
        @Nullable String lowerCaseStatus;
        @Nullable String retrogenTarget;
        int x = NO_POSITION;
        int z = NO_POSITION;
        int @Nullable [] numericBiomes;
        List<List<String>> palettes = List.of();
        Map<String, Start> starts = Map.of();
    }

    /** A structure start as stored: its id and its pieces. */
    private record Start(@Nullable String id, List<Piece> pieces) {
    }

    private record Piece(int @Nullable [] box, @Nullable String template) {
    }

    private ChunkReader() {
    }

    /**
     * Reads a chunk from its decompressed NBT bytes.
     *
     * @throws IOException when the chunk cannot be read: truncated or malformed NBT, a document
     *     over {@link #MAX_NBT_BYTES}, or a root that is not a compound
     */
    public static ChunkInfo read(InputStream nbt) throws IOException {
        byte[] bytes = nbt.readNBytes((int) MAX_NBT_BYTES + 1);
        return read(bytes, bytes.length);
    }

    /**
     * Reads a chunk from the first {@code length} bytes of {@code nbt}, its decompressed NBT; see
     * {@link #read(InputStream)}. The array is not kept.
     */
    public static ChunkInfo read(byte[] nbt, int length) throws IOException {
        if (length > MAX_NBT_BYTES) {
            throw new IOException("the chunk NBT is larger than " + MAX_NBT_BYTES + " bytes");
        }
        try {
            return walk(nbt, length);
        } catch (RuntimeException e) {
            // One chunk the reader cannot make sense of must not end the scan of the world.
            throw new IOException("unreadable chunk NBT: " + e, e);
        }
    }

    private static ChunkInfo walk(byte[] nbt, int length) throws IOException {
        NbtInput in = new NbtInput(nbt, length);
        if (in.readType() != NbtInput.COMPOUND) {
            throw new IOException("the root of the chunk NBT is not a compound");
        }
        in.readName();

        int dataVersion = -1;
        ChunkTags root = new ChunkTags();
        ChunkTags level = null;
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            if (in.nameIs(DATA_VERSION)) {
                dataVersion = NbtInput.isNumber(type) ? in.readNumberAsInt(type) : skipTo(in, type, -1);
            } else if (in.nameIs(LEVEL)) {
                level = type == NbtInput.COMPOUND ? chunkTags(in, LEVEL_LAYOUT) : skipTo(in, type, null);
            } else {
                chunkTag(in, type, ROOT_LAYOUT, root);
            }
        }
        in.exit();

        if (dataVersion < FIRST_1_13_DATA_VERSION) {
            return new ChunkInfo(dataVersion, Kind.PRE_1_13, NO_POSITION, NO_POSITION, List.of(), Set.of(), Set.of(), Set.of());
        }
        return chunkInfo(dataVersion, level != null ? level : root, level != null ? LEVEL_LAYOUT : ROOT_LAYOUT);
    }

    private static ChunkInfo chunkInfo(int dataVersion, ChunkTags chunk, Layout layout) {
        String status = chunk.status;
        if (status == null && layout.lowerCaseStatus()) {
            status = chunk.lowerCaseStatus;
        }

        Set<String> unknownStructureIds = new TreeSet<>();
        List<FoundStructure> structures = structures(chunk.starts, dataVersion, unknownStructureIds);

        Set<String> biomes = new TreeSet<>();
        Set<String> unknownBiomeIds = new TreeSet<>();
        if (ChunkStatuses.biomesGenerated(status, chunk.retrogenTarget, dataVersion)) {
            if (dataVersion < FIRST_PALETTE_BIOMES_DATA_VERSION) {
                numericBiomes(chunk.numericBiomes, dataVersion, biomes, unknownBiomeIds);
            } else {
                paletteBiomes(chunk.palettes, dataVersion, biomes, unknownBiomeIds);
            }
        }

        return new ChunkInfo(dataVersion, Kind.MODERN, chunk.x, chunk.z, structures, biomes, unknownStructureIds, unknownBiomeIds);
    }

    // ---- walking the NBT

    /** Reads the tags of a chunk compound (the root's own tags are read one by one with {@link #chunkTag}). */
    private static ChunkTags chunkTags(NbtInput in, Layout layout) throws IOException {
        ChunkTags tags = new ChunkTags();
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            chunkTag(in, type, layout, tags);
        }
        in.exit();
        return tags;
    }

    /** One tag of a chunk compound whose name was just read: kept in {@code tags} when it is one the addon reads. */
    private static void chunkTag(NbtInput in, int type, Layout layout, ChunkTags tags) throws IOException {
        if (in.nameIs(STATUS)) {
            tags.status = string(in, type);
        } else if (layout.lowerCaseStatus() && in.nameIs(LOWER_CASE_STATUS)) {
            tags.lowerCaseStatus = string(in, type);
        } else if (in.nameIs(RETROGEN)) {
            tags.retrogenTarget = type == NbtInput.COMPOUND ? retrogenTarget(in) : skipTo(in, type, null);
        } else if (in.nameIs(X_POS)) {
            tags.x = NbtInput.isNumber(type) ? in.readNumberAsInt(type) : skipTo(in, type, NO_POSITION);
        } else if (in.nameIs(Z_POS)) {
            tags.z = NbtInput.isNumber(type) ? in.readNumberAsInt(type) : skipTo(in, type, NO_POSITION);
        } else if (in.nameIs(NUMERIC_BIOMES)) {
            tags.numericBiomes = type == NbtInput.INT_ARRAY ? in.readIntArray() : skipTo(in, type, null);
        } else if (in.nameIs(layout.sections())) {
            tags.palettes = type == NbtInput.LIST ? sectionPalettes(in) : skipTo(in, type, List.of());
        } else if (in.nameIs(layout.structures())) {
            tags.starts = type == NbtInput.COMPOUND ? starts(in, layout) : skipTo(in, type, Map.of());
        } else {
            in.skip(type);
        }
    }

    /** {@code below_zero_retrogen.target_status}, or null. */
    private static @Nullable String retrogenTarget(NbtInput in) throws IOException {
        String target = null;
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            if (in.nameIs(TARGET_STATUS)) {
                target = string(in, type);
            } else {
                in.skip(type);
            }
        }
        in.exit();
        return target;
    }

    /** The biome palette of each section in a list of sections: empty for a section without one, and no sections when the list holds no compounds. */
    private static List<List<String>> sectionPalettes(NbtInput in) throws IOException {
        in.enter();
        int elementType = in.readListType();
        int count = in.readListLength(elementType);
        if (elementType != NbtInput.COMPOUND) {
            in.skipElements(elementType, count);
            in.exit();
            return List.of();
        }
        List<List<String>> palettes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<String> palette = List.of();
            in.enter();
            for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
                in.readName();
                if (in.nameIs(BIOMES)) {
                    palette = type == NbtInput.COMPOUND ? biomePalette(in) : skipTo(in, type, List.of());
                } else {
                    in.skip(type);
                }
            }
            in.exit();
            palettes.add(palette);
        }
        in.exit();
        return palettes;
    }

    /** {@code biomes.palette} of a section: its names, or empty. */
    private static List<String> biomePalette(NbtInput in) throws IOException {
        List<String> palette = List.of();
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            if (in.nameIs(PALETTE)) {
                palette = type == NbtInput.LIST ? strings(in) : skipTo(in, type, List.of());
            } else {
                in.skip(type);
            }
        }
        in.exit();
        return palette;
    }

    /** A list of strings, or empty when the list holds another type. */
    private static List<String> strings(NbtInput in) throws IOException {
        in.enter();
        int elementType = in.readListType();
        int count = in.readListLength(elementType);
        if (elementType != NbtInput.STRING) {
            in.skipElements(elementType, count);
            in.exit();
            return List.of();
        }
        List<String> strings = new ArrayList<>(Math.min(count, MAX_BIOME_PALETTE + 1));
        for (int i = 0; i < count; i++) {
            strings.add(in.readString());
        }
        in.exit();
        return strings;
    }

    /** The structure starts of a {@code structures} compound, by key. */
    private static Map<String, Start> starts(NbtInput in, Layout layout) throws IOException {
        Map<String, Start> starts = Map.of();
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            if (in.nameIs(layout.starts())) {
                starts = type == NbtInput.COMPOUND ? startsByKey(in) : skipTo(in, type, Map.of());
            } else {
                in.skip(type);
            }
        }
        in.exit();
        return starts;
    }

    private static Map<String, Start> startsByKey(NbtInput in) throws IOException {
        // Sorted, so the same chunk always gives the same list.
        Map<String, Start> starts = new TreeMap<>();
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            String key = in.nameAsString();
            if (type == NbtInput.COMPOUND) {
                starts.put(key, start(in));
            } else {
                starts.remove(key);
                in.skip(type);
            }
        }
        in.exit();
        return starts;
    }

    private static Start start(NbtInput in) throws IOException {
        String id = null;
        List<Piece> pieces = List.of();
        in.enter();
        for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
            in.readName();
            if (in.nameIs(ID)) {
                id = string(in, type);
            } else if (in.nameIs(CHILDREN)) {
                pieces = type == NbtInput.LIST ? pieces(in) : skipTo(in, type, List.of());
            } else {
                in.skip(type);
            }
        }
        in.exit();
        return new Start(id, pieces);
    }

    private static List<Piece> pieces(NbtInput in) throws IOException {
        in.enter();
        int elementType = in.readListType();
        int count = in.readListLength(elementType);
        if (elementType != NbtInput.COMPOUND) {
            in.skipElements(elementType, count);
            in.exit();
            return List.of();
        }
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] box = null;
            String template = null;
            in.enter();
            for (int type = in.readType(); type != NbtInput.END; type = in.readType()) {
                in.readName();
                if (in.nameIs(BOX)) {
                    box = type == NbtInput.INT_ARRAY ? in.readIntArray() : skipTo(in, type, null);
                } else if (in.nameIs(TEMPLATE)) {
                    template = string(in, type);
                } else {
                    in.skip(type);
                }
            }
            in.exit();
            pieces.add(new Piece(box, template));
        }
        in.exit();
        return pieces;
    }

    /** The string of a tag, or null (the tag skipped) when it is not a string. */
    private static @Nullable String string(NbtInput in, int type) throws IOException {
        return type == NbtInput.STRING ? in.readString() : skipTo(in, type, null);
    }

    /** Skips a tag of an unexpected type and gives what a missing tag gives. */
    private static <T> T skipTo(NbtInput in, int type, T missing) throws IOException {
        in.skip(type);
        return missing;
    }

    private static byte[] ascii(String name) {
        return name.getBytes(StandardCharsets.US_ASCII);
    }

    // ---- what the tags mean

    private static List<FoundStructure> structures(Map<String, Start> starts, int dataVersion, Set<String> unknownIds) {
        List<FoundStructure> found = new ArrayList<>();
        for (Map.Entry<String, Start> start : starts.entrySet()) {
            FoundStructure structure = readStart(start.getKey(), start.getValue(), dataVersion);
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
    private static @Nullable FoundStructure readStart(String key, Start start, int dataVersion) {
        if (start.id() == null || start.id().equals(INVALID_START)) {
            return null;
        }

        int[] box = null;
        int[] shipBox = null;
        for (Piece piece : start.pieces()) {
            int[] pieceBox = piece.box();
            if (pieceBox == null || pieceBox.length != BOX_LENGTH) {
                continue;
            }
            box = box == null ? pieceBox.clone() : union(box, pieceBox);
            String template = piece.template();
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
    private static void numericBiomes(int @Nullable [] cells, int dataVersion, Set<String> biomes, Set<String> unknownIds) {
        if (cells == null) {
            return;
        }
        // The array is this reader's own, so it is sorted in place to visit each number once.
        Arrays.sort(cells);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0 && cells[i] == cells[i - 1]) {
                continue;
            }
            String id = BiomeIds.current(null, cells[i], dataVersion);
            if (id == null) {
                unknownIds.add(Integer.toString(cells[i]));
            } else {
                biomes.add(id);
            }
        }
    }

    /** {@code sections[].biomes.palette}: the names used in each section. */
    private static void paletteBiomes(List<List<String>> palettes, int dataVersion, Set<String> biomes, Set<String> unknownIds) {
        for (List<String> palette : palettes) {
            if (palette.size() > MAX_BIOME_PALETTE) {
                continue;
            }
            for (String name : palette) {
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
