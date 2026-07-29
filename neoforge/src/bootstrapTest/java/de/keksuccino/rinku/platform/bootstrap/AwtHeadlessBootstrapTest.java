package de.keksuccino.rinku.platform.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwtHeadlessBootstrapTest {

    @Test
    void enablesHeadlessModeWhenPropertyIsAbsent() {
        Properties properties = new Properties();

        AwtHeadlessBootstrap.enforce(properties);

        assertEquals("true", properties.getProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY));
    }

    @Test
    void overwritesUnsafeFalseValue() {
        Properties properties = new Properties();
        properties.setProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY, "false");

        AwtHeadlessBootstrap.enforce(properties);

        assertEquals("true", properties.getProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY));
    }

    @Test
    void preservesUnrelatedProperties() {
        Properties properties = new Properties();
        properties.setProperty("rinku.sentinel", "unchanged");

        AwtHeadlessBootstrap.enforce(properties);

        assertEquals("unchanged", properties.getProperty("rinku.sentinel"));
    }

    @Test
    void rejectsMissingPropertyContainer() {
        assertThrows(NullPointerException.class, () -> AwtHeadlessBootstrap.enforce(null));
    }
}
