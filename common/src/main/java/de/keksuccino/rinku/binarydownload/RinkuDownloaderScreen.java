package de.keksuccino.rinku.binarydownload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

public class RinkuDownloaderScreen extends Screen {

    private final Screen parent;

    public RinkuDownloaderScreen(@Nullable Screen parent) {
        super(Component.literal("Rinku is downloading required libraries..").withStyle(ChatFormatting.GOLD));
        this.parent = parent;
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        double cx = width / 2d;
        double cy = height / 2d;
        double progressBarHeight = 14;
        double progressBarWidth = width / 3d;

        Matrix3x2fStack matrix = graphics.pose();

        /* Draw Progress Bar */
        matrix.pushMatrix();
        matrix.translate((float) cx, (float) cy);
        matrix.translate((float) (-progressBarWidth / 2d), (float) (-progressBarHeight / 2d));
        graphics.fill( // bar border
                0, 0,
                (int) progressBarWidth,
                (int) progressBarHeight,
                -1
        );
        graphics.fill( // bar padding
                2, 2,
                (int) progressBarWidth - 2,
                (int) progressBarHeight - 2,
                -16777215
        );
        graphics.fill( // bar bar
                4, 4,
                (int) ((progressBarWidth - 4) * RinkuDownloadListener.INSTANCE.getProgress()),
                (int) progressBarHeight - 4,
                -1
        );
        matrix.popMatrix();

        // putting this here incase I want to re-add a third line later on
        // allows me to generalize the code to not care about line count
        String[] text = new String[] {
                RinkuDownloadListener.INSTANCE.getTask(),
                Math.round(RinkuDownloadListener.INSTANCE.getProgress() * 100) + "%",
        };

        /* Draw Text */

        // calculate offset for the top line
        int oSet = ((font.lineHeight / 2) + ((font.lineHeight + 2) * (text.length + 2))) + 4;
        matrix.pushMatrix();
        matrix.translate((float) cx, (float) (cy - oSet));
        // draw menu name
        graphics.text(this.font, this.title, (int) -(font.width(this.title) / 2d), 0, -1);
        // draw other text
        int index = 0;
        for (String s : text) {
            if (index == 1) {
                matrix.translate(0.0F, font.lineHeight + 2.0F);
            }
            matrix.translate(0.0F, font.lineHeight + 2.0F);
            graphics.text(this.font, s, (int) -(font.width(s) / 2d), 0, -1);
            index++;
        }
        matrix.popMatrix();

    }

    @Override
    public void tick() {
        if (RinkuDownloadListener.INSTANCE.isDone() || RinkuDownloadListener.INSTANCE.isFailed()) {
            onClose();
            Minecraft.getInstance().gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

}
