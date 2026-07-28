package de.keksuccino.mcef;

import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Internal render-frame bridge used by the Minecraft mixin. */
public final class MCEFRenderCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RenderThreadMailboxCoordinator<MCEFBrowser> BROWSERS = new RenderThreadMailboxCoordinator<>();

    private MCEFRenderCoordinator() {}

    static boolean register(MCEFBrowser browser) {
        return BROWSERS.register(browser);
    }

    static void unregister(MCEFBrowser browser) {
        BROWSERS.unregister(browser);
    }

    public static void pumpOnRenderThread() {
        RenderSystem.assertOnRenderThread();
        BROWSERS.pump(MCEFBrowser::pumpAsyncPaintsOnRenderThread, MCEFRenderCoordinator::logBrowserFailure);
    }

    public static void shutdownOnRenderThread() {
        RenderSystem.assertOnRenderThread();
        BROWSERS.shutdown(MCEFBrowser::shutdownOnRenderThread, MCEFRenderCoordinator::logBrowserFailure);
    }

    private static void logBrowserFailure(MCEFBrowser browser, Throwable failure) {
        LOGGER.warn("MCEF browser render-thread lifecycle operation failed.", failure);
    }

}
