package com.huanchengfly.icebridge.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Pure commit/revocation policy; the Android adapter only implements private atomic file access. */
final class SystemRuntimeConfigPersistence {
    enum Slot { CONFIG, LAST_GOOD, REVOKED }

    interface Storage {
        boolean exists(int userId, Slot slot) throws IOException;
        byte[] read(int userId, Slot slot) throws IOException;
        void write(int userId, Slot slot, byte[] bytes) throws IOException;
        void delete(int userId, Slot slot) throws IOException;
    }

    private static final byte[] REVOKED = "disabled\n".getBytes(StandardCharsets.US_ASCII);
    private final Storage storage;

    SystemRuntimeConfigPersistence(Storage storage) { this.storage = storage; }

    SystemRuntimeConfig.State load(int userId, int expectedProviderSchema) throws IOException {
        SystemRuntimeConfig.checkUserId(userId);
        // Even a torn marker write revokes old state. A later enable removes it after commit.
        if (storage.exists(userId, Slot.REVOKED)) return null;
        IOException primaryFailure = null;
        try {
            SystemRuntimeConfig.State state = read(userId, Slot.CONFIG, expectedProviderSchema);
            if (state != null) return state.autonomousEnabled ? state : null;
        } catch (IOException error) {
            primaryFailure = error;
        }
        try {
            SystemRuntimeConfig.State state = read(userId, Slot.LAST_GOOD, expectedProviderSchema);
            if (state == null) {
                if (primaryFailure != null) throw primaryFailure;
                return null;
            }
            if (!state.autonomousEnabled) return null;
            // Replacing the corrupt main file is itself atomic and never crosses user IDs.
            storage.write(userId, Slot.CONFIG, SystemRuntimeConfig.encode(state));
            return state;
        } catch (IOException error) {
            if (primaryFailure != null && primaryFailure != error) error.addSuppressed(primaryFailure);
            throw error;
        }
    }

    void save(SystemRuntimeConfig.State state) throws IOException {
        if (state == null) throw new IOException("runtime-null");
        SystemRuntimeConfig.checkUserId(state.userId);
        if (!state.autonomousEnabled) {
            disable(state.userId);
            return;
        }
        byte[] bytes = SystemRuntimeConfig.encode(state);
        storage.write(state.userId, Slot.CONFIG, bytes);
        storage.write(state.userId, Slot.LAST_GOOD, bytes);
        // A failed save leaves an existing revocation in force.
        storage.delete(state.userId, Slot.REVOKED);
    }

    void disable(int userId) throws IOException {
        SystemRuntimeConfig.checkUserId(userId);
        // Revocation is durable before deleting either recoverable enabled snapshot.
        storage.write(userId, Slot.REVOKED, REVOKED);
        IOException failure = null;
        try { storage.delete(userId, Slot.CONFIG); } catch (IOException error) { failure = error; }
        try { storage.delete(userId, Slot.LAST_GOOD); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }

    private SystemRuntimeConfig.State read(int userId, Slot slot, int schema) throws IOException {
        byte[] bytes = storage.read(userId, slot);
        return bytes == null ? null : SystemRuntimeConfig.decode(bytes, userId, schema);
    }
}
