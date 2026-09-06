package com.huanchengfly.icebridge.engines;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RootCommandRunnerTest {
    @Test
    public void recognizesRootUidWithGroups() {
        assertTrue(RootCommandRunner.containsRootUid("uid=0(root) gid=0(root) groups=0(root)"));
    }

    @Test
    public void rejectsNonRootUid() {
        assertFalse(RootCommandRunner.containsRootUid("uid=2000(shell) gid=2000(shell)"));
    }

    @Test
    public void rejectsRootTextWithoutUidField() {
        assertFalse(RootCommandRunner.containsRootUid("root access granted"));
    }
}
