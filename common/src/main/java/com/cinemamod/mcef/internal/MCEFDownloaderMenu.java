package com.cinemamod.mcef.internal;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class MCEFDownloaderMenu extends Screen {
    private final Screen menu;

    public MCEFDownloaderMenu(Screen menu) {
        super(Component.literal("MCEF is downloading required libraries...").withStyle(ChatFormatting.GOLD));
        this.menu = menu;
    }

    @Override
    public void render(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        double cx = width / 2d;
        double cy = height / 2d;

        double progressBarHeight = 14;
        double progressBarWidth = width / 3d;

        this.renderBackground(poseStack);

        /* Draw Progress Bar */
        poseStack.pushPose();
        poseStack.translate((float) cx, (float) cy, 0.0F);
        poseStack.translate((float) (-progressBarWidth / 2d), (float) (-progressBarHeight / 2d), 0.0F);
        fill(poseStack, // bar border
                0, 0,
                (int) progressBarWidth,
                (int) progressBarHeight,
                -1
        );
        fill(poseStack, // bar padding
                2, 2,
                (int) progressBarWidth - 2,
                (int) progressBarHeight - 2,
                -16777215
        );
        fill(poseStack, // bar bar
                4, 4,
                (int) ((progressBarWidth - 4) * MCEFDownloadListener.INSTANCE.getProgress()),
                (int) progressBarHeight - 4,
                -1
        );
        poseStack.popPose();

        // putting this here incase I want to re-add a third line later on
        // allows me to generalize the code to not care about line count
        String[] text = new String[] {
                MCEFDownloadListener.INSTANCE.getTask(),
                Math.round(MCEFDownloadListener.INSTANCE.getProgress() * 100) + "%",
        };

        /* Draw Text */

        // calculate offset for the top line
        int oSet = ((font.lineHeight / 2) + ((font.lineHeight + 2) * (text.length + 2))) + 4;
        poseStack.pushPose();
        poseStack.translate((float) cx, (float) (cy - oSet), 0.0F);
        // draw menu name
        drawString(poseStack, this.font, this.title, (int) -(font.width(this.title) / 2d), 0, -1);
        // draw other text
        int index = 0;
        for (String s : text) {
            if (index == 1) {
                poseStack.translate(0.0F, font.lineHeight + 2.0F, 0.0F);
            }
            poseStack.translate(0.0F, font.lineHeight + 2.0F, 0.0F);
            drawString(poseStack, this.font, s, (int) -(font.width(s) / 2d), 0, -1);
            index++;
        }
        poseStack.popPose();

    }

    @Override
    public void tick() {
        if (MCEFDownloadListener.INSTANCE.isDone() || MCEFDownloadListener.INSTANCE.isFailed()) {
            onClose();
            Minecraft.getInstance().setScreen(menu);
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
