package de.keksuccino.rinku;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandler;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Relays download callbacks to the first registered owner.
 *
 * <p>Download callbacks transfer ownership of one-shot continuation objects, so broadcasting them
 * would allow multiple handlers to complete the same callback. The atomic first-writer-wins slot
 * also makes handlers registered outside CEF's UI thread safely visible to native callbacks.
 */
final class RinkuDownloadHandlerRelay implements CefDownloadHandler {
    private final AtomicReference<CefDownloadHandler> handler = new AtomicReference<>();

    void addHandler(CefDownloadHandler handler) {
        if (handler != null) this.handler.compareAndSet(null, handler);
    }

    @Override
    public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
        CefDownloadHandler currentHandler = handler.get();
        return currentHandler == null || currentHandler.canDownload(browser, url, requestMethod);
    }

    @Override
    public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        CefDownloadHandler currentHandler = handler.get();
        if (currentHandler != null) return currentHandler.onBeforeDownloadWithDecision(browser, downloadItem, suggestedName, callback);
        continueWithSaveDialog(suggestedName, callback);
        return true;
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
        CefDownloadHandler currentHandler = handler.get();
        if (currentHandler != null) currentHandler.onDownloadUpdated(browser, downloadItem, callback);
    }

    private static void continueWithSaveDialog(String suggestedName, CefBeforeDownloadCallback callback) {
        // Passing true preserves Rinku's established Save As behavior; canceling the dialog cancels
        // the download while still satisfying CEF's one-owner callback contract.
        callback.Continue(suggestedName == null ? "" : suggestedName, true);
    }
}
