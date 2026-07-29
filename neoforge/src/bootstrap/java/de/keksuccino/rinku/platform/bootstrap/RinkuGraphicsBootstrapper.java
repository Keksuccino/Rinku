package de.keksuccino.rinku.platform.bootstrap;

import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;

/**
 * Establishes Minecraft's headless AWT mode before NeoForge creates its early loading window.
 *
 * <p>This provider lives in the outer FML library wrapper, while the normal Rinku mod is nested inside that
 * wrapper. It must stay free of Minecraft, logging, and other UI initialization: touching those here can defeat
 * the ordering that prevents AWT from taking over GLFW's macOS event loop.</p>
 */
public final class RinkuGraphicsBootstrapper implements GraphicsBootstrapper {

    @Override
    public String name() {
        return "Rinku headless AWT bootstrap";
    }

    @Override
    public void bootstrap(String[] arguments) {
        AwtHeadlessBootstrap.enforce();
    }
}
