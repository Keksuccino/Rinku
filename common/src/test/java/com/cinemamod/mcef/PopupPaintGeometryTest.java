/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 */

package com.cinemamod.mcef;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupPaintGeometryTest {
    @Test
    void mapsPopupLocalDirtyRegionIntoViewTexture() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(3, 4, 8, 6), 40, 30, 10, 20, 100, 100);

        assertEquals(region(3, 4, 8, 6), plan.retainedSource());
        assertEquals(region(3, 4, 8, 6), plan.upload().source());
        assertEquals(region(13, 24, 8, 6), plan.upload().destination());
        assertFalse(plan.completeSourceFrame());
    }

    @Test
    void clipsUploadAtRightViewEdgeWithoutClippingRetention() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(0, 0, 30, 20), 30, 20, 80, 10, 100, 60);

        assertEquals(region(0, 0, 30, 20), plan.retainedSource());
        assertEquals(region(0, 0, 20, 20), plan.upload().source());
        assertEquals(region(80, 10, 20, 20), plan.upload().destination());
        assertTrue(plan.completeSourceFrame());
    }

    @Test
    void clipsUploadAtBottomViewEdgeWithoutClippingRetention() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(0, 0, 30, 20), 30, 20, 10, 50, 100, 60);

        assertEquals(region(0, 0, 30, 20), plan.retainedSource());
        assertEquals(region(0, 0, 30, 10), plan.upload().source());
        assertEquals(region(10, 50, 30, 10), plan.upload().destination());
        assertTrue(plan.completeSourceFrame());
    }

    @Test
    void clipsUploadAtLeftViewEdgeAndSkipsHiddenSourceColumns() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(0, 0, 20, 15), 20, 15, -8, 10, 100, 60);

        assertEquals(region(0, 0, 20, 15), plan.retainedSource());
        assertEquals(region(8, 0, 12, 15), plan.upload().source());
        assertEquals(region(0, 10, 12, 15), plan.upload().destination());
        assertTrue(plan.completeSourceFrame());
    }

    @Test
    void clipsUploadAtTopViewEdgeAndSkipsHiddenSourceRows() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(0, 0, 20, 15), 20, 15, 10, -6, 100, 60);

        assertEquals(region(0, 0, 20, 15), plan.retainedSource());
        assertEquals(region(0, 6, 20, 9), plan.upload().source());
        assertEquals(region(10, 0, 20, 9), plan.upload().destination());
        assertTrue(plan.completeSourceFrame());
    }

    @Test
    void fullyOffscreenFrameIsRetainedWithoutAnUpload() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(0, 0, 30, 20), 30, 20, 100, 5, 100, 60);

        assertEquals(region(0, 0, 30, 20), plan.retainedSource());
        assertNull(plan.upload());
        assertTrue(plan.completeSourceFrame());
    }

    @Test
    void clipsDirtySourceBeforeComputingDestinationOffsets() {
        PopupPaintGeometry.PaintPlan plan = plan(new Rectangle(-4, 2, 25, 20), 30, 20, -5, -3, 20, 10);

        assertEquals(region(0, 2, 21, 18), plan.retainedSource());
        assertEquals(region(5, 3, 16, 10), plan.upload().source());
        assertEquals(region(0, 0, 16, 10), plan.upload().destination());
        assertFalse(plan.completeSourceFrame());
    }

    @Test
    void dirtyRegionOutsidePopupSourceProducesNoWork() {
        assertNull(PopupPaintGeometry.plan(new Rectangle(30, 4, 10, 8), 30, 20, 0, 0, 100, 60));
    }

    @Test
    void completeOffscreenFrameCanValidateRetainedPixelsForLaterViewResize() {
        Rectangle geometry = new Rectangle(100, 5, 30, 20);
        Rectangle fullSource = new Rectangle(0, 0, geometry.width, geometry.height);
        PopupPaintGeometry.PaintPlan offscreenPlan = plan(fullSource, geometry.width, geometry.height, geometry.x, geometry.y, 100, 60);
        PopupPaintState state = new PopupPaintState();
        state.updateVisibility(true);
        state.updateGeometry(geometry);
        long generation = state.generation();

        assertTrue(offscreenPlan.completeSourceFrame());
        assertNull(offscreenPlan.upload());
        assertTrue(state.markFullPainted(generation, geometry, true, geometry.width, geometry.height));
        assertTrue(state.canComposite(generation, geometry, true));

        PopupPaintGeometry.PaintPlan exposedPlan = plan(fullSource, geometry.width, geometry.height, geometry.x, geometry.y, 140, 60);
        assertEquals(region(0, 0, 30, 20), exposedPlan.upload().source());
        assertEquals(region(100, 5, 30, 20), exposedPlan.upload().destination());
    }

    private static PopupPaintGeometry.PaintPlan plan(Rectangle dirtySource, int sourceWidth, int sourceHeight, int destinationX, int destinationY, int destinationWidth, int destinationHeight) {
        PopupPaintGeometry.PaintPlan plan = PopupPaintGeometry.plan(dirtySource, sourceWidth, sourceHeight, destinationX, destinationY, destinationWidth, destinationHeight);
        assertNotNull(plan);
        return plan;
    }

    private static PopupPaintGeometry.Region region(int x, int y, int width, int height) {
        return new PopupPaintGeometry.Region(x, y, width, height);
    }
}
