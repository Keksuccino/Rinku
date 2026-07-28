package de.keksuccino.mcef;

import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefAudioHandler;
import org.cef.handler.CefContextMenuHandler;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link CefClient}
 */
public class MCEFClient implements CefLoadHandler, CefContextMenuHandler, CefDisplayHandler, CefAudioHandler, CefDownloadHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

    private final CefClient handle;
    private final List<CefLoadHandler> loadHandlers = new CopyOnWriteArrayList<>();
    private final List<CefContextMenuHandler> contextMenuHandlers = new CopyOnWriteArrayList<>();
    private final List<CefDisplayHandler> displayHandlers = new CopyOnWriteArrayList<>();
    private final List<CefAudioHandler> audioHandlers = new CopyOnWriteArrayList<>();
    private final MCEFDownloadHandlerRelay downloadHandlerRelay = new MCEFDownloadHandlerRelay();

    public MCEFClient(CefClient cefClient) {
        handle = cefClient;
        cefClient.addLoadHandler(this);
        cefClient.addContextMenuHandler(this);
        cefClient.addDisplayHandler(this);
        cefClient.addAudioHandler(this);
        cefClient.addDownloadHandler(this);
    }

    public CefClient getHandle() {
        return handle;
    }

    public void addLoadHandler(CefLoadHandler handler) {
        loadHandlers.add(handler);
    }

    @Override
    public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
        for (CefLoadHandler loadHandler : loadHandlers)
            loadHandler.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
        for (CefLoadHandler loadHandler : loadHandlers) loadHandler.onLoadStart(browser, frame, transitionType);
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        for (CefLoadHandler loadHandler : loadHandlers) loadHandler.onLoadEnd(browser, frame, httpStatusCode);
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
        for (CefLoadHandler loadHandler : loadHandlers)
            loadHandler.onLoadError(browser, frame, errorCode, errorText, failedUrl);
    }

    public void addContextMenuHandler(CefContextMenuHandler handler) {
        contextMenuHandlers.add(handler);
    }

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            contextMenuHandler.onBeforeContextMenu(browser, frame, params, model);
    }

    @Override
    public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame, CefContextMenuParams params, int commandId, int eventFlags) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            if (contextMenuHandler.onContextMenuCommand(browser, frame, params, commandId, eventFlags))
                return true;
        return false;
    }

    @Override
    public void onContextMenuDismissed(CefBrowser browser, CefFrame frame) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            contextMenuHandler.onContextMenuDismissed(browser, frame);
    }

    public void addDisplayHandler(CefDisplayHandler handler) {
        displayHandlers.add(handler);
    }

    public void removeDisplayHandler(CefDisplayHandler handler) {
        displayHandlers.remove(handler);
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onAddressChange(browser, frame, url);
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onTitleChange(browser, title);
    }

    @Override
    public boolean onTooltip(CefBrowser browser, String text) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onTooltip(browser, text))
                return true;
        return false;
    }

    @Override
    public void onStatusMessage(CefBrowser browser, String value) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onStatusMessage(browser, value);
    }

    @Override
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onConsoleMessage(browser, level, message, source, line))
                return true;

        if (shouldForwardConsoleMessageToMcLog(level)) {
            logConsoleMessageToMcLog(browser, level, message, source, line);
        }

        // Always consume here so CEF doesn't bypass our filtering and spam the process console.
        return true;
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onCursorChange(browser, cursorType))
                return true;
        return false;
    }
    
    public void addAudioHandler(CefAudioHandler handler) {
        audioHandlers.add(handler);
    }
    
    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            if (audioHandler.getAudioParameters(browser, params))
                return true;
        }
        return false;
    }
    
    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamStarted(browser, params, channels);
        }
    }
    
    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamPacket(browser, data, frames, pts);
        }
    }
    
    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamStopped(browser);
        }
    }
    
    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamError(browser, text);
        }
        LOGGER.warn("An audio stream threw an error: " + text);
    }

    public void addDownloadHandler(CefDownloadHandler handler) {
        downloadHandlerRelay.addHandler(handler);
    }

    @Override
    public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
        return downloadHandlerRelay.canDownload(browser, url, requestMethod);
    }

    @Override
    @Deprecated
    public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        downloadHandlerRelay.onBeforeDownload(browser, downloadItem, suggestedName, callback);
    }

    @Override
    public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        return downloadHandlerRelay.onBeforeDownloadWithDecision(browser, downloadItem, suggestedName, callback);
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
        downloadHandlerRelay.onDownloadUpdated(browser, downloadItem, callback);
    }

    private static boolean shouldForwardConsoleMessageToMcLog(CefSettings.LogSeverity level) {
        CefSettings.LogSeverity threshold = MCEF.getSettings().getConsoleLogForwardingMinSeverity();
        CefSettings.LogSeverity effectiveThreshold = threshold == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DISABLE
                : threshold;
        if (effectiveThreshold == CefSettings.LogSeverity.LOGSEVERITY_DISABLE) {
            return false;
        }

        CefSettings.LogSeverity effectiveLevel = level == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DEFAULT
                : level;
        return getSeverityRank(effectiveLevel) >= getSeverityRank(effectiveThreshold);
    }

    private static int getSeverityRank(CefSettings.LogSeverity severity) {
        return switch (severity) {
            case LOGSEVERITY_VERBOSE -> 0;
            case LOGSEVERITY_DEFAULT, LOGSEVERITY_INFO -> 1;
            case LOGSEVERITY_WARNING -> 2;
            case LOGSEVERITY_ERROR -> 3;
            case LOGSEVERITY_FATAL -> 4;
            case LOGSEVERITY_DISABLE -> 5;
        };
    }

    private static void logConsoleMessageToMcLog(
            CefBrowser browser,
            CefSettings.LogSeverity level,
            String message,
            String source,
            int line
    ) {
        int browserId = browser == null ? -1 : browser.getIdentifier();
        String sourceValue = source == null || source.isBlank() ? "<unknown>" : source;
        String messageValue = message == null ? "<null>" : message;
        CefSettings.LogSeverity effectiveLevel = level == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DEFAULT
                : level;

        switch (effectiveLevel) {
            case LOGSEVERITY_VERBOSE -> LOGGER.debug("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_DEFAULT, LOGSEVERITY_INFO ->
                    LOGGER.info("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_WARNING ->
                    LOGGER.warn("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_ERROR, LOGSEVERITY_FATAL ->
                    LOGGER.error("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_DISABLE -> {
                // Nothing to forward.
            }
        }
    }
}
