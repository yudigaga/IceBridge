package com.huanchengfly.icebridge.providers;

import org.junit.Test;

import static org.junit.Assert.fail;

public class ConfigProviderRuntimeStatusTest {
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void acceptsBoundedStatusForCurrentUserAndSchema() {
        ConfigProviderRuntimeStatus.validate(10, 10, 8, 8, HASH, 5, "ready");
    }

    @Test public void wrongUsersOrOldSchemasCannotAcknowledgeCurrentConfiguration() {
        rejects(() -> ConfigProviderRuntimeStatus.validate(10, 0, 8, 8, HASH, 5, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(-1, -1, 8, 8, HASH, 5, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(1000, 1000, 8, 8, HASH, 5, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 7, 8, HASH, 5, "ready"));
    }

    @Test public void statusRequiresFingerprintAndBoundedNonnegativeMetadata() {
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 8, 8, "", 5, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 8, 8, null, 5, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 8, 8, HASH, -1, "ready"));
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 8, 8, HASH, 5, null));
        rejects(() -> ConfigProviderRuntimeStatus.validate(0, 0, 8, 8, HASH, 5, new String(new char[257])));
    }

    private static void rejects(Runnable action) {
        try { action.run(); fail("accepted invalid runtime status"); }
        catch (IllegalArgumentException expected) { }
    }
}
