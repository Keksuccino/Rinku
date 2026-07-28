package de.keksuccino.rinku;

import de.keksuccino.rinku.listeners.RinkuInitListener;
import de.keksuccino.rinku.mixins.MixinGui;
import com.mojang.blaze3d.systems.RenderSystem;
import de.keksuccino.rinku.platform.Services;
import de.keksuccino.rinku.util.CefUtil;
import net.minecraft.client.Minecraft;
import org.cef.misc.CefCursorType;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.*;

public final class Rinku {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "rinku";
    public static final String VERSION = "3.0.0";

    private static final RinkuInitializationController INITIALIZATION_CONTROLLER = new RinkuInitializationController();
    private static final HashMap<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new HashMap<>();
    private static final ArrayList<RinkuInitListener> awaitingInit = new ArrayList<>();
    private static final String PRELOADED_BROWSER_START_URL = "about:blank";
    private static final Object PRELOADED_BROWSER_POOL_LOCK = new Object();
    private static final Deque<RinkuBrowser> PRELOADED_TRANSPARENT_BROWSERS = new ArrayDeque<>();
    private static final Deque<RinkuBrowser> PRELOADED_OPAQUE_BROWSERS = new ArrayDeque<>();

    private static RinkuSettings settings;
    private static volatile RinkuApp app;
    private static volatile RinkuClient client;
    private static int preloadTransparentInFlight;
    private static int preloadOpaqueInFlight;
    private static boolean preloadPoolClosed = true;

