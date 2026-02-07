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
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import java.awt.*;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private MCEFCursorChangeListener cursorChangeListener;
    /**
     * Whether MCEF should mimic the controls of a typical web browser.
     * E.g. CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     */
    private boolean browserControls = true;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;
    /**
     * Tracks right-alt state so we can distinguish AltGr text input
     * from regular Ctrl+Alt shortcuts.
     */
    private boolean rightAltDown_MCEF = false;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend MCEFBrowser and override the repaint logic
    protected volatile ByteBuffer popupGraphics;
    protected volatile Rectangle popupSize;
    protected volatile boolean showPopup = false;
    protected volatile boolean popupDrawn = false;

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        super(client.getHandle(), url, transparent, null);
        renderer = new MCEFRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(resolveCursorType_MCEF(cefCursorID));

        if (RenderSystem.isOnRenderThread()) {
            renderer.initialize();
        } else {
            Minecraft.getInstance().submit(renderer::initialize);
        }
    }

    public MCEFRenderer getRenderer() {
        return renderer;
    }
    
    /**
     * Convenience method to get the Identifier for this browser's texture.
     * This can be used directly with GuiGraphics rendering methods.
     * 
     * @return The Identifier for this browser's texture, or null if not initialized
     */
    public Identifier getTextureIdentifier() {
        return renderer != null && renderer.isTextureReady() ? renderer.getTextureIdentifier() : null;
    }
    
    /**
     * Check if the browser's texture is ready for rendering.
     * 
     * @return true if the texture is initialized and ready to be rendered
     */
    public boolean isTextureReady() {
        return renderer != null && renderer.isTextureReady();
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public boolean usingBrowserControls() {
        return browserControls;
    }

    /**
     * Enabling browser controls tells MCEF to mimic the behavior of an actual browser.
     * CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     *
     * @param browserControls whether browser controls should be enabled
     * @return the browser instance
     */
    public MCEFBrowser useBrowserControls(boolean browserControls) {
        this.browserControls = browserControls;
        return this;
    }

    public MCEFDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        showPopup = show;
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        if (size == null || size.width <= 0 || size.height <= 0) {
            popupSize = null;
            popupGraphics = null;
            popupDrawn = false;
            return;
        }

        popupSize = new Rectangle(size);
        int popupBufferSize = getRequiredBufferSize_MCEF(size.width, size.height);
        if (popupBufferSize <= 0) {
            popupGraphics = null;
            popupDrawn = false;
            return;
        }

        if (popupGraphics == null || popupGraphics.capacity() != popupBufferSize) {
            popupGraphics = ByteBuffer.allocateDirect(popupBufferSize);
        }
    }

    // Graphics
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (dirtyRects == null || dirtyRects.length == 0 || buffer == null)
            return;

        Rectangle[] dirtyRectsCopy = new Rectangle[dirtyRects.length];
        for (int i = 0; i < dirtyRects.length; i++) {
            Rectangle dirtyRect = dirtyRects[i];
            dirtyRectsCopy[i] = dirtyRect == null ? null : new Rectangle(dirtyRect);
        }

        Rectangle popupRectSnapshot = popupSize == null ? null : new Rectangle(popupSize);
        boolean showPopupSnapshot = showPopup;

        if (RenderSystem.isOnRenderThread()) {
            onPaintRenderThread_MCEF(
                    popup,
                    dirtyRectsCopy,
                    buffer,
                    width,
                    height,
                    popupRectSnapshot,
                    showPopupSnapshot
            );
            return;
        }

        ByteBuffer bufferCopy = cloneBufferForAsyncPaint_MCEF(buffer);
        Minecraft.getInstance().submit(() -> {
            try {
                onPaintRenderThread_MCEF(
                        popup,
                        dirtyRectsCopy,
                        bufferCopy,
                        width,
                        height,
                        popupRectSnapshot,
                        showPopupSnapshot
                );
            } finally {
                MemoryUtil.memFree(bufferCopy);
            }
        });
    }

    private void onPaintRenderThread_MCEF(
            boolean popup,
            Rectangle[] dirtyRects,
            ByteBuffer buffer,
            int width,
            int height,
            Rectangle popupRect,
            boolean showPopupSnapshot
    ) {
        if (!popup) {
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                renderer.onPaint(buffer, width, height);
                return;
            }

            if (!renderer.supportsDirtyRectUpload()) {
                renderer.onPaint(buffer, width, height);
                if (!showPopupSnapshot) {
                    popupGraphics = null;
                    popupSize = null;
                    popupDrawn = false;
                }
                return;
            }

            GlStateManager._bindTexture(renderer.getTextureID());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);

            for (Rectangle dirtyRect : dirtyRects) {
                Rectangle clippedRect = clipRect_MCEF(dirtyRect, width, height);
                if (clippedRect == null) {
                    continue;
                }

                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, clippedRect.x);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, clippedRect.y);
                renderer.onPaint(buffer, clippedRect.x, clippedRect.y, clippedRect.width, clippedRect.height);
            }

            if ((popupDrawn || showPopupSnapshot) && popupRect != null) {
                Rectangle clippedPopupRect = clipRect_MCEF(popupRect, width, height);
                if (clippedPopupRect == null) {
                    popupGraphics = null;
                    popupSize = null;
                    popupDrawn = false;
                    return;
                }

                if (!showPopupSnapshot) {
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, clippedPopupRect.x);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, clippedPopupRect.y);
                    renderer.onPaint(buffer, clippedPopupRect.x, clippedPopupRect.y, clippedPopupRect.width, clippedPopupRect.height);
                    popupGraphics = null;
                    popupSize = null;
                    popupDrawn = false;
                } else if (popupDrawn) {
                    ByteBuffer popupBuffer = popupGraphics;
                    int requiredPopupBufferSize = getRequiredBufferSize_MCEF(popupRect.width, popupRect.height);
                    if (popupBuffer == null || requiredPopupBufferSize <= 0 || popupBuffer.capacity() < requiredPopupBufferSize) {
                        return;
                    }

                    GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, popupRect.width);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, clippedPopupRect.x - popupRect.x);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, clippedPopupRect.y - popupRect.y);
                    renderer.onPaint(popupBuffer, clippedPopupRect.x, clippedPopupRect.y, clippedPopupRect.width, clippedPopupRect.height);
                }
            }
        } else {
            if (popupRect == null || popupRect.width <= 0 || popupRect.height <= 0 || !renderer.supportsDirtyRectUpload()) {
                return;
            }

            GlStateManager._bindTexture(renderer.getTextureID());

            ByteBuffer popupBuffer = popupGraphics;
            int requiredPopupBufferSize = getRequiredBufferSize_MCEF(popupRect.width, popupRect.height);
            if (requiredPopupBufferSize <= 0) {
                popupGraphics = null;
                popupDrawn = false;
                return;
            }

            if (popupBuffer == null || popupBuffer.capacity() != requiredPopupBufferSize) {
                popupBuffer = ByteBuffer.allocateDirect(requiredPopupBufferSize);
                popupGraphics = popupBuffer;
            }

            boolean paintedAnyRect = false;
            for (Rectangle dirtyRect : dirtyRects) {
                Rectangle clippedRect = clipRect_MCEF(dirtyRect, Math.min(width, popupRect.width), Math.min(height, popupRect.height));
                if (clippedRect == null) {
                    continue;
                }

                GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, clippedRect.x);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, clippedRect.y);
                renderer.onPaint(
                        buffer,
                        popupRect.x + clippedRect.x,
                        popupRect.y + clippedRect.y,
                        clippedRect.width,
                        clippedRect.height
                );

                copyRectRows_MCEF(
                        buffer,
                        width,
                        popupBuffer,
                        popupRect.width,
                        clippedRect
                );
                paintedAnyRect = true;
            }

            popupDrawn = paintedAnyRect;
        }
    }

    private static void copyRectRows_MCEF(ByteBuffer src, int srcWidth, ByteBuffer dst, int dstWidth, Rectangle rect) {
        long srcAddr = MemoryUtil.memAddress(src);
        long dstAddr = MemoryUtil.memAddress(dst);
        int bytesPerRow = rect.width << 2;
        for (int row = 0; row < rect.height; row++) {
            int srcOffset = ((rect.y + row) * srcWidth + rect.x) << 2;
            int dstOffset = ((rect.y + row) * dstWidth + rect.x) << 2;
            MemoryUtil.memCopy(srcAddr + srcOffset, dstAddr + dstOffset, bytesPerRow);
        }
    }

    private static Rectangle clipRect_MCEF(Rectangle rect, int maxWidth, int maxHeight) {
        if (rect == null || maxWidth <= 0 || maxHeight <= 0) {
            return null;
        }

        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int maxX = Math.min(maxWidth, rect.x + rect.width);
        int maxY = Math.min(maxHeight, rect.y + rect.height);
        int width = maxX - x;
        int height = maxY - y;

        if (width <= 0 || height <= 0) {
            return null;
        }

        return new Rectangle(x, y, width, height);
    }

    private static int getRequiredBufferSize_MCEF(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }

        long bufferSize = (long) width * height * 4L;
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) {
            return 0;
        }

        return (int) bufferSize;
    }

    private static ByteBuffer cloneBufferForAsyncPaint_MCEF(ByteBuffer source) {
        ByteBuffer sourceSlice = source.duplicate();
        sourceSlice.clear();

        ByteBuffer copy = MemoryUtil.memAlloc(sourceSlice.remaining());
        copy.put(sourceSlice);
        copy.flip();
        return copy;
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        updateModifierStateOnKeyPress_MCEF(keyCode);
        int normalizedModifiers = normalizeAltGrModifiers_MCEF(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) {
                    reload();
                    return;
                } else if (keyCode == GLFW_KEY_EQUAL) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                    return;
                } else if (keyCode == GLFW_KEY_MINUS) {
                    if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                    return;
                } else if (keyCode == GLFW_KEY_0) {
                    setZoomLevel(0);
                    return;
                }
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) {
                    goBack();
                    return;
                } else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) {
                    goForward();
                    return;
                }
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, normalizedModifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        int normalizedModifiers = normalizeAltGrModifiers_MCEF(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) return;
                else if (keyCode == GLFW_KEY_EQUAL) return;
                else if (keyCode == GLFW_KEY_MINUS) return;
                else if (keyCode == GLFW_KEY_0) return;
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) return;
                else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, normalizedModifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
        updateModifierStateOnKeyRelease_MCEF(keyCode);
    }

    public void sendKeyTyped(char c, int modifiers) {
        int normalizedModifiers = normalizeAltGrModifiers_MCEF(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if ((int) c == GLFW_KEY_R) return;
                else if ((int) c == GLFW_KEY_EQUAL) return;
                else if ((int) c == GLFW_KEY_MINUS) return;
                else if ((int) c == GLFW_KEY_0) return;
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if ((int) c == GLFW_KEY_LEFT && canGoBack()) return;
                else if ((int) c == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, normalizedModifiers);
        sendKeyEvent(e);
    }

    private void updateModifierStateOnKeyPress_MCEF(int keyCode) {
        if (keyCode == GLFW_KEY_RIGHT_ALT) {
            rightAltDown_MCEF = true;
        }
    }

    private void updateModifierStateOnKeyRelease_MCEF(int keyCode) {
        if (keyCode == GLFW_KEY_RIGHT_ALT) {
            rightAltDown_MCEF = false;
        }
    }

    private int normalizeAltGrModifiers_MCEF(int modifiers) {
        if (rightAltDown_MCEF && (modifiers & GLFW_MOD_ALT) == 0) {
            rightAltDown_MCEF = false;
        }

        // GLFW reports AltGr as Ctrl+Alt on many layouts.
        if (!rightAltDown_MCEF) {
            return modifiers;
        }

        if ((modifiers & GLFW_MOD_CONTROL) == 0 || (modifiers & GLFW_MOD_ALT) == 0) {
            return modifiers;
        }

        return modifiers & ~(GLFW_MOD_CONTROL | GLFW_MOD_ALT);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        CefMouseEvent e = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, 0, 0, dragContext.getVirtualModifiers(btnMask));
        sendMouseEvent(e);

        if (dragContext.isDragging())
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMousePress(int mouseX, int mouseY, int button) {
        // for some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0) btnMask |= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1) btnMask |= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2) btnMask |= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        // For some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);

        // drag&drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }
    }

    // TODO: smooth scrolling
    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        if (browserControls) {
            if ((modifiers & GLFW_MOD_CONTROL) != 0) {
                if (amount > 0) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                } else if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                return;
            }
        }

        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!MCEFPlatform.getPlatform().isMacOS()) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        CefMouseWheelEvent e = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, modifiers);
        sendMouseWheelEvent(e);
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation))
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) { // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(this, dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    public void close() {
        renderer.cleanup();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        Minecraft.getInstance().submit(renderer::cleanup);
        super.finalize();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        if (cursorType == CefCursorType.NONE) {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(), GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        } else {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().handle(), MCEF.getGLFWCursorHandle(cursorType));
        }
    }

    private static CefCursorType resolveCursorType_MCEF(int cursorTypeId) {
        CefCursorType[] cursorTypes = CefCursorType.values();
        if (cursorTypeId < 0 || cursorTypeId >= cursorTypes.length) {
            return CefCursorType.POINTER;
        }
        return cursorTypes[cursorTypeId];
    }

}
