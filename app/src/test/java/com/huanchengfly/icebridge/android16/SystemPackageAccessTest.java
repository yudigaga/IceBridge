package com.huanchengfly.icebridge.android16;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator.State;

import org.junit.Test;

import static org.junit.Assert.*;

/** Reflection contract tests; Binder identity calls use the Android unit-test stub. */
public class SystemPackageAccessTest {
    private static final String TARGET = "com.example.target";

    @Test public void modernLongFlagsAndAospThreeArgumentHiddenSetterKeepUser() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        pm.enabled = 3;
        pm.info.enabled = false;
        pm.hidden = true;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        State state = access.read(TARGET, 10);
        assertTrue(state.isRecoverable());
        assertEquals(10, pm.readUser);
        assertEquals(PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS,
                pm.queryFlags);
        assertNull(access.thaw(TARGET, 10, state));
        assertEquals(10, pm.enableUser);
        assertEquals(10, pm.hiddenUser);
        assertTrue(access.read(TARGET, 10).isLaunchable());
        assertTrue(access.capabilities().description.contains("flags=long"));
    }

    @Test public void legacyIntFlagsAndFiveArgumentHiddenSetterAreRecognized() {
        IntFlagsPackageManager pm = new IntFlagsPackageManager();
        pm.hidden = true;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        assertNull(access.thaw(TARGET, 11, access.read(TARGET, 11)));
        assertEquals(11, pm.hiddenUser);
        assertEquals(1_000, pm.hiddenCallingUid);
        assertEquals("android", pm.hiddenCallingPackage);
        assertEquals(0, pm.enables);
        assertTrue(access.read(TARGET, 11).isLaunchable());
        assertTrue(access.capabilities().description.contains("flags=int"));
    }

    @Test public void enabledOnlyFreezeDoesNotWriteHiddenState() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        pm.enabled = 2;
        pm.info.enabled = false;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        assertNull(access.thaw(TARGET, 12, access.read(TARGET, 12)));
        assertEquals(1, pm.enables);
        assertEquals(0, pm.unhides);
        assertEquals("android", pm.enableCallingPackage);
        assertTrue(access.read(TARGET, 12).isLaunchable());
    }

    @Test public void uninstalledForUserAndMissingApplicationAreNeverThawed() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        pm.info.flags = 0;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        State state = access.read(TARGET, 10);
        assertTrue(state.isKnown());
        assertFalse(state.installed);
        assertEquals("invalid-state", access.thaw(TARGET, 10, state));
        pm.info = null;
        state = access.read(TARGET, 10);
        assertTrue(state.isKnown());
        assertFalse(state.installed);
        assertEquals(0, pm.enables);
        assertEquals(0, pm.unhides);
    }

    @Test public void suspensionHasExplicitUnsupportedCapabilityAndIsNeverUndone() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        pm.suspended = true;
        pm.enabled = 3;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        State state = access.read(TARGET, 0);
        assertTrue(state.suspendedKnown);
        assertTrue(state.suspended);
        assertEquals("invalid-state", access.thaw(TARGET, 0, state));
        assertEquals(0, pm.enables);
        assertTrue(access.capabilities().description.contains("unsuspend=false"));
    }

    @Test public void permissionFailureFailsClosedAndUnwrapsInvocationException() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        pm.enabled = 3;
        pm.failSetter = true;
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        State state = access.read(TARGET, 0);
        assertEquals("setter-SecurityException", access.thaw(TARGET, 0, state));
        assertEquals(3, pm.enabled);
        pm.failReader = true;
        assertFalse(access.read(TARGET, 0).isKnown());
        assertEquals("package-read-SecurityException", access.read(TARGET, 0).error);
    }

    @Test public void unknownUserMissingProxyAndRejectedUnhideFailClosed() {
        LongFlagsPackageManager pm = new LongFlagsPackageManager();
        SystemPackageAccess access = SystemPackageAccess.fromPackageManager(pm);
        assertFalse(access.read(TARGET, -1).isKnown());
        assertEquals(-1, pm.readUser);
        assertFalse(SystemPackageAccess.fromPackageManager(null).read(TARGET, 0).isKnown());
        assertFalse(SystemPackageAccess.fromPackageManager(new Object()).capabilities().enableDisabled);
        pm.hidden = true;
        pm.rejectUnhide = true;
        assertEquals("unhide-rejected", access.thaw(TARGET, 0, access.read(TARGET, 0)));
        assertTrue(pm.hidden);
    }

    public abstract static class PackageManagerBase {
        ApplicationInfo info = new ApplicationInfo();
        int enabled;
        boolean hidden;
        boolean suspended;
        boolean failSetter;
        boolean failReader;
        boolean rejectUnhide;
        int readUser = -1;
        long queryFlags;
        int enableUser = -1;
        int hiddenUser = -1;
        int hiddenCallingUid = -1;
        String hiddenCallingPackage;
        String enableCallingPackage;
        int enables;
        int unhides;

        PackageManagerBase() {
            info.flags = ApplicationInfo.FLAG_INSTALLED;
            info.enabled = true;
        }

        ApplicationInfo applicationInfo(String packageName, long flags, int user) {
            if (failReader) throw new SecurityException("blocked");
            assertEquals(TARGET, packageName);
            queryFlags = flags;
            readUser = user;
            return info;
        }

        public int getApplicationEnabledSetting(String packageName, int user) { return enabled; }
        public boolean getApplicationHiddenSettingAsUser(String packageName, int user) { return hidden; }
        public boolean isPackageSuspendedForUser(String packageName, int user) { return suspended; }
        public void setApplicationEnabledSetting(String packageName, int state, int flags,
                                                 int user, String caller) {
            if (failSetter) throw new SecurityException("blocked");
            assertEquals(0, flags);
            enables++;
            enabled = state;
            info.enabled = true;
            enableUser = user;
            enableCallingPackage = caller;
        }

        boolean unhide(boolean value, int user) {
            unhides++;
            hiddenUser = user;
            if (rejectUnhide) return false;
            hidden = value;
            return true;
        }
    }

    public static final class LongFlagsPackageManager extends PackageManagerBase {
        public ApplicationInfo getApplicationInfo(String packageName, long flags, int user) {
            return applicationInfo(packageName, flags, user);
        }
        public boolean setApplicationHiddenSettingAsUser(String packageName, boolean value, int user) {
            return unhide(value, user);
        }
    }

    public static final class IntFlagsPackageManager extends PackageManagerBase {
        public ApplicationInfo getApplicationInfo(String packageName, int flags, int user) {
            return applicationInfo(packageName, flags, user);
        }
        public boolean setApplicationHiddenSettingAsUser(String packageName, boolean value,
                int user, int callingUid, String caller) {
            hiddenCallingUid = callingUid;
            hiddenCallingPackage = caller;
            return unhide(value, user);
        }
    }
}
