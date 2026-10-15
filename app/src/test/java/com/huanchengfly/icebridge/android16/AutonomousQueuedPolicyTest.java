package com.huanchengfly.icebridge.android16;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutonomousQueuedPolicyTest {
    @Test public void disablingClipboardDropsPreviouslyCapturedWork() throws Exception {
        assertNull(SystemAutonomousRuntime.rebaseRequest(state(true, false, true), request("clipboard:old")));
    }

    @Test public void disablingNativeCaptureDoesNotReplayAnOldWebObservation() throws Exception {
        assertNull(SystemAutonomousRuntime.rebaseRequest(state(false, true, true), request("web-old")));
    }

    @Test public void newlyEnabledFrozenOnlyPolicyOverridesCapturedPolicy() throws Exception {
        AutonomousBridgeCoordinator.Request<Object> captured = request("web-old");
        AutonomousBridgeCoordinator.Request<Object> fresh = SystemAutonomousRuntime.rebaseRequest(
                state(true, true, true), captured);
        assertNotNull(fresh);
        assertTrue(fresh.onlyFrozen);
        assertFalse(captured.onlyFrozen);
        assertSame(captured.payload, fresh.payload);
    }

    @Test public void presentationOptionsDoNotDisableQueuedLinkOrClipboardRouting() throws Exception {
        for (boolean cardEnabled : new boolean[]{false, true}) {
            for (boolean smallWindow : new boolean[]{false, true}) {
                SystemRuntimeConfig.State state = new SystemRuntimeConfig.State(8, 0, 1,
                        true, true, true, true, true, true, true, true, false, new String[0],
                        SystemRuntimeConfig.OnlineState.empty(), 0xff7e57c2, "system", cardEnabled, smallWindow,
                        new String[]{"com.example.target"});
                for (String fingerprint : new String[]{"web-old", "clipboard:old"}) {
                    AutonomousBridgeCoordinator.Request<Object> captured = request(fingerprint);
                    AutonomousBridgeCoordinator.Request<Object> fresh =
                            SystemAutonomousRuntime.rebaseRequest(state, captured);
                    assertNotNull("Presentation options must not revoke an otherwise eligible route", fresh);
                    assertSame(captured.payload, fresh.payload);
                    assertEquals(captured.fingerprint, fresh.fingerprint);
                    assertTrue(fresh.onlyFrozen);
                    assertTrue(fresh.autoOpen);
                }
            }
        }
    }

    private static SystemRuntimeConfig.State state(boolean nativeCapture, boolean clipboard, boolean onlyFrozen)
            throws Exception {
        return new SystemRuntimeConfig.State(8, 0, 1, true, true, true, nativeCapture,
                clipboard, true, onlyFrozen, true, false, new String[0], SystemRuntimeConfig.OnlineState.empty());
    }

    private static AutonomousBridgeCoordinator.Request<Object> request(String fingerprint) {
        return new AutonomousBridgeCoordinator.Request<>(AutonomousBridgeCoordinator.Kind.WEB_RULE,
                0, "*", "example.com", null, fingerprint, new Object(), false, true, false);
    }
}
