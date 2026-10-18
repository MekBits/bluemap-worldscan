package dk.mekbits.bluemapworldscan.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;

/**
 * The committed chunk fixtures come from a private server and must hold only the tags the addon
 * reads (see {@link FixtureReducer}); no block entities, entities or other player content.
 */
class FixtureHygieneTest {

    private static final Set<String> ROOT = Set.of(
            "DataVersion", "Status", "status", "xPos", "zPos", "below_zero_retrogen", "structures", "sections", "Level");
    private static final Set<String> RETROGEN = Set.of("target_status");
    private static final Set<String> STRUCTURES = Set.of("starts");
    private static final Set<String> SECTION = Set.of("biomes");
    private static final Set<String> LEVEL = Set.of("Status", "xPos", "zPos", "Biomes", "Structures", "Sections");
    private static final Set<String> LEVEL_STRUCTURES = Set.of("Starts");

    @Test
    void everyCommittedFixtureHoldsOnlyAllowListedTags() throws IOException, URISyntaxException {
        Path chunks = Path.of(getClass().getResource("/chunks").toURI());
        List<String> violations = new ArrayList<>();
        int checked = 0;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(chunks, "*.nbt")) {
            for (Path file : files) {
                try (InputStream in = Files.newInputStream(file)) {
                    CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(in);
                    for (String violation : violations(root)) {
                        violations.add(file.getFileName() + ": " + violation);
                    }
                }
                checked++;
            }
        }

