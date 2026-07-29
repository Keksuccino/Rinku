package de.keksuccino.rinku;

import java.nio.ByteBuffer;

/** Converts a rectangular BGRA source region into tightly packed RGBA pixels for GPU uploads. */
final class BgraRgbaConverter {

    private static final int BYTES_PER_PIXEL = 4;

    private BgraRgbaConverter() {}

    static int convertRegion(ByteBuffer source, int sourceRowLength, int sourceX, int sourceY, int width, int height, ByteBuffer destination) {
        if (source == null || destination == null || sourceRowLength <= 0 || sourceX < 0 || sourceY < 0 || width <= 0 || height <= 0) return 0;
        long sourceRowEnd = (long) sourceX + width;
        long requiredPixels = (long) width * height;
        long lastSourceRow = (long) sourceY + height - 1L;
        long sourcePixelCapacity = source.capacity() / BYTES_PER_PIXEL;
        if (sourceRowEnd > sourceRowLength || requiredPixels > Integer.MAX_VALUE / BYTES_PER_PIXEL || lastSourceRow > sourcePixelCapacity / sourceRowLength) return 0;
        long sourceEndPixel = lastSourceRow * sourceRowLength + sourceRowEnd;
        int requiredBytes = (int) requiredPixels * BYTES_PER_PIXEL;
        if (sourceEndPixel > sourcePixelCapacity || destination.capacity() < requiredBytes) return 0;

        // JCEF owns the callback buffer's cursor state. Cleared duplicate views let the upload use the complete
        // native allocation without mutating caller positions or trusting a transient limit.
        ByteBuffer sourceView = source.duplicate();
        ByteBuffer destinationView = destination.duplicate();
        sourceView.clear();
        destinationView.clear();
        int destinationRowBytes = width * BYTES_PER_PIXEL;
        for (int row = 0; row < height; row++) {
            int sourceOffset = ((sourceY + row) * sourceRowLength + sourceX) * BYTES_PER_PIXEL;
            int destinationOffset = row * destinationRowBytes;
            for (int column = 0; column < width; column++) {
                int sourcePixel = sourceOffset + column * BYTES_PER_PIXEL;
                int destinationPixel = destinationOffset + column * BYTES_PER_PIXEL;
                destinationView.put(destinationPixel, sourceView.get(sourcePixel + 2));
                destinationView.put(destinationPixel + 1, sourceView.get(sourcePixel + 1));
                destinationView.put(destinationPixel + 2, sourceView.get(sourcePixel));
                destinationView.put(destinationPixel + 3, sourceView.get(sourcePixel + 3));
            }
        }
        return requiredBytes;
    }

}
