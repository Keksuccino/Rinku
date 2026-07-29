package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
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

/** Rinku's loader-independent configuration screen, adapted to 1.20.1's immediate-mode GUI API. */
public class OptionsScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_MAX_WIDTH = 360;
    private static final int EDITABLE_LABEL_WIDTH = 155;
    private static final int EDITABLE_ROW_GAP = 5;
    private static final int OPTION_ROW_HEIGHT = 26;
    private static final int HEADER_HEIGHT = 45;
    private static final int FOOTER_HEIGHT = 36;
    private static final int CYCLE_VALUE_COLOR = 0xFFAA00;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final int BROWSER_TAB_INDEX = 0;
    private static final int DOWNLOADS_TAB_INDEX = 1;
    private static final int ADVANCED_TAB_INDEX = 2;

    @Nullable
    private final Screen parent;
    private final RinkuSettings settings;
    private final List<TextOption<?>> textOptions;
    private final Map<String, String> pendingTextValues = new HashMap<>();
    private final List<TextOptionControl<?>> visibleTextControls = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private final double[] tabScrollAmounts = new double[3];
    private int currentTabIndex = BROWSER_TAB_INDEX;
    @Nullable
    private OptionsList optionsList;
    @Nullable
    private Button transparentPoolSizeButton;
    @Nullable
    private Button opaquePoolSizeButton;

    public OptionsScreen(@Nullable Screen parent) {
        super(Component.translatable("rinku.options"));
        this.parent = parent;
        this.settings = Rinku.getSettings();
        this.initializePendingTextValues();
        this.textOptions = this.createTextOptions();
    }

    @Override
    protected void init() {
        this.visibleTextControls.clear();
        this.tabButtons.clear();
        this.transparentPoolSizeButton = null;
        this.opaquePoolSizeButton = null;
        this.addTabButtons();
        this.optionsList = this.addRenderableWidget(new OptionsList(this.minecraft, this.width, this.height, HEADER_HEIGHT, Math.max(HEADER_HEIGHT + OPTION_ROW_HEIGHT, this.height - FOOTER_HEIGHT), OPTION_ROW_HEIGHT));
        this.buildCurrentTab();
        this.optionsList.setScrollAmount(this.tabScrollAmounts[this.currentTabIndex]);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose()).bounds((this.width - 150) / 2, this.height - 28, 150, BUTTON_HEIGHT).build());
        this.updateTabButtons();
        this.updatePreloadControls();
        this.validateVisibleTextOptions();
    }

    private void addTabButtons() {
        int availableWidth = Math.max(180, this.width - 40);
        int tabWidth = Math.min(120, availableWidth / 3);
        int startX = (this.width - tabWidth * 3) / 2;
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("rinku.options.tab.browser"), ignored -> this.switchTab(BROWSER_TAB_INDEX)).bounds(startX, 8, tabWidth, BUTTON_HEIGHT).build()));
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("rinku.options.tab.downloads"), ignored -> this.switchTab(DOWNLOADS_TAB_INDEX)).bounds(startX + tabWidth, 8, tabWidth, BUTTON_HEIGHT).build()));
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("rinku.options.tab.advanced"), ignored -> this.switchTab(ADVANCED_TAB_INDEX)).bounds(startX + tabWidth * 2, 8, tabWidth, BUTTON_HEIGHT).build()));
    }

    private void switchTab(int tabIndex) {
        if (tabIndex == this.currentTabIndex) return;
        this.rememberScrollAmount();
        this.currentTabIndex = tabIndex;
        this.rebuildWidgets();
    }

    private void rememberScrollAmount() {
        if (this.optionsList != null) this.tabScrollAmounts[this.currentTabIndex] = this.optionsList.getScrollAmount();
    }

    private void updateTabButtons() {
        for (int index = 0; index < this.tabButtons.size(); index++) this.tabButtons.get(index).active = index != this.currentTabIndex;
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

    private List<TextOption<?>> createTextOptions() {
        List<TextOption<?>> options = new ArrayList<>();
        options.add(new TextOption<>(BROWSER_TAB_INDEX, "user-agent", "rinku.options.user_agent", "rinku.options.user_agent.desc", 512, RinkuOptionsInput::parseUserAgent, this.settings::getUserAgent, this.settings::setUserAgent, Component.translatable("rinku.options.validation.user_agent")));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-mirror", "rinku.options.download_mirror", "rinku.options.download_mirror.desc", 2048, this::parseDownloadMirror, this.settings::getDownloadMirror, this.settings::setDownloadMirror, Component.translatable("rinku.options.validation.download_mirror")));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-connect-timeout-ms", "rinku.options.connect_timeout", "rinku.options.connect_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadConnectTimeoutMs, this.settings::setDownloadConnectTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-read-timeout-ms", "rinku.options.read_timeout", "rinku.options.read_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadReadTimeoutMs, this.settings::setDownloadReadTimeoutMs, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-max-archive-bytes", "rinku.options.max_archive_bytes", "rinku.options.max_archive_bytes.desc", 10, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES), this.settings::getDownloadMaxArchiveBytes, this.settings::setDownloadMaxArchiveBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES)));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-max-checksum-bytes", "rinku.options.max_checksum_bytes", "rinku.options.max_checksum_bytes.desc", 7, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES), this.settings::getDownloadMaxChecksumBytes, this.settings::setDownloadMaxChecksumBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES)));
        options.add(new TextOption<>(DOWNLOADS_TAB_INDEX, "download-max-extracted-bytes", "rinku.options.max_extracted_bytes", "rinku.options.max_extracted_bytes.desc", 11, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES), this.settings::getDownloadMaxExtractedBytes, this.settings::setDownloadMaxExtractedBytes, Component.translatable("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES)));
        return List.copyOf(options);
    }

    private void buildCurrentTab() {
        if (this.optionsList == null) return;
        if (this.currentTabIndex == BROWSER_TAB_INDEX) {
            this.addTextOption(this.findTextOption("user-agent"));
            this.addFullWidthOption(this.buildBooleanButton("rinku.options.use_cache", "rinku.options.use_cache.desc", this.settings::isUsingCache, this.settings::setUseCache));
            this.addFullWidthOption(this.buildBooleanButton("rinku.options.enable_widevine", "rinku.options.enable_widevine.desc", this.settings::isEnableWidevineCdm, this.settings::setEnableWidevineCdm));
            this.addFullWidthOption(this.buildPreloadEnabledButton());
            this.transparentPoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_transparent_pool_size", "rinku.options.preload_transparent_pool_size.desc", this.settings::getBrowserPreloadTransparentPoolSize, this.settings::setBrowserPreloadTransparentPoolSize);
            this.opaquePoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_opaque_pool_size", "rinku.options.preload_opaque_pool_size.desc", this.settings::getBrowserPreloadOpaquePoolSize, this.settings::setBrowserPreloadOpaquePoolSize);
            this.addFullWidthOption(this.transparentPoolSizeButton);
            this.addFullWidthOption(this.opaquePoolSizeButton);
            return;
        }
        if (this.currentTabIndex == DOWNLOADS_TAB_INDEX) {
            this.addFullWidthOption(this.buildBooleanButton("rinku.options.skip_download", "rinku.options.skip_download.desc", this.settings::isSkipDownload, this.settings::setSkipDownload));
            this.addFullWidthOption(this.buildMirrorPolicyButton());
            this.addTextOption(this.findTextOption("download-mirror"));
            this.addFullWidthOption(this.buildBooleanButton("rinku.options.enforce_checksums", "rinku.options.enforce_checksums.desc", this.settings::isEnforceDownloadChecksums, this.settings::setEnforceDownloadChecksums));
            this.addTextOption(this.findTextOption("download-connect-timeout-ms"));
            this.addTextOption(this.findTextOption("download-read-timeout-ms"));
            this.addTextOption(this.findTextOption("download-max-archive-bytes"));
            this.addTextOption(this.findTextOption("download-max-checksum-bytes"));
            this.addTextOption(this.findTextOption("download-max-extracted-bytes"));
            return;
        }
        this.addFullWidthOption(this.buildBooleanButton("rinku.options.disable_web_security", "rinku.options.disable_web_security.desc", this.settings::isDisableWebSecurity, this.settings::setDisableWebSecurity));
        this.addFullWidthOption(this.buildLogSeverityButton("rinku.options.native_log_severity", "rinku.options.native_log_severity.desc", this.settings::getNativeCefLogSeverity, this.settings::setNativeCefLogSeverity));
        this.addFullWidthOption(this.buildLogSeverityButton("rinku.options.console_log_severity", "rinku.options.console_log_severity.desc", this.settings::getConsoleLogForwardingMinSeverity, this.settings::setConsoleLogForwardingMinSeverity));
    }

    private TextOption<?> findTextOption(String id) {
        for (TextOption<?> option : this.textOptions) {
            if (option.id().equals(id)) return option;
        }
        throw new IllegalStateException("Missing Rinku text option " + id);
    }

    private Button buildPreloadEnabledButton() {
        return Button.builder(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()), pressedButton -> {
            this.settings.setBrowserPreloadEnabled(!this.settings.isBrowserPreloadEnabled());
            pressedButton.setMessage(this.booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled()));
            this.updatePreloadControls();
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable("rinku.options.preload_enabled.desc"))).build();
    }

    private Button buildMirrorPolicyButton() {
        return Button.builder(this.mirrorPolicyMessage(), pressedButton -> {
            this.settings.setDownloadMirrorPolicy(nextValue(this.settings.getDownloadMirrorPolicy(), RinkuDownloader.MirrorPolicy.values()));
            pressedButton.setMessage(this.mirrorPolicyMessage());
            this.validateVisibleTextOptions();
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable("rinku.options.download_mirror_policy.desc"))).build();
    }

    private Button buildLogSeverityButton(String labelKey, String descriptionKey, Supplier<CefSettings.LogSeverity> getter, Consumer<CefSettings.LogSeverity> setter) {
        return Button.builder(this.logSeverityMessage(labelKey, getter.get()), pressedButton -> {
            CefSettings.LogSeverity next = nextValue(getter.get(), CefSettings.LogSeverity.values());
            setter.accept(next);
            pressedButton.setMessage(this.logSeverityMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
    }

    private Button buildPoolSizeButton(String labelKey, String descriptionKey, Supplier<Integer> getter, Consumer<Integer> setter) {
        return Button.builder(this.integerOptionMessage(labelKey, getter.get()), pressedButton -> {
            int next = getter.get() >= RinkuSettings.MAX_BROWSER_PRELOAD_POOL_SIZE ? RinkuSettings.MIN_BROWSER_PRELOAD_POOL_SIZE : getter.get() + 1;
            setter.accept(next);
            pressedButton.setMessage(this.integerOptionMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
    }

    private Button buildBooleanButton(String labelKey, String descriptionKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Button.builder(this.booleanOptionMessage(labelKey, getter.get()), pressedButton -> {
            boolean next = !getter.get();
            setter.accept(next);
            pressedButton.setMessage(this.booleanOptionMessage(labelKey, next));
        }).width(this.getButtonWidth()).tooltip(Tooltip.create(Component.translatable(descriptionKey))).build();
    }

    private void addFullWidthOption(Button button) {
        if (this.optionsList != null) this.optionsList.addOption(new OptionEntry(List.of(button), false));
    }

    private <T> void addTextOption(TextOption<T> option) {
        Component label = Component.translatable(option.labelKey());
        Component description = Component.translatable(option.descriptionKey());
        String initialValue = this.pendingTextValues.getOrDefault(option.id(), "");
        StringWidget labelWidget = new StringWidget(EDITABLE_LABEL_WIDTH, BUTTON_HEIGHT, label, this.font).alignLeft();
        EditBox editBox = new EditBox(this.font, 0, 0, Math.max(40, this.getButtonWidth() - EDITABLE_LABEL_WIDTH - EDITABLE_ROW_GAP), BUTTON_HEIGHT, label);
        labelWidget.setTooltip(Tooltip.create(description));
        editBox.setMaxLength(Math.max(option.maxLength(), initialValue.length()));
        editBox.setValue(initialValue);
        editBox.setTooltip(Tooltip.create(description));
        OptionEntry entry = new OptionEntry(List.of(labelWidget, editBox), true);
        TextOptionControl<T> control = new TextOptionControl<>(option, editBox, description, entry);
        editBox.setResponder(value -> {
            this.pendingTextValues.put(option.id(), value);
            control.validate();
        });
        this.visibleTextControls.add(control);
        if (this.optionsList != null) this.optionsList.addOption(entry);
    }

    private String parseDownloadMirror(String value) {
        return RinkuOptionsInput.parseMirror(value, this.settings.getDownloadMirrorPolicy() != RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
    }

    private void updatePreloadControls() {
        boolean active = this.settings.isBrowserPreloadEnabled();
        if (this.transparentPoolSizeButton != null) this.transparentPoolSizeButton.active = active;
        if (this.opaquePoolSizeButton != null) this.opaquePoolSizeButton.active = active;
    }

    private void validateVisibleTextOptions() {
        for (TextOptionControl<?> control : this.visibleTextControls) control.validate();
    }

    private boolean applyTextOptions() {
        TextOption<?> firstInvalid = null;
        for (TextOption<?> option : this.textOptions) {
            if (!this.isTextOptionValid(option) && firstInvalid == null) firstInvalid = option;
        }
        if (firstInvalid != null) {
            this.rememberScrollAmount();
            this.currentTabIndex = firstInvalid.tabIndex();
            this.rebuildWidgets();
            for (TextOptionControl<?> control : this.visibleTextControls) {
                if (control.option().id().equals(firstInvalid.id())) {
                    control.validate();
                    if (this.optionsList != null) this.optionsList.showEntry(control.entry());
                    break;
                }
            }
            return false;
        }
        for (TextOption<?> option : this.textOptions) this.applyTextOption(option);
        return true;
    }

    private <T> boolean isTextOptionValid(TextOption<T> option) {
        try {
            option.parser().apply(this.pendingTextValues.getOrDefault(option.id(), ""));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private <T> void applyTextOption(TextOption<T> option) {
        T parsedValue = option.parser().apply(this.pendingTextValues.getOrDefault(option.id(), ""));
        if (!Objects.equals(parsedValue, option.currentValue().get())) option.applier().accept(parsedValue);
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
        return Math.max(80, Math.min(BUTTON_ROW_MAX_WIDTH, this.width - 40));
    }

    private static <T> T nextValue(T current, T[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return values[(index + 1) % values.length];
        }
        return values[0];
    }

    @Override
    public void tick() {
        for (TextOptionControl<?> control : this.visibleTextControls) control.editBox().tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fill(0, HEADER_HEIGHT - 2, this.width, HEADER_HEIGHT, 0xFF000000);
        graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height - FOOTER_HEIGHT + 2, 0xFF000000);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!this.applyTextOptions()) return;
        Minecraft.getInstance().setScreen(this.parent);
    }

    private record TextOption<T>(int tabIndex, String id, String labelKey, String descriptionKey, int maxLength, Function<String, T> parser, Supplier<T> currentValue, Consumer<T> applier, Component invalidMessage) {
    }

    private final class TextOptionControl<T> {

        private final TextOption<T> option;
        private final EditBox editBox;
        private final Component description;
        private final OptionEntry entry;

        private TextOptionControl(TextOption<T> option, EditBox editBox, Component description, OptionEntry entry) {
            this.option = option;
            this.editBox = editBox;
            this.description = description;
            this.entry = entry;
        }

        private boolean validate() {
            try {
                this.option.parser().apply(this.editBox.getValue());
                this.editBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                this.editBox.setTooltip(Tooltip.create(this.description));
                return true;
            } catch (IllegalArgumentException exception) {
                this.editBox.setTextColor(INVALID_TEXT_COLOR);
                this.editBox.setTooltip(Tooltip.create(this.option.invalidMessage()));
                return false;
            }
        }

        private TextOption<T> option() {
            return this.option;
        }

        private EditBox editBox() {
            return this.editBox;
        }

        private OptionEntry entry() {
            return this.entry;
        }

    }

    private static final class OptionEntry extends ContainerObjectSelectionList.Entry<OptionEntry> {

        private final List<AbstractWidget> widgets;
        private final boolean editablePair;

        private OptionEntry(List<AbstractWidget> widgets, boolean editablePair) {
            this.widgets = widgets;
            this.editablePair = editablePair;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (!this.editablePair) {
                AbstractWidget widget = this.widgets.get(0);
                widget.setX(left);
                widget.setY(top);
                widget.setWidth(width);
                widget.render(graphics, mouseX, mouseY, partialTick);
                return;
            }
            int labelWidth = Math.min(EDITABLE_LABEL_WIDTH, Math.max(80, width / 2));
            AbstractWidget label = this.widgets.get(0);
            AbstractWidget editBox = this.widgets.get(1);
            label.setX(left);
            label.setY(top);
            label.setWidth(labelWidth);
            editBox.setX(left + labelWidth + EDITABLE_ROW_GAP);
            editBox.setY(top);
            editBox.setWidth(Math.max(40, width - labelWidth - EDITABLE_ROW_GAP));
            label.render(graphics, mouseX, mouseY, partialTick);
            editBox.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }

    }

    private static final class OptionsList extends ContainerObjectSelectionList<OptionEntry> {

        private OptionsList(Minecraft minecraft, int width, int height, int top, int bottom, int rowHeight) {
            super(minecraft, width, height, top, bottom, rowHeight);
            this.centerListVertically = false;
            this.setRenderBackground(false);
            this.setRenderTopAndBottom(false);
        }

        private void addOption(OptionEntry entry) {
            this.addEntry(entry);
        }

        private void showEntry(OptionEntry entry) {
            this.ensureVisible(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, Math.min(BUTTON_ROW_MAX_WIDTH, this.width - 40));
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + this.getRowWidth() / 2 + 4;
        }

    }

}
