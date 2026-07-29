package de.keksuccino.rinku;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuDownloadHandlerRelayTest {
    @Test
    void usesRinkuSaveDialogFallbackWithoutAHandler() {
        RinkuDownloadHandlerRelay relay = new RinkuDownloadHandlerRelay();
        AtomicReference<String> continuedPath = new AtomicReference<>();
        AtomicBoolean showedDialog = new AtomicBoolean();
        CefBeforeDownloadCallback callback = (path, showDialog) -> {
            continuedPath.set(path);
            showedDialog.set(showDialog);
        };

        assertTrue(relay.canDownload(null, "https://example.test/archive.zip", "GET"));
        assertTrue(relay.onBeforeDownloadWithDecision(null, null, null, callback));

        assertEquals("", continuedPath.get());
        assertTrue(showedDialog.get());
    }

    @Test
    void forwardsModernDownloadDecisionsAndArguments() {
        RinkuDownloadHandlerRelay relay = new RinkuDownloadHandlerRelay();
        AtomicReference<String> receivedUrl = new AtomicReference<>();
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<CefBeforeDownloadCallback> receivedCallback = new AtomicReference<>();
        CefBeforeDownloadCallback callback = (path, showDialog) -> {};
        relay.addHandler(new CefDownloadHandler() {
            @Override
            public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
                receivedUrl.set(url);
                receivedMethod.set(requestMethod);
                return false;
            }

            @Override
            public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                receivedCallback.set(callback);
                return false;
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {}
        });

        assertFalse(relay.canDownload(null, "https://example.test/archive.zip", "POST"));
        assertFalse(relay.onBeforeDownloadWithDecision(null, null, "archive.zip", callback));
        assertEquals("https://example.test/archive.zip", receivedUrl.get());
        assertEquals("POST", receivedMethod.get());
        assertSame(callback, receivedCallback.get());
    }

    @Test
    void keepsTheFirstRegisteredHandlerAsTheSoleCallbackOwner() {
        RinkuDownloadHandlerRelay relay = new RinkuDownloadHandlerRelay();
        AtomicInteger firstUpdates = new AtomicInteger();
        AtomicInteger secondUpdates = new AtomicInteger();
        relay.addHandler(new UpdatingHandler(firstUpdates));
        relay.addHandler(new UpdatingHandler(secondUpdates));
        relay.addHandler(null);

        relay.onDownloadUpdated(null, null, null);

        assertEquals(1, firstUpdates.get());
        assertEquals(0, secondUpdates.get());
    }

    @Test
    void ignoresUpdatesWithoutAHandler() {
        RinkuDownloadHandlerRelay relay = new RinkuDownloadHandlerRelay();

        assertDoesNotThrow(() -> relay.onDownloadUpdated(null, null, null));
    }

    private static final class UpdatingHandler implements CefDownloadHandler {
        private final AtomicInteger updates;

        private UpdatingHandler(AtomicInteger updates) {
            this.updates = updates;
        }

        @Override
        public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
            updates.incrementAndGet();
        }
    }
}
