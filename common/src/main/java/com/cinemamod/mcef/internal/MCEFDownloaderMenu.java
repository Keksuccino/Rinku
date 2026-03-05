/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef.internal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

public class MCEFDownloaderMenu extends Screen {
    private final Screen menu;

    public MCEFDownloaderMenu(Screen menu) {
        super(Component.literal("MCEF is downloading required libraries...").withStyle(ChatFormatting.GOLD));
        this.menu = menu;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
                (int) ((progressBarWidth - 4) * MCEFDownloadListener.INSTANCE.getProgress()),
                (int) progressBarHeight - 4,
                -1
        );
        matrix.popMatrix();

        // putting this here incase I want to re-add a third line later on
        // allows me to generalize the code to not care about line count
        String[] text = new String[] {
                MCEFDownloadListener.INSTANCE.getTask(),
                Math.round(MCEFDownloadListener.INSTANCE.getProgress() * 100) + "%",
        };

        /* Draw Text */

        // calculate offset for the top line
        int oSet = ((font.lineHeight / 2) + ((font.lineHeight + 2) * (text.length + 2))) + 4;
        matrix.pushMatrix();
        matrix.translate((float) cx, (float) (cy - oSet));
        // draw menu name
        graphics.drawString(this.font, this.title, (int) -(font.width(this.title) / 2d), 0, -1);
        // draw other text
        int index = 0;
        for (String s : text) {
            if (index == 1) {
                matrix.translate(0.0F, font.lineHeight + 2.0F);
            }
            matrix.translate(0.0F, font.lineHeight + 2.0F);
            graphics.drawString(this.font, s, (int) -(font.width(s) / 2d), 0, -1);
            index++;
        }
        matrix.popMatrix();

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
