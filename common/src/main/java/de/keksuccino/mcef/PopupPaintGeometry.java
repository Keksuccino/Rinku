package de.keksuccino.mcef;

import java.awt.Rectangle;

/**
 * Separates popup callback-space retention from destination texture clipping.
 *
 * <p>CEF popup buffers always begin at popup-local coordinate {@code (0, 0)}, even when the popup itself extends
 * outside the browser view. The complete callback-space dirty region must still be retained because a later view
 * resize can expose pixels which were offscreen when they arrived. Only the optional texture upload is clipped to
 * the current browser texture.
 */
final class PopupPaintGeometry {
    private PopupPaintGeometry() {}

    static PaintPlan plan(Rectangle dirtySource, int sourceWidth, int sourceHeight, int destinationX, int destinationY, int destinationWidth, int destinationHeight) {
        Region retainedSource = clipSource(dirtySource, sourceWidth, sourceHeight);
        if (retainedSource == null) {
            return null;
        }

        Upload upload = clipUpload(retainedSource, destinationX, destinationY, destinationWidth, destinationHeight);
        boolean completeSourceFrame = retainedSource.x() == 0 && retainedSource.y() == 0 && retainedSource.width() == sourceWidth && retainedSource.height() == sourceHeight;
        return new PaintPlan(retainedSource, upload, completeSourceFrame);
    }

    private static Region clipSource(Rectangle dirtySource, int sourceWidth, int sourceHeight) {
        if (dirtySource == null || dirtySource.width <= 0 || dirtySource.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }

        long left = Math.max(0L, dirtySource.x);
        long top = Math.max(0L, dirtySource.y);
        long right = Math.min((long) sourceWidth, (long) dirtySource.x + dirtySource.width);
        long bottom = Math.min((long) sourceHeight, (long) dirtySource.y + dirtySource.height);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Region((int) left, (int) top, (int) (right - left), (int) (bottom - top));
    }

    private static Upload clipUpload(Region retainedSource, int destinationX, int destinationY, int destinationWidth, int destinationHeight) {
        if (destinationWidth <= 0 || destinationHeight <= 0) {
            return null;
        }

        long unclippedLeft = (long) destinationX + retainedSource.x();
        long unclippedTop = (long) destinationY + retainedSource.y();
        long visibleLeft = Math.max(0L, unclippedLeft);
        long visibleTop = Math.max(0L, unclippedTop);
        long visibleRight = Math.min((long) destinationWidth, unclippedLeft + retainedSource.width());
        long visibleBottom = Math.min((long) destinationHeight, unclippedTop + retainedSource.height());
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            return null;
        }

        int width = (int) (visibleRight - visibleLeft);
        int height = (int) (visibleBottom - visibleTop);
        int sourceX = (int) (retainedSource.x() + visibleLeft - unclippedLeft);
        int sourceY = (int) (retainedSource.y() + visibleTop - unclippedTop);
        Region source = new Region(sourceX, sourceY, width, height);
        Region destination = new Region((int) visibleLeft, (int) visibleTop, width, height);
        return new Upload(source, destination);
    }

    record PaintPlan(Region retainedSource, Upload upload, boolean completeSourceFrame) {}

    record Upload(Region source, Region destination) {}

    record Region(int x, int y, int width, int height) {
        Region {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("A paint region must have a non-negative origin and positive dimensions");
            }
        }
    }
}
