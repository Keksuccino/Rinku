package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class RinkuRendererTest {

    @Test
    void fullPaintConvertsColorChannelsAndPreservesAlpha() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{10, 20, 30, 40, 50, 60, 70, 0});
        ByteBuffer result = RinkuRenderer.convertBgraToRgba(source, 2, 0, 0, 2, 1, null);
        assertTrue(result.isDirect());
        assertPixels(result, 30, 20, 10, 40, 70, 60, 50, 0);
    }

    @Test
    void conversionPreservesUnsignedChannelsAndSourceByteOrder() {
        ByteBuffer source = ByteBuffer.allocate(256 * 4).order(java.nio.ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 256; i++) source.put((byte) i).put((byte) (i ^ 128)).put((byte) (255 - i)).put((byte) i);
        source.flip();
        ByteBuffer result = RinkuRenderer.convertBgraToRgba(source, 256, 0, 0, 256, 1, null);
        for (int i = 0; i < 256; i++) {
            assertEquals(255 - i, Byte.toUnsignedInt(result.get(i * 4)));
            assertEquals(i ^ 128, Byte.toUnsignedInt(result.get(i * 4 + 1)));
            assertEquals(i, Byte.toUnsignedInt(result.get(i * 4 + 2)));
            assertEquals(i, Byte.toUnsignedInt(result.get(i * 4 + 3)));
        }
        assertEquals(java.nio.ByteOrder.BIG_ENDIAN, source.order());
    }

    @Test
    void dirtyRegionUsesSourceStrideAndBothOffsets() {
        ByteBuffer source = surface(4, 3);
        ByteBuffer result = RinkuRenderer.convertBgraToRgba(source, 4, 1, 1, 2, 2, null);
        assertPixels(result, 7, 6, 5, 8, 8, 7, 6, 9, 11, 10, 9, 12, 12, 11, 10, 13);
    }

    @Test
    void clippedPopupPacksOnlyVisibleSourcePixels() {
        PopupPaintGeometry.PaintPlan plan = PopupPaintGeometry.plan(new java.awt.Rectangle(0, 0, 4, 3), 4, 3, -2, -1, 3, 3);
        assertNotNull(plan);
        PopupPaintGeometry.Region source = plan.upload().source();
        ByteBuffer result = RinkuRenderer.convertBgraToRgba(surface(4, 3), 4, source.x(), source.y(), source.width(), source.height(), null);
        assertPixels(result, 8, 7, 6, 9, 9, 8, 7, 10, 12, 11, 10, 13, 13, 12, 11, 14);
    }

    @Test
    void callbackBufferPositionAndLimitDoNotOffsetTheSurfaceOrGetMutated() {
        ByteBuffer source = surface(3, 2);
        source.position(4).limit(8);
        ByteBuffer result = RinkuRenderer.convertBgraToRgba(source.asReadOnlyBuffer(), 3, 2, 1, 1, 1, null);
        assertPixels(result, 7, 6, 5, 8);
        assertEquals(4, source.position());
        assertEquals(8, source.limit());
    }

    @Test
    void stagingBufferIsReusedAndLimitedToEachPaint() {
        ByteBuffer buffer = RinkuRenderer.convertBgraToRgba(surface(3, 2), 3, 0, 0, 3, 2, null);
        ByteBuffer smaller = RinkuRenderer.convertBgraToRgba(surface(3, 2), 3, 1, 0, 1, 1, buffer);
        assertSame(buffer, smaller);
        assertPixels(smaller, 3, 2, 1, 4);
        ByteBuffer larger = RinkuRenderer.convertBgraToRgba(surface(4, 2), 4, 0, 0, 4, 2, smaller);
        assertNotSame(buffer, larger);
        assertEquals(32, larger.remaining());
    }

    @Test
    void invalidAndOverflowingRegionsAreRejectedBeforeAllocation() {
        ByteBuffer source = surface(2, 2);
        int[][] invalid = {{2, -1, 0, 1, 1}, {2, 0, -1, 1, 1}, {2, 0, 0, 0, 1}, {2, 0, 0, 1, -1}, {2, 1, 0, 2, 1}, {2, 0, 1, 1, 2}, {Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE}, {2, Integer.MAX_VALUE, 0, 1, 1}};
        for (int[] region : invalid) {
            assertThrows(IllegalArgumentException.class, () -> RinkuRenderer.convertBgraToRgba(source, region[0], region[1], region[2], region[3], region[4], null));
        }
        assertThrows(IllegalArgumentException.class, () -> RinkuRenderer.convertBgraToRgba(ByteBuffer.allocate(3), 1, 0, 0, 1, 1, null));
    }

    private static ByteBuffer surface(int width, int height) {
        ByteBuffer source = ByteBuffer.allocate(width * height * 4);
        for (int pixel = 0; pixel < width * height; pixel++) {
            source.put((byte) pixel).put((byte) (pixel + 1)).put((byte) (pixel + 2)).put((byte) (pixel + 3));
        }
        return source.flip();
    }

    private static void assertPixels(ByteBuffer actual, int... expected) {
        assertEquals(expected.length, actual.remaining());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], Byte.toUnsignedInt(actual.get(i)));
    }

}
