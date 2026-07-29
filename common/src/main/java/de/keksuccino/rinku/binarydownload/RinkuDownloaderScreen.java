package de.keksuccino.rinku.binarydownload;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RinkuDownloaderScreen extends Screen {

    private final Screen parent;

    public RinkuDownloaderScreen(@Nullable Screen parent) {
        super(Component.translatable("rinku.downloader.title").withStyle(ChatFormatting.GOLD));
        this.parent = parent;
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        super.render(graphics, mouseX, mouseY, partialTick);

        double cx = width / 2d;
        double cy = height / 2d;
        double progressBarHeight = 14;
        double progressBarWidth = width / 3d;

        PoseStack matrix = graphics.pose();

        /* Draw Progress Bar */
        matrix.pushPose();
        matrix.translate((float) cx, (float) cy, 0.0F);
        matrix.translate((float) (-progressBarWidth / 2d), (float) (-progressBarHeight / 2d), 0.0F);
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
        matrix.popPose();

        // putting this here incase I want to re-add a third line later on
        // allows me to generalize the code to not care about line count
        Component[] text = new Component[] {
                RinkuDownloadListener.INSTANCE.getTask(),
                Component.translatable("rinku.downloader.progress", Math.round(RinkuDownloadListener.INSTANCE.getProgress() * 100)),
        };

        /* Draw Text */

        // calculate offset for the top line
        int oSet = ((font.lineHeight / 2) + ((font.lineHeight + 2) * (text.length + 2))) + 4;
        matrix.pushPose();
        matrix.translate((float) cx, (float) (cy - oSet), 0.0F);
        // draw menu name
        graphics.drawString(this.font, this.title, (int) -(font.width(this.title) / 2d), 0, -1);
        // draw other text
        int index = 0;
        for (Component s : text) {
            if (index == 1) {
                matrix.translate(0.0F, font.lineHeight + 2.0F, 0.0F);
            }
            matrix.translate(0.0F, font.lineHeight + 2.0F, 0.0F);
            graphics.drawString(this.font, s, (int) -(font.width(s) / 2d), 0, -1);
            index++;
        }
        matrix.popPose();

    }

    @Override
    public void tick() {
        if (RinkuDownloadListener.INSTANCE.isDone() || RinkuDownloadListener.INSTANCE.isFailed()) {
            Minecraft.getInstance().setScreen(this.parent);
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
