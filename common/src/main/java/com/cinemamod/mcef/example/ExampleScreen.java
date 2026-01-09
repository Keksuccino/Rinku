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

package com.cinemamod.mcef.example;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

public class ExampleScreen extends Screen {

    private static final int BROWSER_DRAW_OFFSET = 20;

    private MCEFBrowser browser;

    public ExampleScreen(Component component) {
        super(component);
    }

    @Override
    protected void init() {
        super.init();
        if (browser == null) {
            String url = "https://www.google.com";
            boolean transparent = true;
            browser = MCEF.createBrowser(url, transparent);
            resizeBrowser();
        }
    }

    private int mouseX(double x) {
        return (int) ((x - BROWSER_DRAW_OFFSET) * minecraft.getWindow().getGuiScale());
    }

    private int mouseY(double y) {
        return (int) ((y - BROWSER_DRAW_OFFSET) * minecraft.getWindow().getGuiScale());
    }

    private int scaleX(double x) {
        return (int) ((x - BROWSER_DRAW_OFFSET * 2) * minecraft.getWindow().getGuiScale());
    }

    private int scaleY(double y) {
        return (int) ((y - BROWSER_DRAW_OFFSET * 2) * minecraft.getWindow().getGuiScale());
    }

    private void resizeBrowser() {
        if (width > 100 && height > 100) {
            browser.resize(scaleX(width), scaleY(height));
        }
    }

    @Override
    public void resize(int i, int j) {
        super.resize(i, j);
        resizeBrowser();
    }

    @Override
    public void onClose() {
        browser.close();
        super.onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {

        super.render(guiGraphics, mouseX, mouseY, partial);
        
        // Check if the browser texture is ready for rendering
        if (browser != null && browser.isTextureReady()) {
            renderBrowserTexture(guiGraphics);
        }

    }
    
    private void renderBrowserTexture(GuiGraphics guiGraphics) {

        // Get the Identifier for the browser texture
        Identifier textureLocation = browser.getTextureIdentifier();

        int frameRenderWidth = width - BROWSER_DRAW_OFFSET * 2;
        int frameRenderHeight = height - BROWSER_DRAW_OFFSET * 2;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, textureLocation, BROWSER_DRAW_OFFSET, BROWSER_DRAW_OFFSET, 0.0F, 0.0F, frameRenderWidth, frameRenderHeight, frameRenderWidth, frameRenderHeight);

    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        browser.sendMousePress(mouseX(event.x()), mouseY(event.y()), event.button());
        browser.setFocus(true);
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        browser.sendMouseRelease(mouseX(event.x()), mouseY(event.y()), event.button());
        browser.setFocus(true);
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        browser.sendMouseMove(mouseX(mouseX), mouseY(mouseY));
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        browser.sendMouseWheel(mouseX(mouseX), mouseY(mouseY), scrollY, 0);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        browser.setFocus(true);
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        browser.setFocus(true);
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.codepoint() == (char) 0) return false;
        browser.sendKeyTyped((char) event.codepoint(), event.modifiers());
        browser.setFocus(true);
        return super.charTyped(event);
    }

}
