package de.keksuccino.rinku;

import org.cef.CefApp;

/**
 * A wrapper around {@link CefApp}
 */
public class RinkuApp {
    private final CefApp handle;

    public RinkuApp(CefApp handle) {
        this.handle = handle;
    }

    public CefApp getHandle() {
        return handle;
    }
}
