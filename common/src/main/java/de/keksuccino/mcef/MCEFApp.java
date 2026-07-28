package de.keksuccino.mcef;

import org.cef.CefApp;

/**
 * A wrapper around {@link CefApp}
 */
public class MCEFApp {
    private final CefApp handle;

    public MCEFApp(CefApp handle) {
        this.handle = handle;
    }

    public CefApp getHandle() {
        return handle;
    }
}
