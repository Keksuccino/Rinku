package de.keksuccino.rinku.platform.bootstrap;

import java.util.Objects;
import java.util.Properties;

/** Applies Minecraft's headless-AWT requirement before loader graphics code can cache another mode. */
final class AwtHeadlessBootstrap {

    static final String HEADLESS_PROPERTY = "java.awt.headless";

    private AwtHeadlessBootstrap() {}

    static void enforce() {
        enforce(System.getProperties());
    }

    static void enforce(Properties properties) {
        Objects.requireNonNull(properties, "properties").setProperty(HEADLESS_PROPERTY, Boolean.TRUE.toString());
    }
}
