package de.keksuccino.rinku;

import com.mojang.blaze3d.vertex.PoseStack;
import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import org.cef.CefSettings;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Rinku's loader-independent configuration screen implemented against Minecraft 1.19.2's immediate GUI API. */
public class OptionsScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_MAX_WIDTH = 360;
    private static final int EDITABLE_LABEL_WIDTH = 155;
    private static final int EDITABLE_ROW_GAP = 5;
    private static final int OPTION_ROW_ADVANCE = 26;
    private static final int OPTIONS_TOP = 50;
    private static final int FOOTER_HEIGHT = 32;
    private static final int CYCLE_VALUE_COLOR = 0xFFAA00;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;

    @Nullable
    private final Screen parent;
    private final RinkuSettings settings;
    private final Map<String, String> pendingTextValues = new HashMap<>();
    private final EnumMap<Page, List<OptionControl>> controls = new EnumMap<>(Page.class);
    private final EnumMap<Page, Integer> scrollRows = new EnumMap<>(Page.class);
    private final List<TextOptionControl<?>> textOptionControls = new ArrayList<>();
    private final EnumMap<Page, Button> tabButtons = new EnumMap<>(Page.class);
    private Page selectedPage = Page.BROWSER;

    public OptionsScreen(@Nullable Screen parent) {
        super(Component.translatable("rinku.options"));
        this.parent = parent;
        this.settings = Rinku.getSettings();
        for (Page page : Page.values()) this.scrollRows.put(page, 0);
        this.initializePendingTextValues();
    }

    @Override
    protected void init() {
        this.controls.clear();
        this.textOptionControls.clear();
        this.tabButtons.clear();
        for (Page page : Page.values()) this.controls.put(page, new ArrayList<>());
        this.buildTabs();
        this.buildBrowserOptions();
        this.buildDownloadOptions();
        this.buildAdvancedOptions();
        this.addRenderableWidget(new Button((this.width - 150) / 2, this.height - 27, 150, BUTTON_HEIGHT, CommonComponents.GUI_DONE, ignored -> this.onClose()));
        this.updateControlLayout();
    }

    private void initializePendingTextValues() {
        this.pendingTextValues.put("user-agent", this.settings.getUserAgent() == null ? "" : this.settings.getUserAgent());
        this.pendingTextValues.put("download-mirror", this.settings.getDownloadMirror() == null ? "" : this.settings.getDownloadMirror());
        this.pendingTextValues.put("download-connect-timeout-ms", Integer.toString(this.settings.getDownloadConnectTimeoutMs()));
        this.pendingTextValues.put("download-read-timeout-ms", Integer.toString(this.settings.getDownloadReadTimeoutMs()));
        this.pendingTextValues.put("download-max-archive-bytes", Long.toString(this.settings.getDownloadMaxArchiveBytes()));
        this.pendingTextValues.put("download-max-checksum-bytes", Long.toString(this.settings.getDownloadMaxChecksumBytes()));
        this.pendingTextValues.put("download-max-extracted-bytes", Long.toString(this.settings.getDownloadMaxExtractedBytes()));
    }

    private void buildTabs() {
        int totalWidth = this.getButtonWidth();
        int gap = 4;
        int tabWidth = (totalWidth - gap * (Page.values().length - 1)) / Page.values().length;
        int x = (this.width - totalWidth) / 2;
        for (Page page : Page.values()) {
            Button button = this.addRenderableWidget(new Button(x, 24, tabWidth, BUTTON_HEIGHT, Component.translatable(page.translationKey), ignored -> this.selectPage(page)));
            this.tabButtons.put(page, button);
            x += tabWidth + gap;
        }
    }

    private void buildBrowserOptions() {
        this.addTextOption(Page.BROWSER, new TextOption<>("user-agent", "rinku.options.user_agent", "rinku.options.user_agent.desc", 512, RinkuOptionsInput::parseUserAgent, this.settings::getUserAgent, this.settings::setUserAgent, Component.translatable("rinku.options.validation.user_agent")));
        this.addButtonOption(Page.BROWSER, this.buildBooleanButton("rinku.options.use_cache", "rinku.options.use_cache.desc", this.settings::isUsingCache, this.settings::setUseCache), () -> true);
        this.addButtonOption(Page.BROWSER, this.buildBooleanButton("rinku.options.enable_widevine", "rinku.options.enable_widevine.desc", this.settings::isEnableWidevineCdm, this.settings::setEnableWidevineCdm), () -> true);
        Button preloadButton = this.optionButton(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()), "rinku.options.preload_enabled.desc", button -> {
            this.settings.setBrowserPreloadEnabled(!this.settings.isBrowserPreloadEnabled());
            button.setMessage(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()));
            this.updateControlLayout();
        });
        this.addButtonOption(Page.BROWSER, preloadButton, () -> true);
        this.addButtonOption(Page.BROWSER, this.buildPoolSizeButton("rinku.options.preload_transparent_pool_size", "rinku.options.preload_transparent_pool_size.desc", this.settings::getBrowserPreloadTransparentPoolSize, this.settings::setBrowserPreloadTransparentPoolSize), this.settings::isBrowserPreloadEnabled);
        this.addButtonOption(Page.BROWSER, this.buildPoolSizeButton("rinku.options.preload_opaque_pool_size", "rinku.options.preload_opaque_pool_size.desc", this.settings::getBrowserPreloadOpaquePoolSize, this.settings::setBrowserPreloadOpaquePoolSize), this.settings::isBrowserPreloadEnabled);
    }

    private void buildDownloadOptions() {
        this.addButtonOption(Page.DOWNLOADS, this.buildBooleanButton("rinku.options.skip_download", "rinku.options.skip_download.desc", this.settings::isSkipDownload, this.settings::setSkipDownload), () -> true);
        Button mirrorPolicyButton = this.optionButton(this.mirrorPolicyMessage(), "rinku.options.download_mirror_policy.desc", button -> {
            this.settings.setDownloadMirrorPolicy(nextValue(this.settings.getDownloadMirrorPolicy(), RinkuDownloader.MirrorPolicy.values()));
            button.setMessage(this.mirrorPolicyMessage());
            this.validateTextOptions();
        });
        this.addButtonOption(Page.DOWNLOADS, mirrorPolicyButton, () -> true);
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-mirror", "rinku.options.download_mirror", "rinku.options.download_mirror.desc", 2048, this::parseDownloadMirror, this.settings::getDownloadMirror, this.settings::setDownloadMirror, Component.translatable("rinku.options.validation.download_mirror")));
        this.addButtonOption(Page.DOWNLOADS, this.buildBooleanButton("rinku.options.enforce_checksums", "rinku.options.enforce_checksums.desc", this.settings::isEnforceDownloadChecksums, this.settings::setEnforceDownloadChecksums), () -> true);
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-connect-timeout-ms", "rinku.options.connect_timeout", "rinku.options.connect_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadConnectTimeoutMs, this.settings::setDownloadConnectTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-read-timeout-ms", "rinku.options.read_timeout", "rinku.options.read_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadReadTimeoutMs, this.settings::setDownloadReadTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-max-archive-bytes", "rinku.options.max_archive_bytes", "rinku.options.max_archive_bytes.desc", 10, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES), this.settings::getDownloadMaxArchiveBytes, this.settings::setDownloadMaxArchiveBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES)));
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-max-checksum-bytes", "rinku.options.max_checksum_bytes", "rinku.options.max_checksum_bytes.desc", 7, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES), this.settings::getDownloadMaxChecksumBytes, this.settings::setDownloadMaxChecksumBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES)));
        this.addTextOption(Page.DOWNLOADS, new TextOption<>("download-max-extracted-bytes", "rinku.options.max_extracted_bytes", "rinku.options.max_extracted_bytes.desc", 11, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES), this.settings::getDownloadMaxExtractedBytes, this.settings::setDownloadMaxExtractedBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES)));
    }

    private void buildAdvancedOptions() {
        this.addButtonOption(Page.ADVANCED, this.buildBooleanButton("rinku.options.disable_web_security", "rinku.options.disable_web_security.desc", this.settings::isDisableWebSecurity, this.settings::setDisableWebSecurity), () -> true);
        this.addButtonOption(Page.ADVANCED, this.buildLogSeverityButton("rinku.options.native_log_severity", "rinku.options.native_log_severity.desc", this.settings::getNativeCefLogSeverity, this.settings::setNativeCefLogSeverity), () -> true);
        this.addButtonOption(Page.ADVANCED, this.buildLogSeverityButton("rinku.options.console_log_severity", "rinku.options.console_log_severity.desc", this.settings::getConsoleLogForwardingMinSeverity, this.settings::setConsoleLogForwardingMinSeverity), () -> true);
    }

    private Button buildBooleanButton(String labelKey, String descriptionKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return this.optionButton(this.booleanOptionMessage(labelKey, getter.get()), descriptionKey, button -> {
            boolean next = !getter.get();
            setter.accept(next);
            button.setMessage(this.booleanOptionMessage(labelKey, next));
        });
    }

    private Button buildPoolSizeButton(String labelKey, String descriptionKey, Supplier<Integer> getter, Consumer<Integer> setter) {
        return this.optionButton(this.integerOptionMessage(labelKey, getter.get()), descriptionKey, button -> {
            int next = getter.get() >= RinkuSettings.MAX_BROWSER_PRELOAD_POOL_SIZE ? RinkuSettings.MIN_BROWSER_PRELOAD_POOL_SIZE : getter.get() + 1;
            setter.accept(next);
            button.setMessage(this.integerOptionMessage(labelKey, next));
        });
    }

    private Button buildLogSeverityButton(String labelKey, String descriptionKey, Supplier<CefSettings.LogSeverity> getter, Consumer<CefSettings.LogSeverity> setter) {
        return this.optionButton(this.logSeverityMessage(labelKey, getter.get()), descriptionKey, button -> {
            CefSettings.LogSeverity next = nextValue(getter.get(), CefSettings.LogSeverity.values());
            setter.accept(next);
            button.setMessage(this.logSeverityMessage(labelKey, next));
        });
    }

    private Button optionButton(Component message, String descriptionKey, Button.OnPress onPress) {
        Component description = Component.translatable(descriptionKey);
        return new Button(0, 0, this.getButtonWidth(), BUTTON_HEIGHT, message, onPress, (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack, description, mouseX, mouseY));
    }

    private void addButtonOption(Page page, Button button, Supplier<Boolean> enabled) {
        this.addRenderableWidget(button);
        List<OptionControl> pageControls = this.controls.get(page);
        pageControls.add(new ButtonOptionControl(page, pageControls.size(), button, enabled));
    }

    private <T> void addTextOption(Page page, TextOption<T> option) {
        Component label = Component.translatable(option.labelKey());
        EditBox editBox = this.addRenderableWidget(new EditBox(this.font, 0, 0, 100, BUTTON_HEIGHT, label));
        String initialValue = this.pendingTextValues.getOrDefault(option.id(), "");
        editBox.setMaxLength(Math.max(option.maxLength(), initialValue.length()));
        List<OptionControl> pageControls = this.controls.get(page);
        TextOptionControl<T> control = new TextOptionControl<>(page, pageControls.size(), option, label, editBox, Component.translatable(option.descriptionKey()));
        editBox.setResponder(value -> {
            this.pendingTextValues.put(option.id(), value);
            control.validate();
        });
        editBox.setValue(initialValue);
        control.validate();
        pageControls.add(control);
        this.textOptionControls.add(control);
    }

    private String parseDownloadMirror(String value) {
        return RinkuOptionsInput.parseMirror(value, this.settings.getDownloadMirrorPolicy() != RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
    }

    private void selectPage(Page page) {
        if (this.selectedPage == page) return;
        this.selectedPage = page;
        this.setFocused(null);
        for (TextOptionControl<?> control : this.textOptionControls) control.editBox.setFocus(false);
        this.updateControlLayout();
    }

    private void updateControlLayout() {
        int rowWidth = this.getButtonWidth();
        int rowX = (this.width - rowWidth) / 2;
        int footerTop = this.height - FOOTER_HEIGHT;
        int visibleRows = this.getVisibleRowCount();
        for (Page page : Page.values()) {
            List<OptionControl> pageControls = this.controls.get(page);
            int maxScroll = Math.max(0, pageControls.size() - visibleRows);
            int scroll = Mth.clamp(this.scrollRows.getOrDefault(page, 0), 0, maxScroll);
            this.scrollRows.put(page, scroll);
            for (OptionControl control : pageControls) {
                int y = OPTIONS_TOP + (control.rowIndex - scroll) * OPTION_ROW_ADVANCE;
                boolean visible = page == this.selectedPage && y >= OPTIONS_TOP && y + BUTTON_HEIGHT <= footerTop;
                control.layout(rowX, y, rowWidth, visible);
            }
            Button tabButton = this.tabButtons.get(page);
            if (tabButton != null) tabButton.active = page != this.selectedPage;
        }
    }

    private int getVisibleRowCount() {
        return Math.max(1, (this.height - FOOTER_HEIGHT - OPTIONS_TOP) / OPTION_ROW_ADVANCE);
    }

    private int getButtonWidth() {
        return Math.max(120, Math.min(BUTTON_ROW_MAX_WIDTH, this.width - 40));
    }

    private void validateTextOptions() {
        for (TextOptionControl<?> control : this.textOptionControls) control.validate();
    }

    private boolean applyTextOptions() {
        TextOptionControl<?> firstInvalid = null;
        for (TextOptionControl<?> control : this.textOptionControls) {
            if (!control.validate() && firstInvalid == null) firstInvalid = control;
        }
        if (firstInvalid != null) {
            this.selectedPage = firstInvalid.page();
            int centeredRow = Math.max(0, firstInvalid.rowIndex() - this.getVisibleRowCount() / 2);
            this.scrollRows.put(firstInvalid.page(), centeredRow);
            this.updateControlLayout();
            this.setFocused(firstInvalid.editBox);
            firstInvalid.editBox.setFocus(true);
            return false;
        }
        for (TextOptionControl<?> control : this.textOptionControls) control.applyParsedValue();
        return true;
    }

    private Component booleanOptionMessage(String labelKey, boolean enabled) {
        Component value = Component.translatable(enabled ? "rinku.options.toggle.enabled" : "rinku.options.toggle.disabled").withStyle(Style.EMPTY.withColor(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
        return Component.translatable(labelKey, value);
    }

    private Component integerOptionMessage(String labelKey, int value) {
        return Component.translatable(labelKey, this.cycleValue(Component.literal(Integer.toString(value))));
    }

    private Component mirrorPolicyMessage() {
        String valueKey = "rinku.options.download_mirror_policy." + this.settings.getDownloadMirrorPolicy().name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("rinku.options.download_mirror_policy", this.cycleValue(Component.translatable(valueKey)));
    }

    private Component logSeverityMessage(String labelKey, CefSettings.LogSeverity severity) {
        String severityName = severity.name().substring("LOGSEVERITY_".length()).toLowerCase(java.util.Locale.ROOT);
        return Component.translatable(labelKey, this.cycleValue(Component.translatable("rinku.options.log_severity." + severityName)));
    }

    private Component cycleValue(Component value) {
        return value.copy().withStyle(Style.EMPTY.withColor(CYCLE_VALUE_COLOR));
    }

    private static <T> T nextValue(T current, T[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return values[(index + 1) % values.length];
        }
        return values[0];
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTick);
        for (OptionControl control : this.controls.get(this.selectedPage)) control.renderSupplement(poseStack, mouseX, mouseY);
        this.renderScrollbar(poseStack);
    }

    private void renderScrollbar(PoseStack poseStack) {
        List<OptionControl> pageControls = this.controls.get(this.selectedPage);
        int visibleRows = this.getVisibleRowCount();
        if (pageControls.size() <= visibleRows) return;
        int x = (this.width + this.getButtonWidth()) / 2 + 5;
        int top = OPTIONS_TOP;
        int bottom = this.height - FOOTER_HEIGHT;
        int trackHeight = bottom - top;
        int thumbHeight = Math.max(12, trackHeight * visibleRows / pageControls.size());
        int maxScroll = pageControls.size() - visibleRows;
        int thumbY = top + (trackHeight - thumbHeight) * this.scrollRows.get(this.selectedPage) / maxScroll;
        fill(poseStack, x, top, x + 2, bottom, 0x66000000);
        fill(poseStack, x, thumbY, x + 2, thumbY + thumbHeight, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        List<OptionControl> pageControls = this.controls.get(this.selectedPage);
        int maxScroll = Math.max(0, pageControls.size() - this.getVisibleRowCount());
        if (maxScroll > 0 && mouseY >= OPTIONS_TOP && mouseY < this.height - FOOTER_HEIGHT && scrollDelta != 0.0D) {
            int direction = scrollDelta > 0.0D ? -1 : 1;
            this.scrollRows.put(this.selectedPage, Mth.clamp(this.scrollRows.get(this.selectedPage) + direction, 0, maxScroll));
            this.updateControlLayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB && hasControlDown()) {
            Page[] pages = Page.values();
            this.selectPage(pages[(this.selectedPage.ordinal() + (hasShiftDown() ? pages.length - 1 : 1)) % pages.length]);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (!this.applyTextOptions()) return;
        Minecraft.getInstance().setScreen(this.parent);
    }

    private enum Page {
        BROWSER("rinku.options.tab.browser"),
        DOWNLOADS("rinku.options.tab.downloads"),
        ADVANCED("rinku.options.tab.advanced");

        private final String translationKey;

        Page(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record TextOption<T>(String id, String labelKey, String descriptionKey, int maxLength, Function<String, T> parser, Supplier<T> currentValue, Consumer<T> applier, Component invalidMessage) {
    }

    private abstract class OptionControl {

        private final Page page;
        private final int rowIndex;

        private OptionControl(Page page, int rowIndex) {
            this.page = page;
            this.rowIndex = rowIndex;
        }

        final Page page() {
            return this.page;
        }

        final int rowIndex() {
            return this.rowIndex;
        }

        abstract void layout(int x, int y, int width, boolean visible);

        void renderSupplement(PoseStack poseStack, int mouseX, int mouseY) {
        }
    }

    private final class ButtonOptionControl extends OptionControl {

        private final Button button;
        private final Supplier<Boolean> enabled;

        private ButtonOptionControl(Page page, int rowIndex, Button button, Supplier<Boolean> enabled) {
            super(page, rowIndex);
            this.button = button;
            this.enabled = enabled;
        }

        @Override
        void layout(int x, int y, int width, boolean visible) {
            this.button.x = x;
            this.button.y = y;
            this.button.setWidth(width);
            this.button.visible = visible;
            this.button.active = visible && this.enabled.get();
        }
    }

    private final class TextOptionControl<T> extends OptionControl {

        private final TextOption<T> option;
        private final Component label;
        private final EditBox editBox;
        private final Component description;
        @Nullable
        private T parsedValue;
        private int labelX;
        private int rowY;
        private int labelWidth;

        private TextOptionControl(Page page, int rowIndex, TextOption<T> option, Component label, EditBox editBox, Component description) {
            super(page, rowIndex);
            this.option = option;
            this.label = label;
            this.editBox = editBox;
            this.description = description;
        }

        private boolean validate() {
            try {
                this.parsedValue = this.option.parser().apply(this.editBox.getValue());
                this.editBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                return true;
            } catch (IllegalArgumentException ignored) {
                this.parsedValue = null;
                this.editBox.setTextColor(INVALID_TEXT_COLOR);
                return false;
            }
        }

        private void applyParsedValue() {
            if (!Objects.equals(this.parsedValue, this.option.currentValue().get())) this.option.applier().accept(this.parsedValue);
        }

        @Override
        void layout(int x, int y, int width, boolean visible) {
            this.labelWidth = Math.min(EDITABLE_LABEL_WIDTH, Math.max(80, width / 2));
            this.labelX = x;
            this.rowY = y;
            this.editBox.x = x + this.labelWidth + EDITABLE_ROW_GAP;
            this.editBox.y = y;
            this.editBox.setWidth(Math.max(40, width - this.labelWidth - EDITABLE_ROW_GAP));
            this.editBox.visible = visible;
            this.editBox.active = visible;
            if (!visible) this.editBox.setFocus(false);
        }

        @Override
        void renderSupplement(PoseStack poseStack, int mouseX, int mouseY) {
            if (!this.editBox.visible) return;
            drawString(poseStack, OptionsScreen.this.font, this.label, this.labelX, this.rowY + 6, 0xFFFFFF);
            boolean labelHovered = mouseX >= this.labelX && mouseX < this.labelX + this.labelWidth && mouseY >= this.rowY && mouseY < this.rowY + BUTTON_HEIGHT;
            if (labelHovered || this.editBox.isMouseOver(mouseX, mouseY)) {
                Component tooltip = this.parsedValue == null ? this.option.invalidMessage() : this.description;
                OptionsScreen.this.renderTooltip(poseStack, tooltip, mouseX, mouseY);
            }
        }
    }

}
