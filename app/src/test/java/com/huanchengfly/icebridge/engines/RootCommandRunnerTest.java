package com.huanchengfly.icebridge.engines;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RootCommandRunnerTest {
    @Test public void packageMutationAlwaysUsesExplicitAppUser() {
        assertEquals("pm enable --user 10 com.example.app && pm unhide --user 10 com.example.app",
                RootCommandRunner.packageStateCommand("com.example.app", true, 10));
        assertEquals("pm disable-user --user 0 com.example.app",
                RootCommandRunner.packageStateCommand("com.example.app", false, 0));
        assertNull(RootCommandRunner.packageStateCommand("com.example.app", true, -1));
        assertNull(RootCommandRunner.packageStateCommand("com.example;id", true, 0));
    }

    @Test public void fixedConversionRunsOnlyAfterOriginalHttpAndTargetValidation() {
        RootCommandRunner.FixedLinkConverter neverRun = (source, target) -> {
            throw new AssertionError("invalid input must not reach the trusted converter");
        };
        assertNull(RootCommandRunner.viewCommand("pan://share/123", "com.example.app", 0, true, neverRun));
        assertNull(RootCommandRunner.viewCommand("https://example.com", "com.example;id", 0, true, neverRun));
        assertNull(RootCommandRunner.viewCommand("https://example.com", "com.example.app", -1, true, neverRun));
    }

    @Test public void fixedConversionPreservesQuotedCatalogQueryAndExplicitUser() {
        String command = RootCommandRunner.viewCommand("https://123pan.com/s/share", "com.example.app", 10,
                true, (source, target) -> "pan://umeng.com/share/list?shareKey=abc&sharePwd=123");
        assertEquals("am start --user 10 -a android.intent.action.VIEW -d "
                + "'pan://umeng.com/share/list?shareKey=abc&sharePwd=123' -p 'com.example.app'", command);
    }

    @Test public void noConversionDoesNotInvokeCatalogAndShellQuotesStayLiteral() {
        assertEquals("am start --user 0 -a android.intent.action.VIEW -d 'https://example.com/path' -p 'com.example.app'",
                RootCommandRunner.viewCommand("https://example.com/path", "com.example.app", 0,
                        false, (source, target) -> { throw new AssertionError("conversion disabled"); }));
        assertEquals("am start --user 0 -a android.intent.action.VIEW -d 'pan://host/a'\"'\"'b' -p 'com.example.app'",
                RootCommandRunner.viewCommand("https://example.com/path", "com.example.app", 0,
                        true, (source, target) -> "pan://host/a'b"));
    }

    @Test public void malformedConverterOutputCannotBecomeShellInput() {
        assertNull(RootCommandRunner.viewCommand("https://example.com", "com.example.app", 0,
                true, (source, target) -> "pan://host/value\ncommand"));
    }
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

    @Test
    public void rejectsCommandInjectionInputs() {
        assertFalse(RootCommandRunner.isValidPackageName("com.example; id"));
        assertFalse(RootCommandRunner.isValidWebUrl("https://example.com/a' ; id"));
        assertFalse(RootCommandRunner.isValidUserId(-1));
    }

    @Test
    public void acceptsStructuredRootArguments() {
        assertTrue(RootCommandRunner.isValidPackageName("com.example.app"));
        assertTrue(RootCommandRunner.isValidWebUrl("https://example.com/path"));
        assertTrue(RootCommandRunner.isValidUserId(10));
    }
}
