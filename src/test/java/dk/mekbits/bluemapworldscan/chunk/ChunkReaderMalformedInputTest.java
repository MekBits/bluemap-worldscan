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

    /** A complete, well-formed document: a root compound holding one byte array of zeros. */
    private static InputStream documentWithByteArray(int length) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream out = rootWithTag(head, BYTE_ARRAY, "Biomes");
        out.writeInt(length);
        InputStream zeros = new InputStream() {
            private int remaining = length;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }
        };
        return new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(head.toByteArray()), zeros), new ByteArrayInputStream(new byte[] {0}));
    }

    @Test
    void aDocumentLargerThanTheLimitIsRejected() throws IOException {
        // A well-formed document, only larger than the 16 MiB limit.
        InputStream document = documentWithByteArray(20 << 20);

        assertThrows(IOException.class, () -> ChunkReader.read(document));
    }

    @Test
    void aDocumentJustUnderTheLimitIsRead() throws IOException {
        InputStream document = documentWithByteArray(15 << 20);

        assertDoesNotThrow(() -> ChunkReader.read(document));
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
    void compoundsNestedAsDeepAsAdventureNbtAllowsAreRead() throws IOException {
        // adventure-nbt checks the depth before it steps down, so the root and 512 more levels pass.
        int depth = 512;
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

        assertDoesNotThrow(() -> ChunkReader.read(new ByteArrayInputStream(bytes.toByteArray())));
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
