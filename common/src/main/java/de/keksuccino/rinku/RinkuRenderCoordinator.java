package de.keksuccino.rinku;

import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Internal render-frame bridge used by the Minecraft mixin. */
public final class RinkuRenderCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RenderThreadMailboxCoordinator<RinkuBrowser> BROWSERS = new RenderThreadMailboxCoordinator<>();

    private RinkuRenderCoordinator() {}

    static boolean register(RinkuBrowser browser) {
        return BROWSERS.register(browser);
    }

    static void unregister(RinkuBrowser browser) {
        BROWSERS.unregister(browser);
    }

    public static void pumpOnRenderThread() {
        RenderSystem.assertOnRenderThread();
        BROWSERS.pump(RinkuBrowser::pumpAsyncPaintsOnRenderThread, RinkuRenderCoordinator::logBrowserFailure);
    }

    public static void shutdownOnRenderThread() {
        RenderSystem.assertOnRenderThread();
        BROWSERS.shutdown(RinkuBrowser::shutdownOnRenderThread, RinkuRenderCoordinator::logBrowserFailure);
    }

    private static void logBrowserFailure(RinkuBrowser browser, Throwable failure) {
        LOGGER.warn("Rinku browser render-thread lifecycle operation failed.", failure);
    }

}
