package de.keksuccino.rinku.binarydownload;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RinkuDownloaderScreen extends Screen {

    @Nullable
    private final Screen parent;

    public RinkuDownloaderScreen(@Nullable Screen parent) {
        super(Component.translatable("rinku.downloader.title").withStyle(ChatFormatting.GOLD));
        this.parent = parent;
    }

    @Override
    public void render(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        double centerX = this.width / 2.0D;
        double centerY = this.height / 2.0D;
        double progressBarHeight = 14.0D;
        double progressBarWidth = this.width / 3.0D;

        poseStack.pushPose();
        poseStack.translate(centerX - progressBarWidth / 2.0D, centerY - progressBarHeight / 2.0D, 0.0D);
        fill(poseStack, 0, 0, (int) progressBarWidth, (int) progressBarHeight, -1);
        fill(poseStack, 2, 2, (int) progressBarWidth - 2, (int) progressBarHeight - 2, -16777215);
        fill(poseStack, 4, 4, (int) ((progressBarWidth - 4.0D) * RinkuDownloadListener.INSTANCE.getProgress()), (int) progressBarHeight - 4, -1);
        poseStack.popPose();

        Component[] lines = new Component[]{RinkuDownloadListener.INSTANCE.getTask(), Component.translatable("rinku.downloader.progress", Math.round(RinkuDownloadListener.INSTANCE.getProgress() * 100.0D))};
        int offset = this.font.lineHeight / 2 + (this.font.lineHeight + 2) * (lines.length + 2) + 4;
        poseStack.pushPose();
        poseStack.translate(centerX, centerY - offset, 0.0D);
        drawString(poseStack, this.font, this.title, -(this.font.width(this.title) / 2), 0, -1);
        int lineY = this.font.lineHeight + 2;
        for (Component line : lines) {
            drawString(poseStack, this.font, line, -(this.font.width(line) / 2), lineY, -1);
            lineY += this.font.lineHeight + 2;
        }
        poseStack.popPose();
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        if (RinkuDownloadListener.INSTANCE.isDone() || RinkuDownloadListener.INSTANCE.isFailed()) Minecraft.getInstance().setScreen(this.parent);
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
