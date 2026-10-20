package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import dk.mekbits.bluemapworldscan.testsupport.SyntheticChunks;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

/**
 * The rules by which {@link ChunkReader} takes a tag, which are those of adventure-nbt's typed
 * getters on a whole tree: any number type is an int, a tag of another type is missing, and the
 * last of two tags with the same name counts.
 */
class ChunkReaderTagRulesTest {

    private static final int END = 0;
    private static final int INT = 3;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;

    private static ChunkInfo read(CompoundBinaryTag root) throws IOException {
        return ChunkReader.read(new ByteArrayInputStream(SyntheticChunks.toBytes(root)));
    }

    private static ChunkInfo read(byte[] nbt) throws IOException {
        return ChunkReader.read(new ByteArrayInputStream(nbt));
    }

    @Test
    void numbersOfAnyTypeAreReadAsIntsAndFloatsAreFloored() throws IOException {
        ChunkInfo info = read(CompoundBinaryTag.builder()
                .putLong("DataVersion", 4000L)
                .putFloat("xPos", -1.5f)
                .putDouble("zPos", 2.9)
                .build());

        assertEquals(4000, info.dataVersion());
        assertEquals(-2, info.chunkX());
        assertEquals(2, info.chunkZ());
    }

    @Test
    void aDataVersionThatIsNotANumberIsMissing() throws IOException {
        ChunkInfo info = read(CompoundBinaryTag.builder().putString("DataVersion", "4000").build());

        assertEquals(-1, info.dataVersion());
        assertEquals(Kind.PRE_1_13, info.kind());
    }

    @Test
    void sectionsThatAreNotAListOfCompoundsGiveNoBiomes() throws IOException {
        ChunkInfo info = read(CompoundBinaryTag.builder()
                .putInt("DataVersion", 4000)
                .putString("Status", "minecraft:full")
                .putIntArray("sections", new int[] {1, 2})
                .build());

        assertEquals(Set.of(), info.biomes());
    }

    @Test
    void ofTwoTagsWithTheSameNameTheLastCounts() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = root(bytes);
        tag(out, INT, "DataVersion").writeInt(4000);
        tag(out, STRING, "Status").writeUTF("minecraft:empty");
        tag(out, INT, "xPos").writeInt(1);
        tag(out, STRING, "Status").writeUTF("minecraft:full");
        tag(out, INT, "xPos").writeInt(7);
        sectionWithPalette(out, "minecraft:plains");
        out.writeByte(END);

        ChunkInfo info = read(bytes.toByteArray());

        assertEquals(7, info.chunkX());
        assertEquals(Set.of("minecraft:plains"), info.biomes());
    }

    @Test
    void aLevelTagThatIsReplacedByANonCompoundLeavesTheRootLayout() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = root(bytes);
        tag(out, INT, "DataVersion").writeInt(2500);
        tag(out, COMPOUND, "Level");
        tag(out, INT, "xPos").writeInt(3);
        out.writeByte(END);
        tag(out, INT, "Level").writeInt(0);
        tag(out, INT, "xPos").writeInt(9);
        out.writeByte(END);

        assertEquals(9, read(bytes.toByteArray()).chunkX());
    }

    @Test
    void aListOfEndTagsWithElementsIsAnIoException() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = root(bytes);
        tag(out, LIST, "Heightmaps");
        out.writeByte(END);
        out.writeInt(3);
        out.writeByte(END);

        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
    }

    @Test
    void aStartWhoseKeyIsNotACompoundAnyMoreIsDropped() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = root(bytes);
        tag(out, INT, "DataVersion").writeInt(4000);
        tag(out, COMPOUND, "structures");
        tag(out, COMPOUND, "starts");
        tag(out, COMPOUND, "minecraft:igloo");
        tag(out, STRING, "id").writeUTF("minecraft:igloo");
        tag(out, LIST, "Children");
        out.writeByte(COMPOUND);
        out.writeInt(1);
        tag(out, 11, "BB");
        out.writeInt(6);
        for (int i = 0; i < 6; i++) {
            out.writeInt(i);
        }
        out.writeByte(END);
        out.writeByte(END);
        tag(out, COMPOUND, "minecraft:swamp_hut");
        tag(out, STRING, "id").writeUTF("minecraft:swamp_hut");
        out.writeByte(END);
        tag(out, INT, "minecraft:igloo").writeInt(0);
        out.writeByte(END);
        out.writeByte(END);
        out.writeByte(END);

        assertEquals(List.of(), read(bytes.toByteArray()).structures());
    }

    private static DataOutputStream root(ByteArrayOutputStream bytes) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        return out;
    }

    private static DataOutputStream tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
        return out;
    }

    /** A {@code sections} list of one section whose {@code biomes.palette} holds the name. */
    private static void sectionWithPalette(DataOutputStream out, String biome) throws IOException {
        tag(out, LIST, "sections");
        out.writeByte(COMPOUND);
        out.writeInt(1);
        tag(out, COMPOUND, "biomes");
        tag(out, LIST, "palette");
        out.writeByte(STRING);
        out.writeInt(1);
        out.writeUTF(biome);
        out.writeByte(END);
        out.writeByte(END);
    }
}
