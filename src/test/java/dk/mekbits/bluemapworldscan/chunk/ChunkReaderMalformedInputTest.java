package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;

import dk.mekbits.bluemapworldscan.testsupport.ChunkFixtures;
import org.junit.jupiter.api.Test;

/** Whatever the input, {@link ChunkReader#read} either returns a chunk or throws an {@link IOException}. */
class ChunkReaderMalformedInputTest {

    private static final int COMPOUND = 10;
    private static final int BYTE_ARRAY = 7;
    private static final int INT_ARRAY = 11;
    private static final int LIST = 9;

    private static void assertUnreadable(byte[] nbt) {
        assertThrows(IOException.class, () -> ChunkReader.read(new ByteArrayInputStream(nbt)));
    }

    /** A named root compound whose only tag is the given header; callers append the payload. */
    private static DataOutputStream rootWithTag(ByteArrayOutputStream bytes, int tagType, String name) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        out.writeByte(tagType);
        out.writeUTF(name);
        return out;
    }

    @Test
    void aChunkCutInHalfIsAnIoException() {
        byte[] whole = ChunkFixtures.bytes("overworld-1.18.2plus-struct-monument-dv4903-c.-440.-27");

        assertUnreadable(Arrays.copyOf(whole, whole.length / 2));
    }

    @Test
    void noBytesAtAllIsAnIoException() {
        assertUnreadable(new byte[0]);
    }

    @Test
    void anIntArrayWithANegativeLengthIsAnIoException() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = rootWithTag(bytes, INT_ARRAY, "Biomes");
        out.writeInt(-1);
        out.writeByte(0);

        assertUnreadable(bytes.toByteArray());
    }

    @Test
    void aByteArrayLargerThanTheLimitIsRejectedBeforeItIsRead() throws IOException {
        int declared = 100 << 20;
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream out = rootWithTag(head, BYTE_ARRAY, "Biomes");
        out.writeInt(declared);
        InputStream zeros = new InputStream() {
            private int remaining = declared;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }
        };
        // Without the limit this is a complete, readable 100 MiB document.
        InputStream document = new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(head.toByteArray()), zeros), new ByteArrayInputStream(new byte[] {0}));

        assertThrows(IOException.class, () -> ChunkReader.read(document));
    }

    @Test
    void aRootThatIsNotACompoundIsAnIoException() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(LIST);
        out.writeUTF("");
        out.writeByte(COMPOUND);
        out.writeInt(0);

        assertUnreadable(bytes.toByteArray());
    }

    @Test
    void anUnknownTagTypeIsAnIoException() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = rootWithTag(bytes, 99, "Biomes");
        out.writeByte(0);

        assertUnreadable(bytes.toByteArray());
    }

    @Test
    void compoundsNestedDeeperThanTheReaderAllowsAreAnIoException() throws IOException {
        int depth = 600;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        for (int i = 0; i < depth; i++) {
            out.writeByte(COMPOUND);
            out.writeUTF("a");
        }
        for (int i = 0; i <= depth; i++) {
            out.writeByte(0);
        }

        assertUnreadable(bytes.toByteArray());
    }

    @Test
    void aWellFormedEmptyChunkIsNotAnError() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        out.writeByte(0);

        assertDoesNotThrow(() -> ChunkReader.read(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
