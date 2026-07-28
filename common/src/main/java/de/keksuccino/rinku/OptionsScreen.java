package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.cef.CefSettings;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Rinku's loader-independent, tabbed configuration screen. */
public class OptionsScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_MAX_WIDTH = 360;
    private static final int EDITABLE_LABEL_WIDTH = 155;
    private static final int EDITABLE_ROW_GAP = 5;
    private static final int OPTION_ROW_ADVANCE = 26;
    private static final int OPTION_SECTION_PADDING_TOP = 10;
    private static final int CYCLE_VALUE_COLOR = 0xFFAA00;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final int BROWSER_TAB_INDEX = 0;
    private static final int DOWNLOADS_TAB_INDEX = 1;
    private static final Identifier TAB_HEADER_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/tab_header_background.png");

    @Nullable
    private final Screen parent;
    private final RinkuSettings settings;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<Button> fullWidthOptionButtons = new ArrayList<>();
    private final List<TextOptionControl<?>> textOptionControls = new ArrayList<>();
    private final Map<String, String> pendingTextValues = new HashMap<>();
    @Nullable
    private MenuTabBar tabNavigationBar;
    @Nullable
    private Button transparentPoolSizeButton;
    @Nullable
    private Button opaquePoolSizeButton;

    public OptionsScreen(@Nullable Screen parent) {
        super(Component.translatable("rinku.options"));
        this.parent = parent;
        this.settings = Rinku.getSettings();
        this.initializePendingTextValues();
    }

    @Override
    protected void init() {
        this.layout.removeChildren();
        this.fullWidthOptionButtons.clear();
        this.textOptionControls.clear();
        this.transparentPoolSizeButton = null;
        this.opaquePoolSizeButton = null;

        OptionsTab browserTab = this.buildBrowserTab();
        OptionsTab downloadsTab = this.buildDownloadsTab();
        OptionsTab advancedTab = this.buildAdvancedTab();
        this.tabNavigationBar = MenuTabBar.builder(this.tabManager, this.width).addTabs(browserTab, downloadsTab, advancedTab).build();
        this.addRenderableWidget(this.tabNavigationBar);

        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose()).width(150).build());
        this.layout.visitWidgets(widget -> {
            widget.setTabOrderGroup(1);
            this.addRenderableWidget(widget);
        });

        this.updateOptionWidths();
        this.updatePreloadControls();
        this.tabNavigationBar.selectTab(BROWSER_TAB_INDEX, false);
        this.repositionElements();
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

    private OptionsTab buildBrowserTab() {
        OptionsTab tab = new OptionsTab(Component.translatable("rinku.options.tab.browser"));
        this.addTextOption(tab, BROWSER_TAB_INDEX, new TextOption<>("user-agent", "rinku.options.user_agent", "rinku.options.user_agent.desc", 512, RinkuOptionsInput::parseUserAgent, this.settings::getUserAgent, this.settings::setUserAgent, Component.translatable("rinku.options.validation.user_agent")));
        this.addFullWidthOption(tab, this.buildBooleanButton("rinku.options.use_cache", "rinku.options.use_cache.desc", this.settings::isUsingCache, this.settings::setUseCache));
        this.addFullWidthOption(tab, this.buildBooleanButton("rinku.options.enable_widevine", "rinku.options.enable_widevine.desc", this.settings::isEnableWidevineCdm, this.settings::setEnableWidevineCdm));
        this.addFullWidthOption(tab, this.buildPreloadEnabledButton(), optionSettings -> optionSettings.paddingTop(OPTION_SECTION_PADDING_TOP));
        this.transparentPoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_transparent_pool_size", "rinku.options.preload_transparent_pool_size.desc", this.settings::getBrowserPreloadTransparentPoolSize, this.settings::setBrowserPreloadTransparentPoolSize);
        this.opaquePoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_opaque_pool_size", "rinku.options.preload_opaque_pool_size.desc", this.settings::getBrowserPreloadOpaquePoolSize, this.settings::setBrowserPreloadOpaquePoolSize);
        this.addFullWidthOption(tab, this.transparentPoolSizeButton);
        this.addFullWidthOption(tab, this.opaquePoolSizeButton);
        return tab;
    }

    private OptionsTab buildDownloadsTab() {
        OptionsTab tab = new OptionsTab(Component.translatable("rinku.options.tab.downloads"));
        this.addFullWidthOption(tab, this.buildBooleanButton("rinku.options.skip_download", "rinku.options.skip_download.desc", this.settings::isSkipDownload, this.settings::setSkipDownload));
        this.addFullWidthOption(tab, this.buildMirrorPolicyButton());
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-mirror", "rinku.options.download_mirror", "rinku.options.download_mirror.desc", 2048, this::parseDownloadMirror, this.settings::getDownloadMirror, this.settings::setDownloadMirror, Component.translatable("rinku.options.validation.download_mirror")));
        this.addFullWidthOption(tab, this.buildBooleanButton("rinku.options.enforce_checksums", "rinku.options.enforce_checksums.desc", this.settings::isEnforceDownloadChecksums, this.settings::setEnforceDownloadChecksums));
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-connect-timeout-ms", "rinku.options.connect_timeout", "rinku.options.connect_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadConnectTimeoutMs, this.settings::setDownloadConnectTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-read-timeout-ms", "rinku.options.read_timeout", "rinku.options.read_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadReadTimeoutMs, this.settings::setDownloadReadTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-archive-bytes", "rinku.options.max_archive_bytes", "rinku.options.max_archive_bytes.desc", 10, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES), this.settings::getDownloadMaxArchiveBytes, this.settings::setDownloadMaxArchiveBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES)));
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-checksum-bytes", "rinku.options.max_checksum_bytes", "rinku.options.max_checksum_bytes.desc", 7, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES), this.settings::getDownloadMaxChecksumBytes, this.settings::setDownloadMaxChecksumBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES)));
        this.addTextOption(tab, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-extracted-bytes", "rinku.options.max_extracted_bytes", "rinku.options.max_extracted_bytes.desc", 11, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES), this.settings::getDownloadMaxExtractedBytes, this.settings::setDownloadMaxExtractedBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES)));
        return tab;
    }

    private OptionsTab buildAdvancedTab() {
        OptionsTab tab = new OptionsTab(Component.translatable("rinku.options.tab.advanced"));
        this.addFullWidthOption(tab, this.buildBooleanButton("rinku.options.disable_web_security", "rinku.options.disable_web_security.desc", this.settings::isDisableWebSecurity, this.settings::setDisableWebSecurity));
        this.addFullWidthOption(tab, this.buildLogSeverityButton("rinku.options.native_log_severity", "rinku.options.native_log_severity.desc", this.settings::getNativeCefLogSeverity, this.settings::setNativeCefLogSeverity));
        this.addFullWidthOption(tab, this.buildLogSeverityButton("rinku.options.console_log_severity", "rinku.options.console_log_severity.desc", this.settings::getConsoleLogForwardingMinSeverity, this.settings::setConsoleLogForwardingMinSeverity));
        return tab;
    }

    private Button buildPreloadEnabledButton() {
        Button button = Button.builder(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()), pressedButton -> {
            this.settings.setBrowserPreloadEnabled(!this.settings.isBrowserPreloadEnabled());
            pressedButton.setMessage(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()));
            this.updatePreloadControls();
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable("rinku.options.preload_enabled.desc"))).build();
        return button;
    }

    private Button buildMirrorPolicyButton() {
        Button button = Button.builder(this.mirrorPolicyMessage(), pressedButton -> {
            this.settings.setDownloadMirrorPolicy(nextValue(this.settings.getDownloadMirrorPolicy(), RinkuDownloader.MirrorPolicy.values()));
            pressedButton.setMessage(this.mirrorPolicyMessage());
            this.validateTextOptions();
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable("rinku.options.download_mirror_policy.desc"))).build();
        return button;
    }

    private Button buildLogSeverityButton(String labelKey, String descriptionKey, Supplier<CefSettings.LogSeverity> getter, Consumer<CefSettings.LogSeverity> setter) {
        Button button = Button.builder(this.logSeverityMessage(labelKey, getter.get()), pressedButton -> {
            CefSettings.LogSeverity next = nextValue(getter.get(), CefSettings.LogSeverity.values());
            setter.accept(next);
            pressedButton.setMessage(this.logSeverityMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
        return button;
    }

    private Button buildPoolSizeButton(String labelKey, String descriptionKey, Supplier<Integer> getter, Consumer<Integer> setter) {
        Button button = Button.builder(this.integerOptionMessage(labelKey, getter.get()), pressedButton -> {
            int next = getter.get() >= RinkuSettings.MAX_BROWSER_PRELOAD_POOL_SIZE ? RinkuSettings.MIN_BROWSER_PRELOAD_POOL_SIZE : getter.get() + 1;
            setter.accept(next);
            pressedButton.setMessage(this.integerOptionMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
        return button;
    }

    private Button buildBooleanButton(String labelKey, String descriptionKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        Button button = Button.builder(this.booleanOptionMessage(labelKey, getter.get()), pressedButton -> {
            boolean next = !getter.get();
            setter.accept(next);
            pressedButton.setMessage(this.booleanOptionMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
        return button;
    }

    private void addFullWidthOption(OptionsTab tab, Button button) {
        this.fullWidthOptionButtons.add(button);
        tab.addChild(button);
    }

    private void addFullWidthOption(OptionsTab tab, Button button, Consumer<LayoutSettings> layoutSettings) {
        this.fullWidthOptionButtons.add(button);
        tab.addChild(button, layoutSettings);
    }

    private <T> void addTextOption(OptionsTab tab, int tabIndex, TextOption<T> option) {
        Component label = Component.translatable(option.labelKey());
        Component description = Component.translatable(option.descriptionKey());
        StringWidget labelWidget = new StringWidget(EDITABLE_LABEL_WIDTH, BUTTON_HEIGHT, label, this.font);
        EditBox editBox = new EditBox(this.font, this.getButtonWidth() - EDITABLE_LABEL_WIDTH - EDITABLE_ROW_GAP, BUTTON_HEIGHT, label);
        String initialValue = this.pendingTextValues.getOrDefault(option.id(), "");
        labelWidget.setTooltip(Tooltip.create(description));
        // Never truncate a longer value loaded from disk merely because the screen was opened.
        editBox.setMaxLength(Math.max(option.maxLength(), initialValue.length()));
        editBox.setValue(initialValue);
        editBox.setTooltip(Tooltip.create(description));
        TextOptionControl<T> control = new TextOptionControl<>(tabIndex, option, labelWidget, editBox, description);
        editBox.setResponder(value -> {
            this.pendingTextValues.put(option.id(), value);
            control.validate();
        });
        this.textOptionControls.add(control);
        LinearLayout row = LinearLayout.horizontal().spacing(EDITABLE_ROW_GAP);
        row.addChild(labelWidget);
        row.addChild(editBox);
        tab.addChild(row);
    }

    private String parseDownloadMirror(String value) {
        return RinkuOptionsInput.parseMirror(value, this.settings.getDownloadMirrorPolicy() != RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
    }

    private void updatePreloadControls() {
        boolean active = this.settings.isBrowserPreloadEnabled();
        if (this.transparentPoolSizeButton != null) this.transparentPoolSizeButton.active = active;
        if (this.opaquePoolSizeButton != null) this.opaquePoolSizeButton.active = active;
    }

    private void updateOptionWidths() {
        int rowWidth = this.getButtonWidth();
        for (Button button : this.fullWidthOptionButtons) button.setWidth(rowWidth);
        int labelWidth = Math.min(EDITABLE_LABEL_WIDTH, Math.max(80, rowWidth / 2));
        for (TextOptionControl<?> control : this.textOptionControls) {
            control.label().setWidth(labelWidth);
            control.editBox().setWidth(Math.max(40, rowWidth - labelWidth - EDITABLE_ROW_GAP));
        }
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
            if (this.tabNavigationBar != null) this.tabNavigationBar.selectTab(firstInvalid.tabIndex(), false);
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

    private int getButtonWidth() {
        return Math.min(BUTTON_ROW_MAX_WIDTH, this.width - 40);
    }

    private static <T> T nextValue(T current, T[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return values[(index + 1) % values.length];
        }
        return values[0];
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.tabNavigationBar != null && this.tabNavigationBar.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    protected void repositionElements() {
        if (this.tabNavigationBar == null) return;
        this.tabNavigationBar.arrangeElements(this.width);
        int tabAreaTop = this.tabNavigationBar.getRectangle().bottom();
        ScreenRectangle tabArea = new ScreenRectangle(0, tabAreaTop, this.width, Math.max(0, this.height - this.layout.getFooterHeight() - tabAreaTop));
        this.tabManager.setTabArea(tabArea);
        this.layout.setHeaderHeight(tabAreaTop);
        this.layout.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, this.height - this.layout.getFooterHeight(), 0.0F, 0.0F, this.width, 2, 32, 2);
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TAB_HEADER_BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.layout.getHeaderHeight(), 16, 16);
        this.extractMenuBackground(graphics, 0, this.layout.getHeaderHeight(), this.width, this.height);
    }

    @Override
    public void onClose() {
        if (!this.applyTextOptions()) return;
        Minecraft.getInstance().gui.setScreen(this.parent);
    }

    private record TextOption<T>(String id, String labelKey, String descriptionKey, int maxLength, Function<String, T> parser, Supplier<T> currentValue, Consumer<T> applier, Component invalidMessage) {
    }

    private final class TextOptionControl<T> {

        private final int tabIndex;
        private final TextOption<T> option;
        private final StringWidget label;
        private final EditBox editBox;
        private final Component description;
        @Nullable
        private T parsedValue;

        private TextOptionControl(int tabIndex, TextOption<T> option, StringWidget label, EditBox editBox, Component description) {
            this.tabIndex = tabIndex;
            this.option = option;
            this.label = label;
            this.editBox = editBox;
            this.description = description;
        }

        private boolean validate() {
            try {
                this.parsedValue = this.option.parser().apply(this.editBox.getValue());
                this.editBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                this.editBox.setTooltip(Tooltip.create(this.description));
                return true;
            } catch (IllegalArgumentException exception) {
                this.parsedValue = null;
                this.editBox.setTextColor(INVALID_TEXT_COLOR);
                this.editBox.setTooltip(Tooltip.create(this.option.invalidMessage()));
                return false;
            }
        }

        private void applyParsedValue() {
            if (!Objects.equals(this.parsedValue, this.option.currentValue().get())) this.option.applier().accept(this.parsedValue);
        }

        private int tabIndex() {
            return this.tabIndex;
        }

        private StringWidget label() {
            return this.label;
        }

        private EditBox editBox() {
            return this.editBox;
        }

    }

    private final class OptionsTab implements Tab {

        private final Component title;
        private final LinearLayout optionsLayout;
        private final ScrollableLayout scrollableLayout;

        private OptionsTab(Component title) {
            this.title = title;
            this.optionsLayout = LinearLayout.vertical().spacing(OPTION_ROW_ADVANCE - BUTTON_HEIGHT);
            this.optionsLayout.defaultCellSetting().alignHorizontallyCenter();
            this.scrollableLayout = new ScrollableLayout(OptionsScreen.this.minecraft, this.optionsLayout, BUTTON_HEIGHT, ScrollableLayout.ReserveStrategy.BOTH);
            this.scrollableLayout.setScrollbarSpacing(2);
        }

        private void addChild(LayoutElement child) {
            this.optionsLayout.addChild(child);
        }

        private void addChild(LayoutElement child, Consumer<LayoutSettings> settings) {
            this.optionsLayout.addChild(child, settings);
        }

        @Override
        public Component getTabTitle() {
            return this.title;
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.empty();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
            // ScrollableLayout caches content widgets; arrange before TabManager registers the container.
            this.scrollableLayout.arrangeElements();
            this.scrollableLayout.visitWidgets(childrenConsumer);
        }

        @Override
        public void doLayout(ScreenRectangle screenRectangle) {
            OptionsScreen.this.updateOptionWidths();
            int topY = Math.max(screenRectangle.top() + 4, Math.min(50, screenRectangle.bottom() - BUTTON_HEIGHT));
            this.scrollableLayout.setMinWidth(OptionsScreen.this.getButtonWidth());
            this.scrollableLayout.arrangeElements();
            this.scrollableLayout.setMaxHeight(Math.max(BUTTON_HEIGHT, screenRectangle.bottom() - topY));
            this.scrollableLayout.setPosition((OptionsScreen.this.width - this.scrollableLayout.getWidth()) / 2, topY);
        }

        @Override
        public Layout getLayout() {
            return this.scrollableLayout;
        }

    }

}
