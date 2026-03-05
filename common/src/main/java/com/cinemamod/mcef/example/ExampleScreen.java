package com.cinemamod.mcef.example;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
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

    private MCEFBrowser browser;
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
            browser = MCEF.createBrowser(DEFAULT_URL, transparent);
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
        MCEF.getClient().addDisplayHandler(addressBarDisplayHandler);
    }

    private void initNavigationWidgets() {
        int navX = FRAME_MARGIN;
        int navY = FRAME_MARGIN;

        backButton = addRenderableWidget(
                Button.builder(Component.literal("<"), (button) -> browser.goBack())
                        .bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
                        .build()
        );
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        forwardButton = addRenderableWidget(
                Button.builder(Component.literal(">"), (button) -> browser.goForward())
                        .bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
                        .build()
        );
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        reloadButton = addRenderableWidget(
                Button.builder(Component.literal("R"), (button) -> browser.reload())
                        .bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
                        .build()
        );
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
    public void resize(@NotNull Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        resizeBrowser();
    }

    @Override
    public void onClose() {
        if (addressBarDisplayHandler != null && MCEF.isInitialized()) {
            MCEF.getClient().removeDisplayHandler(addressBarDisplayHandler);
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
        ResourceLocation textureLocation = browser.getTextureLocation();
        if (textureLocation == null) {
            return;
        }

        int frameRenderWidth = getBrowserWidth();
        int frameRenderHeight = getBrowserHeight();
        guiGraphics.blit(
                textureLocation,
                getBrowserX(),
                getBrowserY(),
                0.0F,
                0.0F,
                frameRenderWidth,
                frameRenderHeight,
                frameRenderWidth,
                frameRenderHeight
        );

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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            return true;
        }

        if (!isInBrowserBounds(mouseX, mouseY)) {
            return false;
        }

        browser.sendMousePress(mouseX(mouseX), mouseY(mouseY), button);
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (handled) {
            return true;
        }

        browser.sendMouseRelease(this.mouseX(mouseX), this.mouseY(mouseY), button);
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (urlBox != null && urlBox.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            navigateFromUrlField();
            setFocused(null);
            browser.setFocus(true);
            return true;
        }

        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        browser.sendKeyPress(keyCode, scanCode, modifiers);
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (super.keyReleased(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        browser.sendKeyRelease(keyCode, scanCode, modifiers);
        browser.setFocus(true);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (super.charTyped(codePoint, modifiers)) {
            return true;
        }

        if (urlBox != null && urlBox.isFocused()) {
            return true;
        }

        if (codePoint == 0) return false;
        browser.sendKeyTyped(codePoint, modifiers);
        browser.setFocus(true);
        return true;
    }

}