        assertTrue(checked > 0, "no fixtures found in src/test/resources/chunks");
        assertEquals(List.of(), violations);
    }

    @Test
    void theCheckFlagsATagOutsideTheAllowList() {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 4903)
                .put("block_entities", ListBinaryTag.empty())
                .build();

        assertEquals(List.of("block_entities"), violations(chunk));
    }

    /** Paths of the tags in {@code root} that are not on the allow-list. */
    private static List<String> violations(CompoundBinaryTag root) {
        List<String> violations = new ArrayList<>();
        check(root, ROOT, "", violations);
        check(root.getCompound("below_zero_retrogen", CompoundBinaryTag.empty()), RETROGEN, "below_zero_retrogen.", violations);
        check(root.getCompound("structures", CompoundBinaryTag.empty()), STRUCTURES, "structures.", violations);
        checkSections(root.getList("sections", BinaryTagTypes.COMPOUND, ListBinaryTag.empty()), "sections", violations);

        CompoundBinaryTag level = root.getCompound("Level", CompoundBinaryTag.empty());
        check(level, LEVEL, "Level.", violations);
        check(level.getCompound("Structures", CompoundBinaryTag.empty()), LEVEL_STRUCTURES, "Level.Structures.", violations);
        checkSections(level.getList("Sections", BinaryTagTypes.COMPOUND, ListBinaryTag.empty()), "Level.Sections", violations);
        return violations;
    }

    private static void checkSections(ListBinaryTag sections, String path, List<String> violations) {
        for (BinaryTag section : sections) {
            check((CompoundBinaryTag) section, SECTION, path + "[].", violations);
        }
    }

    private static void check(CompoundBinaryTag compound, Set<String> allowed, String prefix, List<String> violations) {
        for (String key : new TreeSet<>(compound.keySet())) {
            if (!allowed.contains(key)) {
                violations.add(prefix + key);
            }
        }
    }

    private static byte[] write(CompoundBinaryTag root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, out);
        return out.toByteArray();
    }

    private static CompoundBinaryTag read(byte[] nbt) throws IOException {
        return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(nbt));
    }

    private static final CompoundBinaryTag STARTS = CompoundBinaryTag.builder()
            .put("minecraft:monument", CompoundBinaryTag.builder()
                    .putString("id", "minecraft:monument")
                    .put("Children", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                            .add(CompoundBinaryTag.builder()
                                    .putIntArray("BB", new int[] {1, 2, 3, 4, 5, 6})
                                    .putString("id", "minecraft:omb")
                                    .build())
                            .build())
                    .build())
            .build();

    private static final CompoundBinaryTag BIOMES = CompoundBinaryTag.builder()
            .put("palette", ListBinaryTag.builder(BinaryTagTypes.STRING)
                    .add(StringBinaryTag.stringBinaryTag("minecraft:deep_ocean"))
                    .build())
            .build();

    private static CompoundBinaryTag blockEntity() {
        return CompoundBinaryTag.builder().putString("front_text", "a private sign").build();
    }

    @Test
    void rootLayoutKeepsOnlyTheAllowListedTags() throws IOException {
        CompoundBinaryTag raw = CompoundBinaryTag.builder()
                .putInt("DataVersion", 4903)
                .putString("Status", "minecraft:full")
                .putString("status", "minecraft:full")
                .putInt("xPos", -440)
                .putInt("zPos", -27)
                .putLong("LastUpdate", 99L)
                .putLong("InhabitedTime", 12345L)
                .put("block_entities", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(blockEntity()).build())
                .put("Heightmaps", CompoundBinaryTag.builder().putLongArray("WORLD_SURFACE", new long[] {1L}).build())
                .put("below_zero_retrogen", CompoundBinaryTag.builder()
                        .putString("target_status", "minecraft:full")
                        .putByteArray("missing_bedrock", new byte[] {1})
                        .build())
                .put("structures", CompoundBinaryTag.builder()
                        .put("starts", STARTS)
                        .put("References", CompoundBinaryTag.builder().putLongArray("minecraft:monument", new long[] {7L}).build())
                        .build())
                .put("sections", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                        .add(CompoundBinaryTag.builder()
                                .putByte("Y", (byte) 3)
                                .put("block_states", CompoundBinaryTag.builder().putLongArray("data", new long[] {5L}).build())
                                .put("biomes", BIOMES)
                                .putByteArray("BlockLight", new byte[] {1})
                                .build())
                        .build())
                .build();

        CompoundBinaryTag reduced = read(FixtureReducer.reduce(write(raw)));

        assertEquals(Set.of("DataVersion", "Status", "status", "xPos", "zPos", "below_zero_retrogen", "structures", "sections"),
                reduced.keySet());
        assertEquals(raw.get("DataVersion"), reduced.get("DataVersion"));
        assertEquals(raw.get("Status"), reduced.get("Status"));
        assertEquals(raw.get("xPos"), reduced.get("xPos"));
        assertEquals(raw.get("zPos"), reduced.get("zPos"));
        assertEquals(Set.of("target_status"), reduced.getCompound("below_zero_retrogen").keySet());
        assertEquals(Set.of("starts"), reduced.getCompound("structures").keySet());
        assertEquals(STARTS, reduced.getCompound("structures").getCompound("starts"));
        ListBinaryTag sections = reduced.getList("sections");
        assertEquals(1, sections.size());
        assertEquals(Set.of("biomes"), sections.getCompound(0).keySet());
        assertEquals(BIOMES, sections.getCompound(0).getCompound("biomes"));
    }

    @Test
    void levelLayoutKeepsOnlyTheAllowListedTags() throws IOException {
        CompoundBinaryTag raw = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2724)
                .put("Level", CompoundBinaryTag.builder()
                        .putString("Status", "full")
                        .putInt("xPos", 9)
                        .putInt("zPos", -4)
                        .putIntArray("Biomes", new int[] {8, 8, 171})
                        .putLong("InhabitedTime", 1L)
                        .put("TileEntities", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(blockEntity()).build())
                        .put("Structures", CompoundBinaryTag.builder()
                                .put("Starts", STARTS)
                                .put("References", CompoundBinaryTag.builder().putLongArray("monument", new long[] {7L}).build())
                                .build())
                        .put("Sections", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(CompoundBinaryTag.builder()
                                        .putByte("Y", (byte) 0)
                                        .putLongArray("BlockStates", new long[] {5L})
                                        .build())
                                .build())
                        .build())
                .build();

        CompoundBinaryTag reduced = read(FixtureReducer.reduce(write(raw)));

        assertEquals(Set.of("DataVersion", "Level"), reduced.keySet());
        CompoundBinaryTag level = reduced.getCompound("Level");
        assertEquals(Set.of("Status", "xPos", "zPos", "Biomes", "Structures", "Sections"), level.keySet());
        assertEquals(raw.getCompound("Level").get("Biomes"), level.get("Biomes"));
        assertEquals(Set.of("Starts"), level.getCompound("Structures").keySet());
        assertEquals(STARTS, level.getCompound("Structures").getCompound("Starts"));
        assertEquals(Set.of(), level.getList("Sections").getCompound(0).keySet());
    }
}
