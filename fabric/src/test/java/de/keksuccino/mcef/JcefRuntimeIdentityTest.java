package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JcefRuntimeIdentityTest {
    @Test
    void runtimeIdentityMatchesTheBuildAndCannotBeOverridden() throws IOException {
        String expectedCommit = System.getProperty("mcef.test.jcefCommit");
        String incompatibleCommit = "0000000000000000000000000000000000000000";
        String previousOverride = System.setProperty("mcef.java.cef.commit", incompatibleCommit);
        try {
            assertNotEquals(incompatibleCommit, expectedCommit);
            assertEquals(expectedCommit, JcefRuntimeIdentity.JAVA_CEF_COMMIT);
            assertEquals(expectedCommit, MCEF.getJavaCefCommit());
        } finally {
            if (previousOverride == null) {
                System.clearProperty("mcef.java.cef.commit");
            } else {
                System.setProperty("mcef.java.cef.commit", previousOverride);
            }
        }
    }
}
