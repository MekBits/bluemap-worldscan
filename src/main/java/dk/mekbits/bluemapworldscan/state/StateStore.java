package dk.mekbits.bluemapworldscan.state;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import dk.mekbits.bluemapworldscan.util.Loaded;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/**
 * Reads and writes a {@link ScanState} as gzip NBT in the addon's own folder.
 *
 * <p>Layout of the root compound: {@code formatVersion} int, {@code worldId} string and
 * {@code regions}, a compound of region file name to a compound of {@code timestamps} int[1024],
 * {@code structures} (a list of compounds with {@code chunk} int, {@code id} string, {@code box}
 * int[6] and, only for an end city with a ship, {@code ship} int[6]) and {@code biomes}, a compound
 * of biome id to long[16]. The state is mapped by hand: the records carry no annotations.
 */
public final class StateStore {

    private static final int CHUNKS = 1024;
    private static final int BIOME_WORDS = CHUNKS / Long.SIZE;
    private static final int BOX_LENGTH = 6;

    private StateStore() {
    }

    /**
     * Loads the state stored in a file.
     *
     * @return {@code Missing} when there is no file; {@code Failed} when it cannot be read or
     *     parsed, has a required tag missing or of another type, an array of another length,
     *     another {@code formatVersion} or another {@code worldId}; else {@code Ok}
     */
    public static Loaded<ScanState> load(Path file, String worldId) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            // The file is the addon's own, written atomically: its size is no reason to refuse it.
            CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(in, BinaryTagIO.Compression.GZIP);
            return new Loaded.Ok<>(parse(root, worldId));
        } catch (NoSuchFileException e) {
            return new Loaded.Missing<>();
        } catch (InvalidStateException e) {
            return new Loaded.Failed<>(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return new Loaded.Failed<>("the scan state cannot be read: " + e);
        }
    }

    /**
     * Writes the state to {@code <file>.tmp} and moves it over the file atomically, so a write
     * cut off by a shutdown never damages the stored state. Missing parent folders are created.
     */
    public static void save(ScanState state, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                BinaryTagIO.writer().write(toTag(state), out, BinaryTagIO.Compression.GZIP);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    // ---- writing

    private static CompoundBinaryTag toTag(ScanState state) {
        CompoundBinaryTag.Builder regions = CompoundBinaryTag.builder();
        for (Map.Entry<String, RegionEntry> region : state.regions().entrySet()) {
            regions.put(region.getKey(), regionToTag(region.getValue()));
        }
        return CompoundBinaryTag.builder()
                .putInt("formatVersion", state.formatVersion())
                .putString("worldId", state.worldId())
                .put("regions", regions.build())
                .build();
    }

    private static CompoundBinaryTag regionToTag(RegionEntry region) {
        ListBinaryTag.Builder<CompoundBinaryTag> structures = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (StructureEntry structure : region.structures()) {
            CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder()
                    .putInt("chunk", structure.chunkIndex())
                    .putString("id", structure.structureId())
                    .putIntArray("box", structure.box());
            if (structure.endShipBox() != null) {
                tag.putIntArray("ship", structure.endShipBox());
            }
            structures.add(tag.build());
        }
        CompoundBinaryTag.Builder biomes = CompoundBinaryTag.builder();
        for (Map.Entry<String, long[]> biome : region.biomes().entrySet()) {
            biomes.putLongArray(biome.getKey(), biome.getValue());
        }
        return CompoundBinaryTag.builder()
                .putIntArray("timestamps", region.timestamps())
                .put("structures", structures.build())
                .put("biomes", biomes.build())
                .build();
    }

    // ---- reading

    /** A stored state that cannot be used; the message is the reason. */
    private static final class InvalidStateException extends Exception {
        InvalidStateException(String message) {
            super(message);
        }
    }

    private static ScanState parse(CompoundBinaryTag root, String worldId) throws InvalidStateException {
        int formatVersion = integer(root, "formatVersion");
        if (formatVersion != ScanState.FORMAT_VERSION) {
            throw new InvalidStateException("the scan state has format version " + formatVersion
                    + ", this version of the addon reads " + ScanState.FORMAT_VERSION);
        }
        String storedWorldId = string(root, "worldId");
        if (!storedWorldId.equals(worldId)) {
            throw new InvalidStateException("the scan state belongs to another world");
        }
        CompoundBinaryTag regionTags = compound(root, "regions");
        SortedMap<String, RegionEntry> regions = new TreeMap<>();
        for (String name : new TreeSet<>(regionTags.keySet())) {
            regions.put(name, parseRegion(name, compound(regionTags, name)));
        }
        return new ScanState(formatVersion, storedWorldId, regions);
    }

    private static RegionEntry parseRegion(String name, CompoundBinaryTag region) throws InvalidStateException {
        int[] timestamps = intArray(region, "timestamps", CHUNKS, name);

        ListBinaryTag structureTags = list(region, "structures", name);
        List<StructureEntry> structures = new ArrayList<>(structureTags.size());
        for (BinaryTag element : structureTags) {
            if (!(element instanceof CompoundBinaryTag structure)) {
                throw new InvalidStateException("a structure of " + name + " is not a compound");
            }
            int chunk = integer(structure, "chunk");
            if (chunk < 0 || chunk >= CHUNKS) {
                throw new InvalidStateException("a structure of " + name + " has chunk index " + chunk);
            }
            int[] ship = structure.get("ship") == null ? null : intArray(structure, "ship", BOX_LENGTH, name);
            structures.add(new StructureEntry(
                    chunk, string(structure, "id"), intArray(structure, "box", BOX_LENGTH, name), ship));
        }

        CompoundBinaryTag biomeTags = compound(region, "biomes");
        SortedMap<String, long[]> biomes = new TreeMap<>();
        for (String biome : new TreeSet<>(biomeTags.keySet())) {
            if (!(biomeTags.get(biome) instanceof LongArrayBinaryTag bits) || bits.size() != BIOME_WORDS) {
                throw new InvalidStateException("biome " + biome + " of " + name + " is not a long[" + BIOME_WORDS + "]");
            }
            biomes.put(biome, bits.value());
        }
        return new RegionEntry(timestamps, structures, biomes);
    }

    private static int integer(CompoundBinaryTag parent, String key) throws InvalidStateException {
        if (parent.get(key) instanceof IntBinaryTag tag) {
            return tag.value();
        }
        throw new InvalidStateException("the scan state has no int tag " + key);
    }

    private static String string(CompoundBinaryTag parent, String key) throws InvalidStateException {
        if (parent.get(key) instanceof StringBinaryTag tag) {
            return tag.value();
        }
        throw new InvalidStateException("the scan state has no string tag " + key);
    }

    private static CompoundBinaryTag compound(CompoundBinaryTag parent, String key) throws InvalidStateException {
        if (parent.get(key) instanceof CompoundBinaryTag tag) {
            return tag;
        }
        throw new InvalidStateException("the scan state has no compound tag " + key);
    }

    private static ListBinaryTag list(CompoundBinaryTag parent, String key, String region) throws InvalidStateException {
        if (parent.get(key) instanceof ListBinaryTag tag
                && (tag.size() == 0 || tag.elementType() == BinaryTagTypes.COMPOUND)) {
            return tag;
        }
        throw new InvalidStateException("region " + region + " has no list of compounds " + key);
    }

    private static int[] intArray(CompoundBinaryTag parent, String key, int length, String region)
            throws InvalidStateException {
        if (parent.get(key) instanceof IntArrayBinaryTag tag && tag.size() == length) {
            return tag.value();
        }
        throw new InvalidStateException("region " + region + " has no int[" + length + "] " + key);
    }
}
