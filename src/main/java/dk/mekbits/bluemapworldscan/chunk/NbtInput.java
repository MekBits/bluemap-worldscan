package dk.mekbits.bluemapworldscan.chunk;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A cursor over one NBT document in a byte array, for reading a few tags and skipping the rest
 * without building them.
 *
 * <p>It checks what adventure-nbt's reader checks: a known tag type, a length that is not negative
 * and fits the document, a list of {@code TAG_End} that is empty, and at most 512 nested compounds,
 * lists and arrays. Strings that are skipped are not decoded, so a malformed name or string in a
 * skipped tag is not an error.
 */
final class NbtInput {

    static final int END = 0;
    static final int BYTE = 1;
    static final int SHORT = 2;
    static final int INT = 3;
    static final int LONG = 4;
    static final int FLOAT = 5;
    static final int DOUBLE = 6;
    static final int BYTE_ARRAY = 7;
    static final int STRING = 8;
    static final int LIST = 9;
    static final int COMPOUND = 10;
    static final int INT_ARRAY = 11;
    static final int LONG_ARRAY = 12;

    /** adventure-nbt's limit, also on compounds, lists and arrays. */
    private static final int MAX_DEPTH = 512;

    private final byte[] buffer;
    private final int end;
    private int position;
    private int depth;
    private int nameStart;
    private int nameLength;

    NbtInput(byte[] buffer, int length) {
        this.buffer = buffer;
        this.end = length;
    }

    /** A tag type byte; {@link #END} ends a compound. */
    int readType() throws IOException {
        int type = readUnsignedByte();
        if (type > LONG_ARRAY) {
            throw new IOException("unknown NBT tag type " + type);
        }
        return type;
    }

    /** Reads a tag name and keeps where it is, for {@link #nameIs} and {@link #nameAsString}. */
    void readName() throws IOException {
        nameLength = readUnsignedShort();
        need(nameLength);
        nameStart = position;
        position += nameLength;
    }

    /** Whether the last name read is this ASCII name. */
    boolean nameIs(byte[] ascii) {
        return nameLength == ascii.length && Arrays.equals(buffer, nameStart, nameStart + nameLength, ascii, 0, ascii.length);
    }

    String nameAsString() throws IOException {
        return decode(nameStart, nameLength);
    }

    static boolean isNumber(int type) {
        return type >= BYTE && type <= DOUBLE;
    }

    /** A number tag's payload as an int, as adventure-nbt's {@code intValue}: floats and doubles are floored. */
    int readNumberAsInt(int type) throws IOException {
        return switch (type) {
            case BYTE -> (byte) readUnsignedByte();
            case SHORT -> (short) readUnsignedShort();
            case INT -> readInt();
            case LONG -> (int) readLong();
            case FLOAT -> floor(Float.intBitsToFloat(readInt()));
            case DOUBLE -> floor(Double.longBitsToDouble(readLong()));
            default -> throw new IllegalArgumentException("not a number tag type: " + type);
        };
    }

    String readString() throws IOException {
        int length = readUnsignedShort();
        need(length);
        String value = decode(position, length);
        position += length;
        return value;
    }

    int[] readIntArray() throws IOException {
        enter();
        int length = readLength();
        need(4L * length);
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = readInt();
        }
        exit();
        return values;
    }

    /** The element type and the length of a list, after {@link #enter}; the caller reads the elements and calls {@link #exit}. */
    int readListType() throws IOException {
        return readType();
    }

    /** The length of a list or array: not negative, and a list of {@code TAG_End} is empty. */
    int readListLength(int elementType) throws IOException {
        int length = readLength();
        if (elementType == END && length > 0) {
            throw new IOException("a list of TAG_End with " + length + " elements");
        }
        return length;
    }

    /** One level deeper: a compound, list or array. */
    void enter() throws IOException {
        if (++depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
    }

    void exit() {
        depth--;
    }

    /** Skips the payload of a tag of this type. */
    void skip(int type) throws IOException {
        switch (type) {
            case BYTE -> skipBytes(1);
            case SHORT -> skipBytes(2);
            case INT, FLOAT -> skipBytes(4);
            case LONG, DOUBLE -> skipBytes(8);
            case STRING -> skipBytes(readUnsignedShort());
            case BYTE_ARRAY -> skipArray(1);
            case INT_ARRAY -> skipArray(4);
            case LONG_ARRAY -> skipArray(8);
            case LIST -> {
                enter();
                int elementType = readListType();
                skipElements(elementType, readListLength(elementType));
                exit();
            }
            case COMPOUND -> {
                enter();
                skipRestOfCompound();
                exit();
            }
            default -> throw new IOException("cannot skip NBT tag type " + type);
        }
    }

    /** Skips the remaining elements of a list whose type and length were read. */
    void skipElements(int elementType, int count) throws IOException {
        int fixed = fixedSize(elementType);
        if (fixed > 0) {
            skipBytes((long) fixed * count);
            return;
        }
        for (int i = 0; i < count; i++) {
            skip(elementType);
        }
    }

    /** Skips the tags of a compound up to and including its {@code TAG_End}. */
    void skipRestOfCompound() throws IOException {
        for (int type = readType(); type != END; type = readType()) {
            skipBytes(readUnsignedShort());
            skip(type);
        }
    }

    private void skipArray(int elementBytes) throws IOException {
        enter();
        skipBytes((long) elementBytes * readLength());
        exit();
    }

    private static int fixedSize(int type) {
        return switch (type) {
            case BYTE -> 1;
            case SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
            default -> 0;
        };
    }

    private int readLength() throws IOException {
        int length = readInt();
        if (length < 0) {
            throw new IOException("negative NBT length " + length);
        }
        return length;
    }

    private void skipBytes(long count) throws IOException {
        need(count);
        position += (int) count;
    }

    private void need(long count) throws EOFException {
        if (count > end - position) {
            throw new EOFException("the NBT document ends before its end");
        }
    }

    int readUnsignedByte() throws IOException {
        need(1);
        return buffer[position++] & 0xFF;
    }

    private int readUnsignedShort() throws IOException {
        need(2);
        int value = (buffer[position] & 0xFF) << 8 | buffer[position + 1] & 0xFF;
        position += 2;
        return value;
    }

    private int readInt() throws IOException {
        need(4);
        int value = (buffer[position] & 0xFF) << 24 | (buffer[position + 1] & 0xFF) << 16
                | (buffer[position + 2] & 0xFF) << 8 | buffer[position + 3] & 0xFF;
        position += 4;
        return value;
    }

    private long readLong() throws IOException {
        long high = readInt() & 0xFFFFFFFFL;
        return high << 32 | readInt() & 0xFFFFFFFFL;
    }

    /** Modified UTF-8, as {@link DataInputStream#readUTF}; plain ASCII without a copy through a stream. */
    private String decode(int start, int length) throws IOException {
        for (int i = start; i < start + length; i++) {
            if (buffer[i] <= 0) {
                byte[] prefixed = new byte[length + 2];
                prefixed[0] = (byte) (length >>> 8);
                prefixed[1] = (byte) length;
                System.arraycopy(buffer, start, prefixed, 2, length);
                return new DataInputStream(new ByteArrayInputStream(prefixed)).readUTF();
            }
        }
        return new String(buffer, start, length, StandardCharsets.ISO_8859_1);
    }

    /** adventure-nbt's floor: the int below, also for a negative number with a fraction. */
    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** The same in float arithmetic, as adventure-nbt floors a float. */
    private static int floor(float value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
