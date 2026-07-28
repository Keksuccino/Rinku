package de.keksuccino.rinku;

import org.cef.callback.CefDragData;
import org.cef.misc.CefCursorType;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class RinkuDragContext {

    private static final int NO_CURSOR_OVERRIDE = -1;
    private static final RinkuDragSessionController.Callbacks<CefDragData> NOOP_CALLBACKS = new RinkuDragSessionController.Callbacks<>() {
        @Override
        public void targetEnter(CefDragData resource, int x, int y, int modifiers, int allowedOperations) {}
        @Override
        public void targetDrop(int x, int y, int modifiers) {}
        @Override
        public void targetLeave() {}
        @Override
        public void sourceEndedAt(int x, int y, int operation) {}
        @Override
        public void sourceSystemDragEnded() {}
    };

    private final RinkuDragSessionController<CefDragData> dragSession = new RinkuDragSessionController<>(CefDragData::dispose, NO_CURSOR_OVERRIDE);
    private final RinkuDragSessionController.Callbacks<CefDragData> callbacks;
    private final AtomicInteger actualCursor = new AtomicInteger(NO_CURSOR_OVERRIDE);

    public RinkuDragContext() {
        this(NOOP_CALLBACKS);
    }

    RinkuDragContext(RinkuDragSessionController.Callbacks<CefDragData> callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    /**
     * Used to prevent re-selecting stuff while dragging
     * If the user is dragging, emulate having no buttons pressed
     *
     * @param btnMask the actual mask
     * @return a mask modified based on if the user is dragging
     */
    public int getVirtualModifiers(int btnMask) {
        return isDragging() ? 0 : btnMask;
    }

    /**
     * When the user is dragging, the browser-set cursor shouldn't be used
     * Instead the cursor should change based on what action would be performed when they release at the given location
     * However, the browser-set cursor also needs to be tracked, so this handles that as well
     *
     * @param cursorType the actual cursor type (should be the result of {@link RinkuDragContext#getActualCursor()} if you're just trying to see the current cursor)
     * @return the drag operation modified cursor if dragging, or the actual cursor if not
     */
    public int getVirtualCursor(int cursorType) {
        actualCursor.set(cursorType);
        return dragSession.virtualCursor(cursorType);
    }

    /**
     * Checks if a drag operation is currently happening
     *
     * @return true if the user is dragging, elsewise false
     */
    public boolean isDragging() {
        return dragSession.isDragging();
    }

    boolean isTransitioning() {
        return dragSession.isTransitioning();
    }

    /**
     * Gets the {@link CefDragData} of the current drag operation
     *
     * @return the current drag operation's data
     */
    public CefDragData getDragData() {
        return dragSession.resource();
    }

    /**
     * Gets the allowed operation mask for this drag event
     *
     * @return -1 for any, 0 for none, 1 for copy (TODO: others)
     */
    public int getMask() {
        return dragSession.allowedOperations();
    }

    /**
     * Gets the browser-set cursor
     *
     * @return the cursor that has been set by the browser, disregarding drag operations
     */
    public int getActualCursor() {
        return actualCursor.get();
    }

    public void startDragging(CefDragData dragData, int mask) {
        dragSession.start(dragData, mask, 0, 0, 0, callbacks);
    }

    public void stopDragging() {
        dragSession.cancel(callbacks);
    }

    /**
     * Clears and disposes the current drag operation. Repeated calls are safe no-ops.
     *
     * @return whether this call detached an active drag operation
     */
    public boolean reset() {
        return dragSession.cancel(callbacks);
    }

    public boolean updateCursor(int operation) {
        int cursorOverride = switch (operation) {
            case CefDragData.DragOperations.DRAG_OPERATION_NONE -> CefCursorType.NO_DROP.getId();
            case CefDragData.DragOperations.DRAG_OPERATION_COPY -> CefCursorType.COPY.getId();
            case CefDragData.DragOperations.DRAG_OPERATION_MOVE -> CefCursorType.MOVE.getId();
            default -> NO_CURSOR_OVERRIDE;
        };
        return dragSession.updateOperation(operation, cursorOverride);
    }

    int getCurrentVirtualCursor() {
        return dragSession.virtualCursor(actualCursor.get());
    }

    boolean startDraggingOwned(CefDragData dragData, int mask, int x, int y, int modifiers) {
        return dragSession.start(dragData, mask, x, y, modifiers, callbacks);
    }

    boolean finishDragging(int x, int y, int modifiers) {
        return dragSession.finish(x, y, modifiers, callbacks);
    }

    boolean cancelDrag() {
        return dragSession.cancel(callbacks);
    }

    void close() {
        dragSession.close(callbacks);
    }
}
