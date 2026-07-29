package de.keksuccino.rinku.example;

import com.mojang.blaze3d.vertex.PoseStack;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.RinkuBrowserTextureBlitter;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
        if (this.browser == null) this.browser = Rinku.createBrowser(DEFAULT_URL, true);
        this.registerAddressBarDisplayHandler();
        this.initNavigationWidgets();
        this.resizeBrowser();
        this.refreshNavigationState();
    }

    private void registerAddressBarDisplayHandler() {
        if (this.addressBarDisplayHandler != null) return;
        this.addressBarDisplayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (ExampleScreen.this.browser == null || cefBrowser == null || frame == null || !frame.isMain()) return;
                if (cefBrowser.getIdentifier() != ExampleScreen.this.browser.getIdentifier()) return;
                ExampleScreen.this.minecraft.execute(() -> {
                    if (ExampleScreen.this.minecraft.screen != ExampleScreen.this || ExampleScreen.this.urlBox == null || url == null || url.isBlank()) return;
                    if (!url.equals(ExampleScreen.this.urlBox.getValue())) ExampleScreen.this.urlBox.setValue(url);
                });
            }
        };
        Rinku.getClient().addDisplayHandler(this.addressBarDisplayHandler);
    }

    private void initNavigationWidgets() {
        int navX = FRAME_MARGIN;
        int navY = FRAME_MARGIN;
        this.backButton = this.addRenderableWidget(new Button(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, Component.literal("<"), button -> this.browser.goBack()));
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;
        this.forwardButton = this.addRenderableWidget(new Button(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, Component.literal(">"), button -> this.browser.goForward()));
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;
        this.reloadButton = this.addRenderableWidget(new Button(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, Component.literal("R"), button -> this.browser.reload()));
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        int urlWidth = Math.max(60, this.width - FRAME_MARGIN - navX);
        this.urlBox = this.addRenderableWidget(new EditBox(this.font, navX, navY, urlWidth, NAV_BAR_HEIGHT, Component.literal("URL")));
        this.urlBox.setMaxLength(2048);
        String currentUrl = this.browser.getURL();
        this.urlBox.setValue(currentUrl == null || currentUrl.isBlank() ? DEFAULT_URL : currentUrl);
    }

    private int getBrowserX() {
        return FRAME_MARGIN;
    }

    private int getBrowserY() {
        return FRAME_MARGIN + NAV_BAR_HEIGHT + NAV_BAR_GAP;
    }

    private int getBrowserWidth() {
        return Math.max(1, this.width - FRAME_MARGIN * 2);
    }

    private int getBrowserHeight() {
        return Math.max(1, this.height - this.getBrowserY() - FRAME_MARGIN);
    }

    private boolean isInBrowserBounds(double x, double y) {
        return x >= this.getBrowserX() && y >= this.getBrowserY() && x < this.getBrowserX() + this.getBrowserWidth() && y < this.getBrowserY() + this.getBrowserHeight();
    }

    private int browserMouseX(double x) {
        return (int) ((x - this.getBrowserX()) * this.minecraft.getWindow().getGuiScale());
    }

    private int browserMouseY(double y) {
        return (int) ((y - this.getBrowserY()) * this.minecraft.getWindow().getGuiScale());
    }

    private void resizeBrowser() {
        if (this.browser == null) return;
        this.browser.resize((int) (this.getBrowserWidth() * this.minecraft.getWindow().getGuiScale()), (int) (this.getBrowserHeight() * this.minecraft.getWindow().getGuiScale()));
    }

    @Override
    public void resize(@NotNull Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        this.resizeBrowser();
    }

    @Override
    public void onClose() {
        if (this.addressBarDisplayHandler != null && Rinku.isInitialized()) Rinku.getClient().removeDisplayHandler(this.addressBarDisplayHandler);
        this.addressBarDisplayHandler = null;
        if (this.browser != null) this.browser.close();
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.urlBox != null) this.urlBox.tick();
        this.refreshNavigationState();
    }

    private void refreshNavigationState() {
        if (this.browser == null) return;
        if (this.backButton != null) this.backButton.active = this.browser.canGoBack();
        if (this.forwardButton != null) this.forwardButton.active = this.browser.canGoForward();
        if (this.reloadButton != null) this.reloadButton.active = true;
        if (this.urlBox != null && !this.urlBox.isFocused()) {
            String currentUrl = this.browser.getURL();
            if (currentUrl != null && !currentUrl.isBlank() && !currentUrl.equals(this.urlBox.getValue())) this.urlBox.setValue(currentUrl);
        }
    }

    private void navigateFromUrlField() {
        if (this.urlBox == null || this.browser == null) return;
        String input = this.urlBox.getValue();
        if (input == null || input.trim().isEmpty()) return;
        String normalizedUrl = this.normalizeUrl(input.trim());
        this.urlBox.setValue(normalizedUrl);
        this.browser.loadURL(normalizedUrl);
        this.clearNavigationFocus();
        this.browser.setFocus(true);
    }

    private void clearNavigationFocus() {
        this.setFocused(null);
        if (this.backButton != null && this.backButton.isFocused()) this.backButton.changeFocus(false);
        if (this.forwardButton != null && this.forwardButton.isFocused()) this.forwardButton.changeFocus(false);
        if (this.reloadButton != null && this.reloadButton.isFocused()) this.reloadButton.changeFocus(false);
        if (this.urlBox != null) this.urlBox.setFocus(false);
    }

    private String normalizeUrl(String input) {
        if (input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) return input;
        return "https://" + input;
    }

    @Override
    public void render(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        if (this.browser != null && this.browser.isTextureReady()) this.renderBrowserTexture(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        this.renderLoadingIndicator(poseStack);
    }

    private void renderBrowserTexture(PoseStack poseStack) {
        RinkuBrowserTextureBlitter.blit(poseStack, this.browser, this.getBrowserX(), this.getBrowserY(), this.getBrowserWidth(), this.getBrowserHeight());
    }

    private void renderLoadingIndicator(PoseStack poseStack) {
        if (this.browser == null || this.urlBox == null || !this.browser.isLoading()) return;
        int barX = this.urlBox.x;
        int barY = this.urlBox.y + 1;
        int barWidth = this.urlBox.getWidth();
        int barBottom = barY + LOADING_BAR_HEIGHT;
        fill(poseStack, barX, barY, barX + barWidth, barBottom, LOADING_BAR_TRACK_COLOR);
        int segmentWidth = Math.max(20, barWidth / 4);
        int travelRange = barWidth + segmentWidth;
        int animatedOffset = (int) ((Util.getMillis() / 6L) % travelRange) - segmentWidth;
        int segmentStart = Math.max(barX, barX + animatedOffset);
        int segmentEnd = Math.min(barX + barWidth, barX + animatedOffset + segmentWidth);
        if (segmentEnd > segmentStart) fill(poseStack, segmentStart, barY, segmentEnd, barBottom, LOADING_BAR_FILL_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!this.isInBrowserBounds(mouseX, mouseY)) return false;
        this.clearNavigationFocus();
        this.browser.sendMousePress(this.browserMouseX(mouseX), this.browserMouseY(mouseY), button);
        this.browser.setFocus(true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (super.mouseReleased(mouseX, mouseY, button)) return true;
        this.browser.sendMouseRelease(this.browserMouseX(mouseX), this.browserMouseY(mouseY), button);
        this.browser.setFocus(true);
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (this.isInBrowserBounds(mouseX, mouseY)) this.browser.sendMouseMove(this.browserMouseX(mouseX), this.browserMouseY(mouseY));
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (!this.isInBrowserBounds(mouseX, mouseY)) return false;
        this.browser.sendMouseWheel(this.browserMouseX(mouseX), this.browserMouseY(mouseY), scrollY, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.urlBox != null && this.urlBox.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            this.navigateFromUrlField();
            return true;
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (this.urlBox != null && this.urlBox.isFocused()) return true;
        this.browser.sendKeyPress(keyCode, scanCode, modifiers);
        this.browser.setFocus(true);
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (super.keyReleased(keyCode, scanCode, modifiers)) return true;
        if (this.urlBox != null && this.urlBox.isFocused()) return true;
        this.browser.sendKeyRelease(keyCode, scanCode, modifiers);
        this.browser.setFocus(true);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (super.charTyped(codePoint, modifiers)) return true;
        if (this.urlBox != null && this.urlBox.isFocused()) return true;
        if (codePoint == 0) return false;
        this.browser.sendKeyTyped(codePoint, modifiers);
        this.browser.setFocus(true);
        return true;
    }

}
