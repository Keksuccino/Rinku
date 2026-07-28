package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupPaintStateTest {
    @Test
    void resizeInvalidatesRetainedPixelsUntilMatchingPaint() {
        PopupPaintState state = new PopupPaintState();
        Rectangle initialGeometry = new Rectangle(8, 12, 40, 24);
        Rectangle resizedGeometry = new Rectangle(8, 12, 64, 32);
        state.updateVisibility(true);
        state.updateGeometry(initialGeometry);
        long initialGeneration = state.generation();
        assertTrue(state.markFullPainted(initialGeneration, initialGeometry, true, 40, 24));
        assertTrue(state.canComposite(initialGeneration, initialGeometry, true));

        assertTrue(state.updateGeometry(resizedGeometry));
        long resizedGeneration = state.generation();

        assertNotEquals(initialGeneration, resizedGeneration);
        assertFalse(state.canComposite(initialGeneration, initialGeometry, true));
        assertFalse(state.canComposite(resizedGeneration, resizedGeometry, true));
        assertFalse(state.markFullPainted(initialGeneration, initialGeometry, true, 40, 24));
        assertFalse(state.markFullPainted(resizedGeneration, resizedGeometry, true, 40, 24));
        assertTrue(state.requiresFullPaint(resizedGeneration, resizedGeometry, true));
        assertTrue(state.markFullPainted(resizedGeneration, resizedGeometry, true, 64, 32));
        assertTrue(state.canComposite(resizedGeneration, resizedGeometry, true));
    }

    @Test
    void sameCapacityReshapeStillInvalidatesRetainedPixels() {
        PopupPaintState state = new PopupPaintState();
        Rectangle initialGeometry = new Rectangle(2, 3, 3, 8);
        Rectangle reshapedGeometry = new Rectangle(2, 3, 4, 6);
        assertEquals(initialGeometry.width * initialGeometry.height * 4, reshapedGeometry.width * reshapedGeometry.height * 4);
        state.updateVisibility(true);
        state.updateGeometry(initialGeometry);
        long initialGeneration = state.generation();
        assertTrue(state.markFullPainted(initialGeneration, initialGeometry, true, 3, 8));

        assertTrue(state.updateGeometry(reshapedGeometry));
        long reshapedGeneration = state.generation();

        assertFalse(state.canComposite(reshapedGeneration, reshapedGeometry, true));
        assertFalse(state.markFullPainted(reshapedGeneration, reshapedGeometry, true, 3, 8));
        assertTrue(state.markFullPainted(reshapedGeneration, reshapedGeometry, true, 4, 6));
        assertTrue(state.canComposite(reshapedGeneration, reshapedGeometry, true));
    }

    @Test
    void hideAndReopenRequireNewPopupPaint() {
        PopupPaintState state = new PopupPaintState();
        Rectangle geometry = new Rectangle(10, 14, 30, 18);
        state.updateVisibility(true);
        state.updateGeometry(geometry);
        long shownGeneration = state.generation();
        assertTrue(state.markFullPainted(shownGeneration, geometry, true, 30, 18));

        assertTrue(state.updateVisibility(false));
        long hiddenGeneration = state.generation();
        assertFalse(state.canComposite(hiddenGeneration, geometry, false));
        assertFalse(state.markFullPainted(hiddenGeneration, geometry, false, 30, 18));
        assertFalse(state.updateVisibility(false));
        assertEquals(hiddenGeneration, state.generation());

        assertTrue(state.updateVisibility(true));
        long reopenedGeneration = state.generation();
        assertFalse(state.canComposite(shownGeneration, geometry, true));
        assertFalse(state.canComposite(reopenedGeneration, geometry, true));
        assertTrue(state.markFullPainted(reopenedGeneration, geometry, true, 30, 18));
        assertTrue(state.canComposite(reopenedGeneration, geometry, true));
    }

    @Test
    void lateCallbacksCannotValidateOrRestoreNewGeneration() {
        PopupPaintState state = new PopupPaintState();
        Rectangle initialGeometry = new Rectangle(4, 5, 20, 12);
        Rectangle resizedGeometry = new Rectangle(6, 7, 24, 10);
        state.updateVisibility(true);
        state.updateGeometry(initialGeometry);
        long initialGeneration = state.generation();
        assertTrue(state.markFullPainted(initialGeneration, initialGeometry, true, 20, 12));

        state.updateGeometry(resizedGeometry);
        long resizedGeneration = state.generation();

        assertFalse(state.canComposite(initialGeneration, initialGeometry, true));
        assertFalse(state.markFullPainted(initialGeneration, initialGeometry, true, 20, 12));
        assertFalse(state.markFullPainted(resizedGeneration, resizedGeometry, true, 20, 12));
        assertFalse(state.canComposite(resizedGeneration, resizedGeometry, true));
        assertTrue(state.markFullPainted(resizedGeneration, resizedGeometry, true, 24, 10));
        assertFalse(state.canComposite(initialGeneration, initialGeometry, true));
        assertTrue(state.canComposite(resizedGeneration, resizedGeometry, true));
    }

    @Test
    void exactDuplicateVisibleCallbacksPreserveRetainedPixels() {
        PopupPaintState state = new PopupPaintState();
        Rectangle geometry = new Rectangle(7, 9, 32, 16);
        state.updateVisibility(true);
        state.updateGeometry(geometry);
        long generation = state.generation();
        assertTrue(state.markFullPainted(generation, geometry, true, 32, 16));

        assertFalse(state.updateVisibility(true));
        assertFalse(state.updateGeometry(new Rectangle(geometry)));

        assertEquals(generation, state.generation());
        assertFalse(state.requiresFullPaint(generation, geometry, true));
        assertTrue(state.canComposite(generation, geometry, true));
    }
}
