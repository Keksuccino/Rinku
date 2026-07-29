package de.keksuccino.rinku.example;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public class ExampleScreen extends Screen {

    private static final int FRAME_MARGIN = 20;
    private static final int NAV_BAR_HEIGHT = 20;
    private static final int NAV_BAR_GAP = 6;
    private static final int NAV_BUTTON_WIDTH = 24;
    private static final int NAV_SPACING = 4;
    private static final int LOADING_BAR_HEIGHT = 2;
    private static final int LOADING_BAR_TRACK_COLOR = 0x55000000;
    private static final int LOADING_BAR_FILL_COLOR = 0xFF3BA8FF;
    private static final String DEFAULT_URL = "https://www.google.com";

    private RinkuBrowser browser;
    private EditBox urlBox;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private CefDisplayHandler addressBarDisplayHandler;

    public ExampleScreen(Component component) {
        super(component);
    }

    @Override
    protected void init() {
        super.init();
        if (browser == null) {
            boolean transparent = true;
            browser = Rinku.createBrowser(DEFAULT_URL, transparent);
        }
        registerAddressBarDisplayHandler();
        initNavigationWidgets();
        resizeBrowser();
        refreshNavigationState();
    }

    private void registerAddressBarDisplayHandler() {
        if (addressBarDisplayHandler != null) {
            return;
        }

        addressBarDisplayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (browser == null || cefBrowser == null || frame == null || !frame.isMain()) {
                    return;
                }
                if (cefBrowser.getIdentifier() != browser.getIdentifier()) {
                    return;
                }

                minecraft.execute(() -> {
                    if (minecraft.screen != ExampleScreen.this || urlBox == null || url == null || url.isBlank()) {
                        return;
                    }
                    if (!url.equals(urlBox.getValue())) {
                        urlBox.setValue(url);
                    }
                });
            }
        };
        Rinku.getClient().addDisplayHandler(addressBarDisplayHandler);
    }

    private void initNavigationWidgets() {
        int navX = FRAME_MARGIN;
        int navY = FRAME_MARGIN;

        backButton = addRenderableWidget(Button.builder(Component.literal("<"), (button) -> browser.goBack()).bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT).build());
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        forwardButton = addRenderableWidget(Button.builder(Component.literal(">"), (button) -> browser.goForward()).bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT).build());
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        reloadButton = addRenderableWidget(Button.builder(Component.literal("R"), (button) -> browser.reload()).bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT).build());
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        int urlWidth = Math.max(60, width - FRAME_MARGIN - navX);
        urlBox = addRenderableWidget(new EditBox(font, navX, navY, urlWidth, NAV_BAR_HEIGHT, Component.literal("URL")));
        urlBox.setMaxLength(2048);
        String currentUrl = browser.getURL();
        urlBox.setValue(currentUrl == null || currentUrl.isBlank() ? DEFAULT_URL : currentUrl);
    }

    private int getBrowserX() {
        return FRAME_MARGIN;
    }

    private int getBrowserY() {
        return FRAME_MARGIN + NAV_BAR_HEIGHT + NAV_BAR_GAP;
    }

    private int getBrowserWidth() {
        return Math.max(1, width - FRAME_MARGIN * 2);
    }

    private int getBrowserHeight() {
        return Math.max(1, height - getBrowserY() - FRAME_MARGIN);
    }

    private boolean isInBrowserBounds(double x, double y) {
        int browserX = getBrowserX();
        int browserY = getBrowserY();
        return x >= browserX && y >= browserY && x < (browserX + getBrowserWidth()) && y < (browserY + getBrowserHeight());
    }

    private int mouseX(double x) {
        return (int) ((x - getBrowserX()) * minecraft.getWindow().getGuiScale());
    }

    private int mouseY(double y) {
        return (int) ((y - getBrowserY()) * minecraft.getWindow().getGuiScale());
    }

    private void resizeBrowser() {
        if (browser != null) {
            browser.resize((int) (getBrowserWidth() * minecraft.getWindow().getGuiScale()), (int) (getBrowserHeight() * minecraft.getWindow().getGuiScale()));
        }
    }

    @Override
    public void resize(int i, int j) {
        super.resize(i, j);
        resizeBrowser();
    }

    @Override
    public void onClose() {
        if (addressBarDisplayHandler != null && Rinku.isInitialized()) {
            Rinku.getClient().removeDisplayHandler(addressBarDisplayHandler);
        }
        addressBarDisplayHandler = null;
        browser.close();
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        refreshNavigationState();
    }

    private void refreshNavigationState() {
        if (browser == null) {
            return;
        }

        if (backButton != null) {
            backButton.active = browser.canGoBack();
        }
        if (forwardButton != null) {
            forwardButton.active = browser.canGoForward();
        }
        if (reloadButton != null) {
            reloadButton.active = true;
        }

        if (urlBox != null && !urlBox.isFocused()) {
            String currentUrl = browser.getURL();
            if (currentUrl != null && !currentUrl.isBlank() && !currentUrl.equals(urlBox.getValue())) {
                urlBox.setValue(currentUrl);
            }
        }
    }

    private void navigateFromUrlField() {
        if (urlBox == null) {
            return;
        }

        String input = urlBox.getValue();
        if (input == null) {
            return;
        }
        input = input.trim();
        if (input.isEmpty()) {
            return;
        }

        String normalizedUrl = normalizeUrl(input);
        urlBox.setValue(normalizedUrl);
        browser.loadURL(normalizedUrl);
        browser.setFocus(true);
    }

    private String normalizeUrl(String input) {
        if (input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return input;
        }
        return "https://" + input;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
        super.render(guiGraphics, mouseX, mouseY, partial);
        renderLoadingIndicator(guiGraphics);

        // Check if the browser texture is ready for rendering
        if (browser != null && browser.isTextureReady()) {
            renderBrowserTexture(guiGraphics);
        }

    }

    private void renderBrowserTexture(GuiGraphics guiGraphics) {

        // Get the Identifier for the browser texture
        Identifier textureLocation = browser.getTextureIdentifier();
        if (textureLocation == null) {
            return;
        }

        int frameRenderWidth = getBrowserWidth();
        int frameRenderHeight = getBrowserHeight();
        // CEF's transparent OSR buffer is premultiplied; straight-alpha blending would darken translucent edges.
        RenderPipeline renderPipeline = selectBrowserRenderPipeline(browser.getRenderer().isTransparent());
        guiGraphics.blit(renderPipeline, textureLocation, getBrowserX(), getBrowserY(), 0.0F, 0.0F, frameRenderWidth, frameRenderHeight, frameRenderWidth, frameRenderHeight);

    }

    static RenderPipeline selectBrowserRenderPipeline(boolean transparent) {
        return transparent ? RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA : RenderPipelines.GUI_TEXTURED;
    }

    private void renderLoadingIndicator(GuiGraphics guiGraphics) {
        if (browser == null || urlBox == null || !browser.isLoading()) {
            return;
        }

        int barX = urlBox.getX();
        int barY = urlBox.getY() + 1;
        int barWidth = urlBox.getWidth();
        int barBottom = barY + LOADING_BAR_HEIGHT;
        guiGraphics.fill(barX, barY, barX + barWidth, barBottom, LOADING_BAR_TRACK_COLOR);

        int segmentWidth = Math.max(20, barWidth / 4);
        int travelRange = barWidth + segmentWidth;
        int animatedOffset = (int) ((Util.getMillis() / 6L) % travelRange) - segmentWidth;
        int segmentStart = Math.max(barX, barX + animatedOffset);
        int segmentEnd = Math.min(barX + barWidth, barX + animatedOffset + segmentWidth);
        if (segmentEnd > segmentStart) {
            guiGraphics.fill(segmentStart, barY, segmentEnd, barBottom, LOADING_BAR_FILL_COLOR);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        boolean handled = super.mouseClicked(event, isDoubleClick);
        if (handled) {
            return true;
        }

        if (!isInBrowserBounds(event.x(), event.y())) {
            return false;
        }

        browser.sendMousePress(mouseX(event.x()), mouseY(event.y()), event.button());
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        if (handled) {
            return true;
        }

        browser.sendMouseRelease(mouseX(event.x()), mouseY(event.y()), event.button());
        browser.setFocus(true);
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (isInBrowserBounds(mouseX, mouseY)) {
            browser.sendMouseMove(this.mouseX(mouseX), this.mouseY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean handled = super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (handled) {
            return true;
        }

        if (!isInBrowserBounds(mouseX, mouseY)) {
            return false;
        }

        browser.sendMouseWheel(this.mouseX(mouseX), this.mouseY(mouseY), scrollY, 0);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (urlBox != null && urlBox.isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            navigateFromUrlField();
            setFocused(null);
            browser.setFocus(true);
            return true;
        }

        if (super.keyPressed(event)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (super.keyReleased(event)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (super.charTyped(event)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        if (event.codepoint() == (char) 0) return false;
        browser.sendKeyTyped((char) event.codepoint(), 0);
        browser.setFocus(true);
        return true;
    }

}
