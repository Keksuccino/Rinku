package de.keksuccino.mcef;

import de.keksuccino.mcef.listeners.MCEFInitListener;
import de.keksuccino.mcef.mixins.MixinGui;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;

/**
 * An API to create Chromium web browsers in Minecraft.
 * Uses a modified version of java-cef (Java Chromium Embedded Framework).
 */
public final class MCEF {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");
    public static final String MOD_ID = "mcef";

    private static MCEFSettings settings;
    private static volatile MCEFApp app;
    private static volatile MCEFClient client;
    private static final MCEFInitializationController INITIALIZATION_CONTROLLER = new MCEFInitializationController();

    private static final ArrayList<MCEFInitListener> awaitingInit = new ArrayList<>();
    private static final String PRELOADED_BROWSER_START_URL = "about:blank";
    private static final Object PRELOADED_BROWSER_POOL_LOCK = new Object();
    private static final Deque<MCEFBrowser> PRELOADED_TRANSPARENT_BROWSERS = new ArrayDeque<>();
    private static final Deque<MCEFBrowser> PRELOADED_OPAQUE_BROWSERS = new ArrayDeque<>();
    private static int preloadTransparentInFlight;
    private static int preloadOpaqueInFlight;
    private static boolean preloadPoolClosed = true;

    public static void scheduleForInit(MCEFInitListener task) {
        awaitingInit.add(task);
    }

    /**
     * Get access to various settings for MCEF.
     * @return Returns the existing {@link MCEFSettings} or creates a new {@link MCEFSettings} and loads from disk (blocking)
     */
    public static MCEFSettings getSettings() {
        if (settings == null) {
            settings = new MCEFSettings();
            try {
                settings.load();
            } catch (IOException e) {
                LOGGER.warn("Failed to load MCEF settings from disk; using defaults.", e);
            }
        }
        return settings;
    }