    /**
     * This gets called by {@link MixinGui}.
     * This should not be called by anything else.
     */
    public static boolean initialize() {

        synchronized (INITIALIZATION_CONTROLLER) {

            RinkuInitializationController.BeginResult beginResult = INITIALIZATION_CONTROLLER.beginInitialization();

            if (beginResult == RinkuInitializationController.BeginResult.ALREADY_INITIALIZED) return true;
            if (beginResult == RinkuInitializationController.BeginResult.REJECTED) return false;

            try {

                LOGGER.info("[RINKU] Loading v" + Rinku.VERSION + " on " + Services.PLATFORM.getPlatformDisplayName() + " (" + OSPlatform.getPlatform().getNormalizedName() + ")..");

                if (CefUtil.init()) {

                    app = new RinkuApp(CefUtil.getCefApp());
                    client = new RinkuClient(CefUtil.getCefClient());

                    INITIALIZATION_CONTROLLER.markInitialized();

                    List<RinkuBrowser> stalePreloadedBrowsers = new ArrayList<>();
                    synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                        stalePreloadedBrowsers.addAll(PRELOADED_TRANSPARENT_BROWSERS);
                        stalePreloadedBrowsers.addAll(PRELOADED_OPAQUE_BROWSERS);
                        PRELOADED_TRANSPARENT_BROWSERS.clear();
                        PRELOADED_OPAQUE_BROWSERS.clear();
                        preloadTransparentInFlight = 0;
                        preloadOpaqueInFlight = 0;
                        preloadPoolClosed = false;
                    }
                    for (RinkuBrowser browser : stalePreloadedBrowsers) closeBrowserQuietly(browser);

                    awaitingInit.forEach(t -> t.onInit(true));
                    awaitingInit.clear();

                    LOGGER.info("[RINKU] Successfully initialized!");

                    app.getHandle().registerSchemeHandlerFactory("mod", "", (browser, frame, url, request) -> new ModScheme(request.getURL()));
                    prefillPreloadedBrowserPoolsAsync();

                    // These callbacks are important because JCEF helper processes can otherwise
                    // survive the game. Rinku's lifecycle gate makes every shutdown path idempotent.
                    OSPlatform platform = OSPlatform.getPlatform();
                    if (platform.isLinux() || platform.isWindows()) {
                        Runtime.getRuntime().addShutdownHook(new Thread(Rinku::shutdown, "Rinku-Shutdown"));
                    } else if (platform.isMacOS()) {
                        CefUtil.getCefApp().macOSTerminationRequestRunnable = () -> {
                            shutdown();
                            Minecraft.getInstance().stop();
                        };
                    }

                    return true;

                }

                awaitingInit.forEach(t -> t.onInit(false));
                awaitingInit.clear();

                LOGGER.error("[RINKU] Failed to initialize!", new Throwable("Something happened, but what exactly is _very unclear_."));

                shutdownLocked();

                return false;

            } catch (RuntimeException | Error failure) {
                shutdownLocked();
                throw failure;
            }

        }

    }

    public static void scheduleForInit(RinkuInitListener task) {
        awaitingInit.add(task);
    }

    /**
     * Check if Rinku is initialized.
     * @return true if Rinku is initialized correctly, false if not
     */
    public static boolean isInitialized() {
        return INITIALIZATION_CONTROLLER.isInitialized();
    }

    /** Returns whether a new CEF initialization attempt may be scheduled. */
    public static boolean isInitializationAllowed() {
        return INITIALIZATION_CONTROLLER.canInitialize();
    }

    /**
     * Check if Rinku has been initialized, throws a {@link RuntimeException} if not.
     */
    public static void assertInitialized() {
        if (!isInitialized()) throw new RuntimeException("Rinku was never initialized.");
    }

    /**
     * Get access to various settings for Rinku.
     * @return Returns the existing {@link RinkuSettings} or creates a new {@link RinkuSettings} and loads from disk (blocking)
     */
    public static RinkuSettings getSettings() {
        if (settings == null) {
            settings = new RinkuSettings();
            try {
                settings.load();
            } catch (IOException e) {
                LOGGER.warn("Failed to load Rinku settings from disk; using defaults.", e);
            }
        }
        return settings;
    }

    /**
     * Will assert that Rinku has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link RinkuApp} instance
     */
    public static RinkuApp getApp() {
        assertInitialized();
        return app;
    }

    /**
     * Will assert that Rinku has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link RinkuClient} instance
     */
    public static RinkuClient getClient() {
        assertInitialized();
        return client;
    }

    /**
     * Will assert that Rinku has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL. Can set it to be transparent rendering.
     * @return the {@link RinkuBrowser} web browser instance
     */
    public static RinkuBrowser createBrowser(String url, boolean transparent) {
        if (!RenderSystem.isOnRenderThread()) {
            return Minecraft.getInstance().submit(() -> createBrowser(url, transparent)).join();
        }
        assertInitialized();
        synchronizePreloadedBrowserPools();
        RinkuBrowser browser = acquireOrCreateBrowser(url, transparent);
        return browser;
    }

    /**
     * Will assert that Rinku has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL, width, and height.
     * Can set it to be transparent rendering.
     * @return the {@link RinkuBrowser} web browser instance
     */
    public static RinkuBrowser createBrowser(String url, boolean transparent, int width, int height) {
        if (!RenderSystem.isOnRenderThread()) {
            return Minecraft.getInstance().submit(() -> createBrowser(url, transparent, width, height)).join();
        }
        assertInitialized();
        synchronizePreloadedBrowserPools();
        RinkuBrowser browser = acquireOrCreateBrowser(url, transparent);
        browser.resize(width, height);
        return browser;
    }

    public static void refreshPreloadedBrowserPool() {
        if (!isInitialized()) {
            return;
        }
        synchronizePreloadedBrowserPools();
    }

    /**
     * Permanently shuts down Rinku/CEF for this game process. Calling this method before
     * initialization also seals the lifecycle because CEF cannot safely be restarted later.
     */
    public static void shutdown() {
        synchronized (INITIALIZATION_CONTROLLER) {
            shutdownLocked();
        }
    }

    private static void shutdownLocked() {
        INITIALIZATION_CONTROLLER.terminate();
        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            preloadPoolClosed = true;
        }

        boolean hadInitializedCef = CefUtil.isInit();
        client = null;
        app = null;

        closeUnusedPreloadedBrowsers();

        if (hadInitializedCef) {
            CefUtil.shutdown();
        }
    }

    private static RinkuBrowser acquireOrCreateBrowser(String url, boolean transparent) {
        RinkuBrowser browser = null;
        if (getPreloadedBrowserPoolTarget(transparent) > 0) {
            synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                Deque<RinkuBrowser> pool = getPreloadedBrowserPool(transparent);
                browser = pool.pollFirst();
            }
        }

        if (browser != null) {
            if (url != null) {
                browser.loadURL(url);
            }
            schedulePreloadedBrowserRefill(transparent);
            return browser;
        }

        browser = createBrowserImmediately(url, transparent);
        schedulePreloadedBrowserRefill(transparent);
        return browser;
    }

    private static RinkuBrowser createBrowserImmediately(String url, boolean transparent) {
        RinkuClient currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException("Rinku was never initialized.");
        }
        RinkuBrowser browser = new RinkuBrowser(currentClient, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        return browser;
    }

    private static void prefillPreloadedBrowserPoolsAsync() {
        schedulePreloadedBrowserRefill(true);
        schedulePreloadedBrowserRefill(false);
    }

    private static void synchronizePreloadedBrowserPools() {
        schedulePreloadedBrowserRefill(true);
        schedulePreloadedBrowserRefill(false);
    }

    private static void schedulePreloadedBrowserRefill(boolean transparent) {
        int targetSize = getPreloadedBrowserPoolTarget(transparent);
        List<RinkuBrowser> extraBrowsers = new ArrayList<>();
        int toPreload = 0;
        boolean canPreload;

        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            Deque<RinkuBrowser> pool = getPreloadedBrowserPool(transparent);
            while (pool.size() > targetSize) {
                extraBrowsers.add(pool.removeLast());
            }

            canPreload = isInitialized() && !preloadPoolClosed && targetSize > 0;

            if (canPreload) {
                int inFlight = transparent ? preloadTransparentInFlight : preloadOpaqueInFlight;
                toPreload = targetSize - pool.size() - inFlight;
                if (toPreload > 0) {
                    if (transparent) {
                        preloadTransparentInFlight += toPreload;
                    } else {
                        preloadOpaqueInFlight += toPreload;
                    }
                }
            }
        }

        for (RinkuBrowser browser : extraBrowsers) {
            closeBrowserQuietly(browser);
        }

        if (!canPreload || toPreload <= 0) {
            return;
        }

        for (int i = 0; i < toPreload; i++) {
            submitPreloadBrowserTask(transparent);
        }
    }

    private static void submitPreloadBrowserTask(boolean transparent) {
        try {
            Minecraft.getInstance().submit(() -> preloadBrowser(transparent));
        } catch (Throwable throwable) {
            decrementPreloadInFlight(transparent);
            LOGGER.warn("[RINKU] Failed to submit {} browser preload task!", transparent ? "transparent" : "opaque", throwable);
        }
    }

    private static void preloadBrowser(boolean transparent) {
        RinkuBrowser preloadedBrowser = null;
        boolean pooled = false;
        try {
            synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                if (preloadPoolClosed) {
                    return;
                }
            }

            if (!isInitialized()) {
                return;
            }

            preloadedBrowser = createBrowserImmediately(PRELOADED_BROWSER_START_URL, transparent);

            synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                int targetSize = getPreloadedBrowserPoolTarget(transparent);
                Deque<RinkuBrowser> pool = getPreloadedBrowserPool(transparent);
                if (!preloadPoolClosed && isInitialized() && targetSize > 0 && pool.size() < targetSize) {
                    pool.offerLast(preloadedBrowser);
                    pooled = true;
                }
            }
        } catch (Throwable throwable) {
            LOGGER.warn("[RINKU] Failed to preload {} browser!", transparent ? "transparent" : "opaque", throwable);
        } finally {
            decrementPreloadInFlight(transparent);
            if (!pooled && preloadedBrowser != null) {
                closeBrowserQuietly(preloadedBrowser);
            }
        }
    }

    private static int getPreloadedBrowserPoolTarget(boolean transparent) {
        RinkuSettings currentSettings = getSettings();
        if (!currentSettings.isBrowserPreloadEnabled()) {
            return 0;
        }
        return transparent
                ? currentSettings.getBrowserPreloadTransparentPoolSize()
                : currentSettings.getBrowserPreloadOpaquePoolSize();
    }

    private static Deque<RinkuBrowser> getPreloadedBrowserPool(boolean transparent) {
        return transparent ? PRELOADED_TRANSPARENT_BROWSERS : PRELOADED_OPAQUE_BROWSERS;
    }

    private static void closeUnusedPreloadedBrowsers() {
        List<RinkuBrowser> browsersToClose = new ArrayList<>();
        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            preloadPoolClosed = true;
            browsersToClose.addAll(PRELOADED_TRANSPARENT_BROWSERS);
            browsersToClose.addAll(PRELOADED_OPAQUE_BROWSERS);
            PRELOADED_TRANSPARENT_BROWSERS.clear();
            PRELOADED_OPAQUE_BROWSERS.clear();
            preloadTransparentInFlight = 0;
            preloadOpaqueInFlight = 0;
        }

        for (RinkuBrowser browser : browsersToClose) {
            closeBrowserQuietly(browser);
        }
    }

    private static void decrementPreloadInFlight(boolean transparent) {
        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            if (transparent) {
                if (preloadTransparentInFlight > 0) {
                    preloadTransparentInFlight--;
                }
                return;
            }

            if (preloadOpaqueInFlight > 0) {
                preloadOpaqueInFlight--;
            }
        }
    }

    private static void closeBrowserQuietly(@Nullable RinkuBrowser browser) {
        try {
            if (browser == null) return;
            browser.close();
        } catch (Throwable ignored) {} // We close it _quietly_, so throwing errors would be kinda dumb here, wouldn't it?
    }

    /**
     * Get the build-pinned git commit hash of the JCEF Java API and native runtime release.
     * @return The git commit hash of java-cef
     */
    public static String getJavaCefCommit() throws IOException {
        return JcefRuntimeIdentity.JAVA_CEF_COMMIT;
    }

    /**
     * Helper method to get a GLFW cursor handle for the given {@link CefCursorType} cursor type
     */
    static long getGLFWCursorHandle(CefCursorType cursorType) {
        if (cursorType == null || cursorType.glfwId == 0) {
            // Use the operating system default cursor for unmapped/unsupported CEF cursor types.
            return 0L;
        }

        if (CEF_TO_GLFW_CURSORS.containsKey(cursorType)) return CEF_TO_GLFW_CURSORS.get(cursorType);
        long glfwCursorHandle = GLFW.glfwCreateStandardCursor(cursorType.glfwId);
        if (glfwCursorHandle == 0L) {
            // Fallback to OS default cursor if GLFW can't create the requested cursor.
            return 0L;
        }
        CEF_TO_GLFW_CURSORS.put(cursorType, glfwCursorHandle);
        return glfwCursorHandle;
    }

}
