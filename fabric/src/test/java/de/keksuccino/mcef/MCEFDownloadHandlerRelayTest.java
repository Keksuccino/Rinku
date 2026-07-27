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

package de.keksuccino.mcef;

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

class MCEFDownloadHandlerRelayTest {
    @Test
    void usesMcefSaveDialogFallbackWithoutAHandler() {
        MCEFDownloadHandlerRelay relay = new MCEFDownloadHandlerRelay();
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
        MCEFDownloadHandlerRelay relay = new MCEFDownloadHandlerRelay();
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
    void preservesLegacyBeforeDownloadHandlers() {
        MCEFDownloadHandlerRelay relay = new MCEFDownloadHandlerRelay();
        AtomicInteger legacyCalls = new AtomicInteger();
        relay.addHandler(new CefDownloadHandler() {
            @Override
            @Deprecated
            public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                legacyCalls.incrementAndGet();
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {}
        });

        assertTrue(relay.onBeforeDownloadWithDecision(null, null, "archive.zip", (path, showDialog) -> {}));
        assertEquals(1, legacyCalls.get());
    }

    @Test
    void keepsTheFirstRegisteredHandlerAsTheSoleCallbackOwner() {
        MCEFDownloadHandlerRelay relay = new MCEFDownloadHandlerRelay();
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
        MCEFDownloadHandlerRelay relay = new MCEFDownloadHandlerRelay();

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
