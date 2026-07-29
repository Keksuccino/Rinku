package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BgraRgbaConverterTest {

    @Test
    void convertsCompleteBgraBufferToRgba() {
        ByteBuffer source = buffer(1, 2, 3, 4, 11, 12, 13, 14);
        ByteBuffer destination = ByteBuffer.allocateDirect(8);

        int convertedBytes = BgraRgbaConverter.convertRegion(source, 2, 0, 0, 2, 1, destination);

        assertEquals(8, convertedBytes);
        assertArrayEquals(new byte[]{3, 2, 1, 4, 13, 12, 11, 14}, bytes(destination, convertedBytes));
    }

    @Test
    void extractsOffsetRegionAcrossSourceRows() {
        ByteBuffer source = buffer(
                1, 2, 3, 4, 11, 12, 13, 14, 21, 22, 23, 24,
                31, 32, 33, 34, 41, 42, 43, 44, 51, 52, 53, 54,
                61, 62, 63, 64, 71, 72, 73, 74, 81, 82, 83, 84
        );
        ByteBuffer destination = ByteBuffer.allocateDirect(16);

        int convertedBytes = BgraRgbaConverter.convertRegion(source, 3, 1, 1, 2, 2, destination);

        assertEquals(16, convertedBytes);
        assertArrayEquals(new byte[]{43, 42, 41, 44, 53, 52, 51, 54, 73, 72, 71, 74, 83, 82, 81, 84}, bytes(destination, convertedBytes));
    }

    @Test
    void preservesCallerBufferCursors() {
        ByteBuffer source = buffer(1, 2, 3, 4, 11, 12, 13, 14);
        ByteBuffer destination = ByteBuffer.allocateDirect(8);
        source.position(3);
        source.limit(6);
        destination.position(2);
        destination.limit(5);

        assertEquals(4, BgraRgbaConverter.convertRegion(source, 2, 1, 0, 1, 1, destination));
        assertEquals(3, source.position());
        assertEquals(6, source.limit());
        assertEquals(2, destination.position());
        assertEquals(5, destination.limit());
    }

    @Test
    void rejectsInvalidOrUndersizedRegions() {
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        ByteBuffer destination = ByteBuffer.allocateDirect(16);

        assertEquals(0, BgraRgbaConverter.convertRegion(source, 2, -1, 0, 1, 1, destination));
        assertEquals(0, BgraRgbaConverter.convertRegion(source, 2, 1, 0, 2, 1, destination));
        assertEquals(0, BgraRgbaConverter.convertRegion(source, 2, 0, 1, 2, 2, destination));
        assertEquals(0, BgraRgbaConverter.convertRegion(source, 2, 0, 0, 2, 2, ByteBuffer.allocateDirect(15)));
        assertEquals(0, BgraRgbaConverter.convertRegion(source, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, destination));
    }

    private static ByteBuffer buffer(int... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length);
        for (int index = 0; index < values.length; index++) buffer.put(index, (byte) values[index]);
        return buffer;
    }

    private static byte[] bytes(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) bytes[index] = buffer.get(index);
        return bytes;
    }

}
