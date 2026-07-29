package de.keksuccino.rinku;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;

import java.util.List;

/** Native 1.21.1 scrolling container used by each options tab. */
final class RinkuOptionsList extends ContainerObjectSelectionList<RinkuOptionsList.Entry> {

    private static final int ROW_MAX_WIDTH = 360;
    private static final int ROW_SIDE_MARGIN = 20;
    private static final int EDITABLE_LABEL_WIDTH = 155;
    private static final int EDITABLE_ROW_GAP = 5;

    RinkuOptionsList(Minecraft minecraft, int width) {
        super(minecraft, width, 20, 0, 26);
        this.centerListVertically = false;
    }

    void addFullWidth(AbstractWidget widget) {
        this.addEntry(Entry.fullWidth(widget));
        this.reflowRows();
    }

    void addPair(AbstractWidget first, AbstractWidget second) {
        this.addEntry(Entry.pair(first, second));
        this.reflowRows();
    }

    void updateBounds(ScreenRectangle rectangle) {
        int top = rectangle.top() + 4;
        this.updateSizeAndPosition(rectangle.width(), Math.max(20, rectangle.height() - 4), top);
        this.reflowRows();
    }

    private void reflowRows() {
        int rowWidth = this.getRowWidth();
        for (Entry entry : this.children()) entry.setAvailableWidth(rowWidth);
    }

    @Override
    public int getRowWidth() {
        return Math.min(ROW_MAX_WIDTH, Math.max(40, this.getWidth() - ROW_SIDE_MARGIN * 2));
    }

    static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

        private final List<AbstractWidget> children;
        private final boolean paired;
        private int availableWidth;

        private Entry(List<AbstractWidget> children, boolean paired) {
            this.children = List.copyOf(children);
            this.paired = paired;
        }

        static Entry fullWidth(AbstractWidget widget) {
            return new Entry(List.of(widget), false);
        }

        static Entry pair(AbstractWidget first, AbstractWidget second) {
            return new Entry(List.of(first, second), true);
        }

        void setAvailableWidth(int availableWidth) {
            this.availableWidth = availableWidth;
            if (!this.paired) {
                this.children.getFirst().setWidth(availableWidth);
                return;
            }

            int labelWidth = Math.min(EDITABLE_LABEL_WIDTH, Math.max(80, availableWidth / 2));
            this.children.get(0).setWidth(labelWidth);
            this.children.get(1).setWidth(Math.max(40, availableWidth - labelWidth - EDITABLE_ROW_GAP));
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (this.availableWidth != width) this.setAvailableWidth(width);
            int x = left;
            for (AbstractWidget widget : this.children) {
                widget.setPosition(x, top);
                widget.render(graphics, mouseX, mouseY, partialTick);
                x += widget.getWidth() + (this.paired ? EDITABLE_ROW_GAP : 0);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.children;
        }

    }

}
