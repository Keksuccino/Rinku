package de.keksuccino.mcef;

import de.keksuccino.mcef.listeners.MCEFCursorChangeListener;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");
    private static final int MAX_PENDING_PAINT_STREAMS_MCEF = 2;

    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    private final AsyncResourceLeaseManager<PaintSurface_MCEF, AsyncPaintFrame_MCEF> asyncPaintBufferLeases_MCEF = new AsyncResourceLeaseManager<>(frame -> MemoryUtil.memFree(frame.buffer()), AsyncPaintFrame_MCEF::requireFullUpload, MAX_PENDING_PAINT_STREAMS_MCEF);
    private final ReentrantLock paintCallbackLock_MCEF = new ReentrantLock();
    private final BrowserCloseController closeController_MCEF = new BrowserCloseController();
    private final AtomicBoolean deferredNativeClose_MCEF = new AtomicBoolean();
    private final PopupPaintState popupPaintState_MCEF = new PopupPaintState();
    private boolean rendererInitialized_MCEF;
    private boolean rendererCleanupStarted_MCEF;
    private int renderOperationDepth_MCEF;
    private final MCEFDragSessionController.Callbacks<CefDragData> dragCallbacks_MCEF = new MCEFDragSessionController.Callbacks<>() {
        @Override
        public void targetEnter(CefDragData dragData, int x, int y, int modifiers, int allowedOperations) {
            MCEFBrowser.this.dragTargetDragEnter(dragData, new Point(x, y), modifiers, allowedOperations);
        }

        @Override
        public void targetDrop(int x, int y, int modifiers) {
            MCEFBrowser.this.dragTargetDrop(new Point(x, y), modifiers);
        }

        @Override
        public void targetLeave() {
            MCEFBrowser.this.dragTargetDragLeave();
        }

        @Override
        public void sourceEndedAt(int x, int y, int operation) {
            MCEFBrowser.this.dragSourceEndedAt(new Point(x, y), operation);
        }

        @Override
        public void sourceSystemDragEnded() {
            MCEFBrowser.this.dragSourceSystemDragEnded();
        }
    };
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext(dragCallbacks_MCEF);
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
        if (!MCEFRenderCoordinator.register(this)) {
            IllegalStateException registrationFailure = new IllegalStateException("Cannot create an MCEF browser after render shutdown has started");
            try {
                closeBrowser_MCEF(false);
            } catch (Throwable lifecycleFailure) {
                addSuppressed_MCEF(registrationFailure, lifecycleFailure);
            }
            throw registrationFailure;
        }
        if (RenderSystem.isOnRenderThread()) {
            initializeRendererOnRenderThread_MCEF();
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
        paintCallbackLock_MCEF.lock();
        try {
            super.onPopupShow(browser, show);
            showPopup = show;
            if (popupPaintState_MCEF.updateVisibility(show)) {
                popupDrawn = false;
                requestPopupStateResync_MCEF();
            }
        } finally {
            paintCallbackLock_MCEF.unlock();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        paintCallbackLock_MCEF.lock();
        try {
            super.onPopupSize(browser, size);
            boolean geometryChanged = popupPaintState_MCEF.updateGeometry(size);
            popupSize = popupPaintState_MCEF.geometry();
            if (!geometryChanged) {
                return;
            }

            popupDrawn = false;
            try {
                if (popupSize == null) {
                    popupGraphics = null;
                    return;
                }
                int popupBufferSize = getRequiredBufferSize_MCEF(popupSize.width, popupSize.height);
                if (popupBufferSize <= 0) {
                    popupGraphics = null;
                    return;
                }
                if (popupGraphics == null || popupGraphics.capacity() != popupBufferSize) {
                    popupGraphics = ByteBuffer.allocateDirect(popupBufferSize);
                }
            } finally {
                requestPopupStateResync_MCEF();
            }
        } finally {
            paintCallbackLock_MCEF.unlock();
        }
    }

    private void requestPopupStateResync_MCEF() {
        asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.VIEW);
        asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
        if (asyncPaintBufferLeases_MCEF.isAccepting()) {
            invalidate();
        }
    }

    // Graphics
    /**
     * Paint listeners are notified only after MCEF has consumed the callback buffer synchronously or has copied it
     * into the bounded latest-frame mailbox. Replaced pending frames are coalesced into a full upload of the newest
     * full-frame copy for that view or popup stream.
     */
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (dirtyRects == null || dirtyRects.length == 0 || buffer == null) return;
        if (onPaint_MCEF(popup, dirtyRects, buffer, width, height)) {
            // The base class gives listeners isolated callback-scoped views.
            super.onPaint(browser, popup, dirtyRects, buffer, width, height);
        }
    }

    private boolean onPaint_MCEF(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        paintCallbackLock_MCEF.lock();
        try {
            if (!asyncPaintBufferLeases_MCEF.isAccepting() || closeController_MCEF.isCloseRequested()) {
                return false;
            }
            if (RenderSystem.isOnRenderThread()) {
                // Apply older copied callbacks first so this callback can safely upload only its dirty regions.
                pumpAsyncPaintsOnRenderThread_MCEF();
                if (closeController_MCEF.isCloseRequested()) {
                    return false;
                }
                Rectangle[] dirtyRectsCopy = copyDirtyRects_MCEF(dirtyRects);
                Rectangle popupRectSnapshot = popupPaintState_MCEF.geometry();
                boolean showPopupSnapshot = popupPaintState_MCEF.visible();
                long popupStateGeneration = popupPaintState_MCEF.generation();
                PaintSurface_MCEF surface = PaintSurface_MCEF.fromPopup(popup);
                boolean forceFullUpload = asyncPaintBufferLeases_MCEF.consumeResync(surface);
                beginRenderOperation_MCEF();
                try {
                    onPaintRenderThread_MCEF(popup, dirtyRectsCopy, buffer, width, height, popupRectSnapshot, showPopupSnapshot, popupStateGeneration, forceFullUpload);
                } catch (Throwable failure) {
                    if (popup) {
                        invalidateRetainedPopupPixels_MCEF();
                    }
                    asyncPaintBufferLeases_MCEF.requireResync(surface);
                    throw failure;
                } finally {
                    endRenderOperation_MCEF();
                }
                return !closeController_MCEF.isCloseRequested();
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || !minecraft.isRunning()) {
                return false;
            }
            PaintSurface_MCEF surface = PaintSurface_MCEF.fromPopup(popup);
            return asyncPaintBufferLeases_MCEF.offer(surface, () -> createAsyncPaintFrame_MCEF(surface, dirtyRects, buffer, width, height), this::renderAsyncPaintFrame_MCEF, this::logAsyncPaintFailure_MCEF);
        } finally {
            paintCallbackLock_MCEF.unlock();
        }
    }

    private void logAsyncPaintFailure_MCEF(Throwable failure) {
        LOGGER.warn("Asynchronous browser paint failed.", failure);
    }

    private AsyncPaintFrame_MCEF createAsyncPaintFrame_MCEF(PaintSurface_MCEF surface, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        ByteBuffer bufferCopy = cloneBufferForAsyncPaint_MCEF(buffer);
        try {
            Rectangle popupRectSnapshot = popupPaintState_MCEF.geometry();
            return new AsyncPaintFrame_MCEF(surface, copyDirtyRects_MCEF(dirtyRects), bufferCopy, width, height, popupRectSnapshot, popupPaintState_MCEF.visible(), popupPaintState_MCEF.generation());
        } catch (Throwable failure) {
            MemoryUtil.memFree(bufferCopy);
            throw failure;
        }
    }

    private void renderAsyncPaintFrame_MCEF(AsyncPaintFrame_MCEF frame) {
        paintCallbackLock_MCEF.lock();
        try {
            Rectangle popupRect = frame.popupRect();
            boolean showPopupSnapshot = frame.showPopup();
            long popupStateGeneration = frame.popupStateGeneration();
            boolean forceFullUpload = frame.requiresFullUpload();
            if (!popupPaintState_MCEF.isCurrentGeneration(popupStateGeneration)) {
                // Popup pixels belong to one popup geometry; never apply them after that geometry has changed.
                if (frame.surface() == PaintSurface_MCEF.POPUP) {
                    asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
                    return;
                }
                popupRect = popupPaintState_MCEF.geometry();
                showPopupSnapshot = popupPaintState_MCEF.visible();
                popupStateGeneration = popupPaintState_MCEF.generation();
                forceFullUpload = true;
            }
            try {
                onPaintRenderThread_MCEF(frame.surface() == PaintSurface_MCEF.POPUP, frame.dirtyRects(), frame.buffer(), frame.width(), frame.height(), popupRect, showPopupSnapshot, popupStateGeneration, forceFullUpload);
            } catch (Throwable failure) {
                if (frame.surface() == PaintSurface_MCEF.POPUP) {
                    invalidateRetainedPopupPixels_MCEF();
                }
                throw failure;
            }
        } finally {
            paintCallbackLock_MCEF.unlock();
        }
    }

    void pumpAsyncPaintsOnRenderThread_MCEF() {
        RenderSystem.assertOnRenderThread();
        if (rendererCleanupStarted_MCEF) {
            return;
        }
        if (closeController_MCEF.isCloseRequested()) {
            cleanupBrowserResourcesOnRenderThread_MCEF();
            return;
        }
        initializeRendererOnRenderThread_MCEF();
        beginRenderOperation_MCEF();
        try {
            asyncPaintBufferLeases_MCEF.drain(MAX_PENDING_PAINT_STREAMS_MCEF);
        } finally {
            endRenderOperation_MCEF();
        }
    }

    void shutdownOnRenderThread_MCEF() {
        RenderSystem.assertOnRenderThread();
        closeBrowser_MCEF(true);
    }

    private void onPaintRenderThread_MCEF(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height, Rectangle popupRect, boolean showPopupSnapshot, long popupStateGeneration, boolean forceFullUpload) {
        if (!popup) {
            if (forceFullUpload || lastWidth != width || lastHeight != height || !renderer.supportsDirtyRectUpload()) {
                lastWidth = width;
                lastHeight = height;
                renderer.onPaint(buffer, width, height);
                restorePopupAfterViewPaint_MCEF(width, height, popupRect, showPopupSnapshot, popupStateGeneration);
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

            restorePopupAfterViewPaint_MCEF(width, height, popupRect, showPopupSnapshot, popupStateGeneration);
        } else {
            if (!popupPaintState_MCEF.acceptsPaint(popupStateGeneration, popupRect, showPopupSnapshot, width, height) || !renderer.supportsDirtyRectUpload()) {
                asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
                return;
            }

            int requiredPopupBufferSize = getRequiredBufferSize_MCEF(popupRect.width, popupRect.height);
            if (requiredPopupBufferSize <= 0 || buffer.capacity() < requiredPopupBufferSize) {
                asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
                return;
            }

            ByteBuffer popupBuffer = popupGraphics;
            if (popupBuffer == null || popupBuffer.capacity() != requiredPopupBufferSize) {
                popupBuffer = ByteBuffer.allocateDirect(requiredPopupBufferSize);
                popupGraphics = popupBuffer;
                invalidateRetainedPopupPixels_MCEF();
                forceFullUpload = true;
            }

            forceFullUpload = forceFullUpload || popupPaintState_MCEF.requiresFullPaint(popupStateGeneration, popupRect, showPopupSnapshot);
            boolean copiedCompleteFullFrame = false;
            Rectangle[] paintRects = forceFullUpload ? new Rectangle[]{new Rectangle(0, 0, width, height)} : dirtyRects;
            for (Rectangle dirtyRect : paintRects) {
                PopupPaintGeometry.PaintPlan paintPlan = PopupPaintGeometry.plan(dirtyRect, width, height, popupRect.x, popupRect.y, renderer.getTextureWidth(), renderer.getTextureHeight());
                if (paintPlan == null) {
                    continue;
                }

                // Retention stays in popup-local callback space and must include pixels which are currently offscreen.
                copyRectRows_MCEF(buffer, width, popupBuffer, popupRect.width, paintPlan.retainedSource());
                copiedCompleteFullFrame |= paintPlan.completeSourceFrame();

                PopupPaintGeometry.Upload upload = paintPlan.upload();
                if (upload == null) {
                    continue;
                }
                PopupPaintGeometry.Region uploadSource = upload.source();
                PopupPaintGeometry.Region uploadDestination = upload.destination();
                GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, uploadSource.x());
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, uploadSource.y());
                renderer.onPaint(buffer, uploadDestination.x(), uploadDestination.y(), uploadDestination.width(), uploadDestination.height());
            }

            // Full retained callback pixels are valid even when the popup had no visible destination pixels to upload.
            if (forceFullUpload && (!copiedCompleteFullFrame || !popupPaintState_MCEF.markFullPainted(popupStateGeneration, popupRect, showPopupSnapshot, width, height))) {
                invalidateRetainedPopupPixels_MCEF();
                asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
                return;
            }
            popupDrawn = popupPaintState_MCEF.canComposite(popupStateGeneration, popupRect, showPopupSnapshot);
        }
    }

    private void restorePopupAfterViewPaint_MCEF(int viewWidth, int viewHeight, Rectangle popupRect, boolean showPopupSnapshot, long popupStateGeneration) {
        if (!popupDrawn || !popupPaintState_MCEF.canComposite(popupStateGeneration, popupRect, showPopupSnapshot)) {
            return;
        }
        PopupPaintGeometry.PaintPlan paintPlan = PopupPaintGeometry.plan(new Rectangle(0, 0, popupRect.width, popupRect.height), popupRect.width, popupRect.height, popupRect.x, popupRect.y, viewWidth, viewHeight);
        if (paintPlan == null || paintPlan.upload() == null) {
            return;
        }
        ByteBuffer popupBuffer = popupGraphics;
        int requiredPopupBufferSize = getRequiredBufferSize_MCEF(popupRect.width, popupRect.height);
        if (popupBuffer == null || requiredPopupBufferSize <= 0 || popupBuffer.capacity() < requiredPopupBufferSize) {
            invalidateRetainedPopupPixels_MCEF();
            asyncPaintBufferLeases_MCEF.requireResync(PaintSurface_MCEF.POPUP);
            return;
        }
        PopupPaintGeometry.Upload upload = paintPlan.upload();
        PopupPaintGeometry.Region uploadSource = upload.source();
        PopupPaintGeometry.Region uploadDestination = upload.destination();
        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, popupRect.width);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, uploadSource.x());
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, uploadSource.y());
        renderer.onPaint(popupBuffer, uploadDestination.x(), uploadDestination.y(), uploadDestination.width(), uploadDestination.height());
    }

    private void invalidateRetainedPopupPixels_MCEF() {
        popupPaintState_MCEF.invalidateRetainedPixels();
        popupDrawn = false;
    }

    private static Rectangle[] copyDirtyRects_MCEF(Rectangle[] dirtyRects) {
        Rectangle[] copy = new Rectangle[dirtyRects.length];
        for (int i = 0; i < dirtyRects.length; i++) {
            Rectangle dirtyRect = dirtyRects[i];
            copy[i] = dirtyRect == null ? null : new Rectangle(dirtyRect);
        }
        return copy;
    }

    private static void copyRectRows_MCEF(ByteBuffer src, int srcWidth, ByteBuffer dst, int dstWidth, PopupPaintGeometry.Region rect) {
        long srcAddr = MemoryUtil.memAddress(src);
        long dstAddr = MemoryUtil.memAddress(dst);
        int bytesPerRow = rect.width() << 2;
        for (int row = 0; row < rect.height(); row++) {
            int srcOffset = ((rect.y() + row) * srcWidth + rect.x()) << 2;
            int dstOffset = ((rect.y() + row) * dstWidth + rect.x()) << 2;
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
        try {
            copy.put(sourceSlice);
            copy.flip();
            return copy;
        } catch (Throwable failure) {
            MemoryUtil.memFree(copy);
            throw failure;
        }
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
    /**
     * MCEF is both the source and target for its emulated OSR drag. CEF requires every target
     * callback to precede source completion: a drop uses TargetDrop -> SourceEndedAt ->
     * SourceSystemDragEnded, while cancellation uses the explicitly permitted TargetLeave ->
     * SourceSystemDragEnded path. TargetLeave is not sent after a successful drop because CEF
     * defines leave and drop as alternative target endings.
     */
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        try {
            boolean handled = dragContext.startDraggingOwned(dragData, mask, x, y, btnMask);
            if (!handled && !dragContext.isDragging()) restoreActualCursorAfterFailedStart_MCEF(null);
            completeDeferredNativeCloseAfterStart_MCEF(null);
            return handled;
        } catch (RuntimeException | Error failure) {
            if (!dragContext.isDragging()) restoreActualCursorAfterFailedStart_MCEF(failure);
            completeDeferredNativeCloseAfterStart_MCEF(failure);
            throw failure;
        }
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) notifyCursorChange_MCEF(this, dragContext.getCurrentVirtualCursor());
        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) { // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(this, dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        completeDragLifecycle_MCEF(() -> dragContext.finishDragging(x, y, btnMask));
    }

    public void cancelDrag() {
        completeDragLifecycle_MCEF(dragContext::cancelDrag);
    }

    private void completeDragLifecycle_MCEF(DragCompletion_MCEF completion) {
        boolean wasDragging = dragContext.isDragging();
        Throwable failure = null;
        try {
            completion.complete();
        } catch (RuntimeException | Error completionFailure) {
            failure = completionFailure;
        }
        if (wasDragging && !dragContext.isDragging()) {
            try {
                notifyCursorChange_MCEF(this, dragContext.getActualCursor());
            } catch (RuntimeException | Error cursorFailure) {
                failure = mergeFailure_MCEF(failure, cursorFailure);
            }
        }
        try {
            completeDeferredNativeClose_MCEF();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeFailure_MCEF(failure, closeFailure);
        }
        rethrowLifecycleFailure_MCEF(failure);
    }

    private void completeDeferredNativeCloseAfterStart_MCEF(Throwable primaryFailure) {
        try {
            completeDeferredNativeClose_MCEF();
        } catch (RuntimeException | Error closeFailure) {
            if (primaryFailure == null) {
                LOGGER.warn("Failed to continue a browser close after rejecting an in-progress drag.", closeFailure);
            } else {
                addSuppressed_MCEF(primaryFailure, closeFailure);
            }
        }
    }

    private void restoreActualCursorAfterFailedStart_MCEF(Throwable primaryFailure) {
        try {
            notifyCursorChange_MCEF(this, dragContext.getActualCursor());
        } catch (RuntimeException | Error cursorFailure) {
            if (primaryFailure == null) {
                LOGGER.warn("Failed to restore the browser cursor after rejecting a drag.", cursorFailure);
            } else {
                addSuppressed_MCEF(primaryFailure, cursorFailure);
            }
        }
    }

    // Closing
    public void close() {
        close(true);
    }

    @Override
    public void close(boolean force) {
        if (!force) {
            // A before-unload handler may cancel a non-forced close. Keep paint admission and GL
            // resources alive until CEF confirms terminal closure through onBeforeClose().
            super.close(false);
            return;
        }
        closeBrowser_MCEF(RenderSystem.isOnRenderThread());
    }

    @Override
    public void onBeforeClose() {
        Throwable failure = null;
        try {
            requestClose_MCEF();
        } catch (Throwable closeRequestFailure) {
            failure = closeRequestFailure;
        }
        closeController_MCEF.markNativeClosed();
        if (RenderSystem.isOnRenderThread()) {
            try {
                cleanupBrowserResourcesOnRenderThread_MCEF();
            } catch (Throwable cleanupFailure) {
                failure = mergeFailure_MCEF(failure, cleanupFailure);
            }
        }
        try {
            super.onBeforeClose();
        } catch (Throwable nativeLifecycleFailure) {
            failure = mergeFailure_MCEF(failure, nativeLifecycleFailure);
        }
        rethrowLifecycleFailure_MCEF(failure);
    }

    private void requestClose_MCEF() {
        closeController_MCEF.requestClose(this::stopBrowserResourceAdmission_MCEF);
    }

    private void stopBrowserResourceAdmission_MCEF() {
        Throwable failure = null;
        try {
            asyncPaintBufferLeases_MCEF.close();
        } catch (RuntimeException | Error paintFailure) {
            failure = paintFailure;
        }
        deferredNativeClose_MCEF.set(true);
        try {
            // Forced close reaches this path before super.close(true) makes CefBrowser_N reject
            // drag callbacks. If close re-enters from one of those callbacks, native close must be
            // deferred until the outer transition sends every target/source completion callback.
            dragContext.close();
        } catch (RuntimeException | Error dragFailure) {
            failure = mergeFailure_MCEF(failure, dragFailure);
        } finally {
            if (!dragContext.isTransitioning()) deferredNativeClose_MCEF.set(false);
        }
        rethrowLifecycleFailure_MCEF(failure);
    }

    private void closeNativeBrowser_MCEF() {
        closeController_MCEF.closeNative(() -> super.close(true));
    }

    private void completeDeferredNativeClose_MCEF() {
        if (dragContext.isTransitioning() || !deferredNativeClose_MCEF.compareAndSet(true, false)) return;
        closeNativeBrowser_MCEF();
    }

    private void closeBrowser_MCEF(boolean cleanupRenderer) {
        Throwable failure = null;
        try {
            requestClose_MCEF();
        } catch (Throwable closeRequestFailure) {
            failure = closeRequestFailure;
        }
        if (cleanupRenderer) {
            try {
                cleanupBrowserResourcesOnRenderThread_MCEF();
            } catch (Throwable cleanupFailure) {
                failure = mergeFailure_MCEF(failure, cleanupFailure);
            }
        }
        if (!deferredNativeClose_MCEF.get()) {
            try {
                closeNativeBrowser_MCEF();
            } catch (Throwable nativeCloseFailure) {
                failure = mergeFailure_MCEF(failure, nativeCloseFailure);
            }
        }
        rethrowLifecycleFailure_MCEF(failure);
    }

    private void initializeRendererOnRenderThread_MCEF() {
        RenderSystem.assertOnRenderThread();
        if (rendererInitialized_MCEF || rendererCleanupStarted_MCEF || closeController_MCEF.isCloseRequested()) {
            return;
        }
        try {
            renderer.initialize();
            rendererInitialized_MCEF = true;
        } catch (Throwable initializationFailure) {
            try {
                closeBrowser_MCEF(true);
            } catch (Throwable lifecycleFailure) {
                addSuppressed_MCEF(initializationFailure, lifecycleFailure);
            }
            throw initializationFailure;
        }
    }

    private void beginRenderOperation_MCEF() {
        RenderSystem.assertOnRenderThread();
        if (rendererCleanupStarted_MCEF) {
            throw new IllegalStateException("Browser renderer has already been cleaned up");
        }
        renderOperationDepth_MCEF++;
    }

    private void endRenderOperation_MCEF() {
        renderOperationDepth_MCEF--;
        if (renderOperationDepth_MCEF < 0) {
            renderOperationDepth_MCEF = 0;
            throw new IllegalStateException("Browser render operation depth became negative");
        }
        if (renderOperationDepth_MCEF == 0 && closeController_MCEF.isCloseRequested()) {
            cleanupBrowserResourcesOnRenderThread_MCEF();
        }
    }

    private void cleanupBrowserResourcesOnRenderThread_MCEF() {
        RenderSystem.assertOnRenderThread();
        if (rendererCleanupStarted_MCEF || renderOperationDepth_MCEF > 0) {
            return;
        }
        rendererCleanupStarted_MCEF = true;
        Throwable failure = null;
        try {
            // Cleanup is safe before successful initialization and is required when initialization failed partway.
            renderer.cleanup();
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            dragContext.close();
        } catch (Throwable dragCleanupFailure) {
            failure = mergeFailure_MCEF(failure, dragCleanupFailure);
        }
        try {
            if (cursorChangeListener != null) {
                cursorChangeListener.onCursorChange(0);
            }
        } catch (Throwable cursorFailure) {
            if (failure == null) {
                failure = cursorFailure;
            } else if (failure != cursorFailure) {
                failure.addSuppressed(cursorFailure);
            }
        } finally {
            MCEFRenderCoordinator.unregister(this);
        }
        if (failure != null) {
            LOGGER.warn("Failed to clean up browser render-thread resources.", failure);
        }
    }

    private static void addSuppressed_MCEF(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    private static Throwable mergeFailure_MCEF(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure == null) {
            return secondaryFailure;
        }
        addSuppressed_MCEF(primaryFailure, secondaryFailure);
        return primaryFailure;
    }

    private static void rethrowLifecycleFailure_MCEF(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        if (failure != null) {
            throw new IllegalStateException("Browser lifecycle action failed", failure);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            requestClose_MCEF();
        } finally {
            super.finalize();
        }
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return notifyCursorChange_MCEF(browser, dragContext.getVirtualCursor(cursorType));
    }

    private boolean notifyCursorChange_MCEF(CefBrowser browser, int cursorType) {
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
        return CefCursorType.fromId(cursorTypeId);
    }

    @FunctionalInterface
    private interface DragCompletion_MCEF {
        boolean complete();
    }

    private enum PaintSurface_MCEF {
        VIEW,
        POPUP;

        private static PaintSurface_MCEF fromPopup(boolean popup) {
            return popup ? POPUP : VIEW;
        }
    }

    private static final class AsyncPaintFrame_MCEF {
        private final PaintSurface_MCEF surface;
        private final Rectangle[] dirtyRects;
        private final ByteBuffer buffer;
        private final int width;
        private final int height;
        private final Rectangle popupRect;
        private final boolean showPopup;
        private final long popupStateGeneration;
        private volatile boolean fullUpload;

        private AsyncPaintFrame_MCEF(PaintSurface_MCEF surface, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height, Rectangle popupRect, boolean showPopup, long popupStateGeneration) {
            this.surface = surface;
            this.dirtyRects = dirtyRects;
            this.buffer = buffer;
            this.width = width;
            this.height = height;
            this.popupRect = popupRect;
            this.showPopup = showPopup;
            this.popupStateGeneration = popupStateGeneration;
        }

        private PaintSurface_MCEF surface() {
            return surface;
        }

        private Rectangle[] dirtyRects() {
            return dirtyRects;
        }

        private ByteBuffer buffer() {
            return buffer;
        }

        private int width() {
            return width;
        }

        private int height() {
            return height;
        }

        private Rectangle popupRect() {
            return popupRect;
        }

        private boolean showPopup() {
            return showPopup;
        }

        private long popupStateGeneration() {
            return popupStateGeneration;
        }

        private void requireFullUpload() {
            fullUpload = true;
        }

        private boolean requiresFullUpload() {
            return fullUpload;
        }
    }

}
