package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JcefRuntimeIdentityTest {
    @Test
    void runtimeIdentityMatchesTheBuildAndCannotBeOverridden() throws IOException {
        String expectedCommit = System.getProperty("rinku.test.jcefCommit");
        String incompatibleCommit = "0000000000000000000000000000000000000000";
        String previousOverride = System.setProperty("rinku.java.cef.commit", incompatibleCommit);
        try {
            assertNotEquals(incompatibleCommit, expectedCommit);
            assertEquals(expectedCommit, JcefRuntimeIdentity.JAVA_CEF_COMMIT);
            assertEquals(expectedCommit, Rinku.getJavaCefCommit());
        } finally {
            if (previousOverride == null) {
                System.clearProperty("rinku.java.cef.commit");
            } else {
                System.setProperty("rinku.java.cef.commit", previousOverride);
            }
        }
    }
}
