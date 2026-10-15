package com.huanchengfly.icebridge.providers;

/** Pure boundary checks for the system-only acknowledgement; never a user-writable config key. */
final class ConfigProviderRuntimeStatus {
    private ConfigProviderRuntimeStatus() { }

    static void validate(int reportedUser, int currentUser, int schema, int expectedSchema,
                         String fingerprint, long revision, String message) {
        if (reportedUser < 0 || reportedUser > 999 || reportedUser != currentUser) {
            throw new IllegalArgumentException("Runtime status user mismatch");
        }
        if (schema != expectedSchema) throw new IllegalArgumentException("Runtime status schema mismatch");
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid runtime config fingerprint");
        }
        if (revision < 0) throw new IllegalArgumentException("Invalid runtime revision");
        if (message == null || message.length() > 256) throw new IllegalArgumentException("Invalid runtime status message");
    }
}