    /**
     * This gets called by {@link MixinGui}.
     * This should not be called by anything else.
     */
    public static boolean initialize() {
        synchronized (INITIALIZATION_CONTROLLER) {
            MCEFInitializationController.BeginResult beginResult = INITIALIZATION_CONTROLLER.beginInitialization();
            if (beginResult == MCEFInitializationController.BeginResult.ALREADY_INITIALIZED) return true;
            if (beginResult == MCEFInitializationController.BeginResult.REJECTED) return false;

            try {
                LOGGER.info("Initializing CEF on " + MCEFPlatform.getPlatform().getNormalizedName() + "...");
                if (CefUtil.init()) {
                    app = new MCEFApp(CefUtil.getCefApp());
                    client = new MCEFClient(CefUtil.getCefClient());
                    INITIALIZATION_CONTROLLER.markInitialized();
                    List<MCEFBrowser> stalePreloadedBrowsers = new ArrayList<>();
                    synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                        stalePreloadedBrowsers.addAll(PRELOADED_TRANSPARENT_BROWSERS);
                        stalePreloadedBrowsers.addAll(PRELOADED_OPAQUE_BROWSERS);
                        PRELOADED_TRANSPARENT_BROWSERS.clear();
                        PRELOADED_OPAQUE_BROWSERS.clear();
                        preloadTransparentInFlight = 0;
                        preloadOpaqueInFlight = 0;
                        preloadPoolClosed = false;
                    }
                    for (MCEFBrowser browser : stalePreloadedBrowsers) closeBrowserQuietly(browser);

                    awaitingInit.forEach(t -> t.onInit(true));
                    awaitingInit.clear();
                    LOGGER.info("Chromium Embedded Framework initialized");

                    app.getHandle().registerSchemeHandlerFactory("mod", "", (browser, frame, url, request) -> new ModScheme(request.getURL()));
                    prefillPreloadedBrowserPoolsAsync();

                    // These callbacks are important because JCEF helper processes can otherwise
                    // survive the game. MCEF's lifecycle gate makes every shutdown path idempotent.
                    MCEFPlatform platform = MCEFPlatform.getPlatform();
                    if (platform.isLinux() || platform.isWindows()) {
                        Runtime.getRuntime().addShutdownHook(new Thread(MCEF::shutdown, "MCEF-Shutdown"));
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
                LOGGER.error("Could not initialize Chromium Embedded Framework");
                shutdownLocked();
                return false;
            } catch (RuntimeException | Error failure) {
                shutdownLocked();
                throw failure;
            }
        }
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link MCEFApp} instance
     */
    public static MCEFApp getApp() {
        assertInitialized();
        return app;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link MCEFClient} instance
     */
    public static MCEFClient getClient() {
        assertInitialized();
        return client;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL. Can set it to be transparent rendering.
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent) {
        if (!RenderSystem.isOnRenderThread()) {
            return Minecraft.getInstance().submit(() -> createBrowser(url, transparent)).join();
        }
        assertInitialized();
        synchronizePreloadedBrowserPools();
        MCEFBrowser browser = acquireOrCreateBrowser(url, transparent);
        return browser;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL, width, and height.
     * Can set it to be transparent rendering.
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        if (!RenderSystem.isOnRenderThread()) {
            return Minecraft.getInstance().submit(() -> createBrowser(url, transparent, width, height)).join();
        }
        assertInitialized();
        synchronizePreloadedBrowserPools();
        MCEFBrowser browser = acquireOrCreateBrowser(url, transparent);
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
     * Check if MCEF is initialized.
     * @return true if MCEF is initialized correctly, false if not
     */
    public static boolean isInitialized() {
        return INITIALIZATION_CONTROLLER.isInitialized();
    }

    /** Returns whether a new CEF initialization attempt may be scheduled. */
    public static boolean isInitializationAllowed() {
        return INITIALIZATION_CONTROLLER.canInitialize();
    }

    /**
     * Permanently shuts down MCEF/CEF for this game process. Calling this method before
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

    /**
     * Check if MCEF has been initialized, throws a {@link RuntimeException} if not.
     */
    public static void assertInitialized() {
        if (!isInitialized())
            throw new RuntimeException("Chromium Embedded Framework was never initialized.");
    }

    private static MCEFBrowser acquireOrCreateBrowser(String url, boolean transparent) {
        MCEFBrowser browser = null;
        if (getPreloadedBrowserPoolTarget(transparent) > 0) {
            synchronized (PRELOADED_BROWSER_POOL_LOCK) {
                Deque<MCEFBrowser> pool = getPreloadedBrowserPool(transparent);
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

    private static MCEFBrowser createBrowserImmediately(String url, boolean transparent) {
        MCEFClient currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException("Chromium Embedded Framework was never initialized.");
        }
        MCEFBrowser browser = new MCEFBrowser(currentClient, url, transparent);
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
        List<MCEFBrowser> extraBrowsers = new ArrayList<>();
        int toPreload = 0;
        boolean canPreload;

        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            Deque<MCEFBrowser> pool = getPreloadedBrowserPool(transparent);
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

        for (MCEFBrowser browser : extraBrowsers) {
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
            LOGGER.warn(
                    "Failed to submit {} browser preload task",
                    transparent ? "transparent" : "opaque",
                    throwable
            );
        }
    }

    private static void preloadBrowser(boolean transparent) {
        MCEFBrowser preloadedBrowser = null;
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
                Deque<MCEFBrowser> pool = getPreloadedBrowserPool(transparent);
                if (!preloadPoolClosed && isInitialized() && targetSize > 0 && pool.size() < targetSize) {
                    pool.offerLast(preloadedBrowser);
                    pooled = true;
                }
            }
        } catch (Throwable throwable) {
            LOGGER.warn(
                    "Failed to preload {} browser",
                    transparent ? "transparent" : "opaque",
                    throwable
            );
        } finally {
            decrementPreloadInFlight(transparent);

            if (!pooled && preloadedBrowser != null) {
                closeBrowserQuietly(preloadedBrowser);
            }
        }
    }

    private static int getPreloadedBrowserPoolTarget(boolean transparent) {
        MCEFSettings currentSettings = getSettings();
        if (!currentSettings.isBrowserPreloadEnabled()) {
            return 0;
        }
        return transparent
                ? currentSettings.getBrowserPreloadTransparentPoolSize()
                : currentSettings.getBrowserPreloadOpaquePoolSize();
    }

    private static Deque<MCEFBrowser> getPreloadedBrowserPool(boolean transparent) {
        return transparent ? PRELOADED_TRANSPARENT_BROWSERS : PRELOADED_OPAQUE_BROWSERS;
    }

    private static void closeUnusedPreloadedBrowsers() {
        List<MCEFBrowser> browsersToClose = new ArrayList<>();
        synchronized (PRELOADED_BROWSER_POOL_LOCK) {
            preloadPoolClosed = true;
            browsersToClose.addAll(PRELOADED_TRANSPARENT_BROWSERS);
            browsersToClose.addAll(PRELOADED_OPAQUE_BROWSERS);
            PRELOADED_TRANSPARENT_BROWSERS.clear();
            PRELOADED_OPAQUE_BROWSERS.clear();
            preloadTransparentInFlight = 0;
            preloadOpaqueInFlight = 0;
        }

        for (MCEFBrowser browser : browsersToClose) {
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

    private static void closeBrowserQuietly(MCEFBrowser browser) {
        try {
            browser.close();
        } catch (Throwable throwable) {
            LOGGER.warn("Failed to close preloaded browser", throwable);
        }
    }

    /**
     * Get the git commit hash of the java-cef code (either from MANIFEST.MF or from the git repo on-disk if in a
     * development environment). Used for downloading the java-cef release.
     * @return The git commit hash of java-cef
     */
    public static String getJavaCefCommit() throws IOException {
        return JavaCefCommitResolver.resolve(MCEF.class, System.getProperty("mcef.java.cef.commit"));
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
    private static final HashMap<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new HashMap<>();
}
