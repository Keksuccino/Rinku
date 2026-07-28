package de.keksuccino.mcef;

import java.awt.Rectangle;

/**
 * Tracks whether retained popup pixels belong to the active visibility and geometry generation.
 *
 * <p>CEF does not attach a popup generation to paint callbacks, so the browser snapshots this generation while
 * serializing callbacks. Any real visibility or geometry transition invalidates the retained pixels. Exact duplicate
 * callbacks deliberately keep them valid so a full view upload can continue restoring an unchanged visible popup.
 * Access is serialized by {@code MCEFBrowser}'s paint callback lock.
 */
final class PopupPaintState {
    private Rectangle geometry;
    private boolean visible;
    private boolean retainedPixelsValid;
    private long generation;

    boolean updateVisibility(boolean visible) {
        if (this.visible == visible) {
            return false;
        }
        this.visible = visible;
        advanceGeneration();
        return true;
    }

    boolean updateGeometry(Rectangle geometry) {
        if (sameGeometry(this.geometry, geometry)) {
            return false;
        }
        this.geometry = copyValidGeometry(geometry);
        advanceGeneration();
        return true;
    }

    Rectangle geometry() {
        return geometry == null ? null : new Rectangle(geometry);
    }

    boolean visible() {
        return visible;
    }

    long generation() {
        return generation;
    }

    boolean isCurrentGeneration(long generation) {
        return this.generation == generation;
    }

    boolean acceptsPaint(long generation, Rectangle geometry, boolean visible, int width, int height) {
        return isCurrentState(generation, geometry, visible) && visible && this.geometry != null && width == this.geometry.width && height == this.geometry.height;
    }

    boolean requiresFullPaint(long generation, Rectangle geometry, boolean visible) {
        return !canComposite(generation, geometry, visible);
    }

    /** Marks pixels valid only after the caller has copied a complete popup frame into the retained buffer. */
    boolean markFullPainted(long generation, Rectangle geometry, boolean visible, int width, int height) {
        if (!acceptsPaint(generation, geometry, visible, width, height)) {
            return false;
        }
        retainedPixelsValid = true;
        return true;
    }

    boolean canComposite(long generation, Rectangle geometry, boolean visible) {
        return retainedPixelsValid && visible && this.geometry != null && isCurrentState(generation, geometry, visible);
    }

    void invalidateRetainedPixels() {
        retainedPixelsValid = false;
    }

    private boolean isCurrentState(long generation, Rectangle geometry, boolean visible) {
        return this.generation == generation && this.visible == visible && sameGeometry(this.geometry, geometry);
    }

    private void advanceGeneration() {
        generation++;
        retainedPixelsValid = false;
    }

    private static Rectangle copyValidGeometry(Rectangle geometry) {
        if (geometry == null || geometry.width <= 0 || geometry.height <= 0) {
            return null;
        }
        return new Rectangle(geometry);
    }

    private static boolean sameGeometry(Rectangle validGeometry, Rectangle candidate) {
        if (candidate == null || candidate.width <= 0 || candidate.height <= 0) {
            return validGeometry == null;
        }
        return validGeometry != null && validGeometry.equals(candidate);
    }
}
