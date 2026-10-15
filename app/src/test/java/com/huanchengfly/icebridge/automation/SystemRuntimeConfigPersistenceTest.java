package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.huanchengfly.icebridge.automation.SystemRuntimeConfigPersistence.Slot.CONFIG;
import static com.huanchengfly.icebridge.automation.SystemRuntimeConfigPersistence.Slot.LAST_GOOD;
import static com.huanchengfly.icebridge.automation.SystemRuntimeConfigPersistence.Slot.REVOKED;
import static org.junit.Assert.*;

public class SystemRuntimeConfigPersistenceTest {
    private static final int SCHEMA = 8;
    private static final String[] RULES = {"com.tencent.mm|example.com|com.example.reader|manual|fixed_deep_link"};

    @Test public void missingConfigurationDoesNotImplicitlyEnableIndependentOperation() throws Exception {
        assertNull(new SystemRuntimeConfigPersistence(new MemoryStorage()).load(0, SCHEMA));
    }

    @Test public void restartLoadsLastSuccessfullyCommittedPerUserConfiguration() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        runtime.save(state(10, true));
        SystemRuntimeConfigPersistence restarted = new SystemRuntimeConfigPersistence(storage);
        assertEquals(0, restarted.load(0, SCHEMA).userId);
        assertEquals(10, restarted.load(10, SCHEMA).userId);
        assertNull(restarted.load(11, SCHEMA));
    }

    @Test public void corruptPrimaryRecoversEntireLastGoodEnvelopeAndRepairsPrimary() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        byte[] original = storage.read(0, LAST_GOOD);
        storage.write(0, CONFIG, "corrupt".getBytes(StandardCharsets.UTF_8));
        SystemRuntimeConfig.State recovered = runtime.load(0, SCHEMA);
        assertTrue(recovered.autonomousEnabled);
        assertArrayEquals(RULES, recovered.encodedRules());
        assertArrayEquals(original, storage.read(0, CONFIG));
    }

    @Test public void neitherCorruptionNorSchemaMismatchReturnsPartialConfiguration() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        try { runtime.load(0, SCHEMA + 1); fail("accepted old schema"); } catch (IOException expected) { }
        storage.write(0, CONFIG, new byte[]{0});
        storage.write(0, LAST_GOOD, new byte[]{1});
        try { runtime.load(0, SCHEMA); fail("accepted corrupt state"); } catch (IOException expected) { }
    }

    @Test public void backupCannotBeBorrowedFromAnotherUser() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        storage.write(0, LAST_GOOD, SystemRuntimeConfig.encode(state(10, true)));
        try { runtime.load(0, SCHEMA); fail("cross-user restore"); }
        catch (IOException expected) { assertEquals("runtime-user-mismatch", expected.getMessage()); }
    }

    @Test public void disableRevokesBeforeRemovingBackupsAndDoesNotAffectOtherUsers() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        runtime.save(state(10, true));
        runtime.disable(0);
        assertTrue(storage.exists(0, REVOKED));
        assertFalse(storage.exists(0, CONFIG));
        assertFalse(storage.exists(0, LAST_GOOD));
        assertNull(runtime.load(0, SCHEMA));
        assertTrue(runtime.load(10, SCHEMA).autonomousEnabled);
    }

    @Test public void failedSnapshotDeletionCannotResurrectEnabledConfiguration() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        storage.failDelete = CONFIG;
        try { runtime.disable(0); fail("expected deletion failure"); } catch (IOException expected) { }
        assertTrue(storage.exists(0, CONFIG));
        assertTrue(storage.exists(0, REVOKED));
        assertNull(new SystemRuntimeConfigPersistence(storage).load(0, SCHEMA));
    }

    @Test public void anyRevocationMarkerIncludingTornOrEmptyBlocksBothEnabledCopies() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        storage.write(0, REVOKED, new byte[0]);
        assertNull(runtime.load(0, SCHEMA));
    }

    @Test public void reEnableClearsRevocationOnlyAfterBothSnapshotsAreCommitted() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.disable(0);
        storage.failWrite = LAST_GOOD;
        try { runtime.save(state(0, true)); fail("expected disk failure"); } catch (IOException expected) { }
        assertTrue(storage.exists(0, REVOKED));
        assertNull(runtime.load(0, SCHEMA));
        storage.failWrite = null;
        runtime.save(state(0, true));
        assertFalse(storage.exists(0, REVOKED));
        assertTrue(runtime.load(0, SCHEMA).autonomousEnabled);
    }

    @Test public void savingExplicitlyDisabledStateUsesRevocationInsteadOfPersistingRules() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        runtime.save(state(0, true));
        runtime.save(state(0, false));
        assertTrue(storage.exists(0, REVOKED));
        assertFalse(storage.exists(0, CONFIG));
        assertFalse(storage.exists(0, LAST_GOOD));
        assertNull(runtime.load(0, SCHEMA));
    }

    @Test public void disabledPrimaryNeverFallsBackToEnabledLastGood() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        storage.write(0, LAST_GOOD, SystemRuntimeConfig.encode(state(0, true)));
        storage.write(0, CONFIG, SystemRuntimeConfig.encode(state(0, false)));
        assertNull(runtime.load(0, SCHEMA));
    }

    @Test public void rejectedUserIdsNeverReachStorage() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        SystemRuntimeConfigPersistence runtime = new SystemRuntimeConfigPersistence(storage);
        try { runtime.load(-1, SCHEMA); fail("accepted invalid user"); } catch (IOException expected) { }
        try { runtime.disable(1000); fail("accepted invalid user"); } catch (IOException expected) { }
        assertEquals(0, storage.calls);
    }

    private static SystemRuntimeConfig.State state(int userId, boolean enabled) throws IOException {
        return new SystemRuntimeConfig.State(SCHEMA, userId, 12345, enabled,
                true, true, true, false, true, true, true, true, RULES, null);
    }

    private static final class MemoryStorage implements SystemRuntimeConfigPersistence.Storage {
        private final Map<String, byte[]> files = new HashMap<>();
        SystemRuntimeConfigPersistence.Slot failWrite;
        SystemRuntimeConfigPersistence.Slot failDelete;
        int calls;

        @Override public boolean exists(int userId, SystemRuntimeConfigPersistence.Slot slot) {
            calls++;
            return files.containsKey(key(userId, slot));
        }
        @Override public byte[] read(int userId, SystemRuntimeConfigPersistence.Slot slot) {
            calls++;
            byte[] value = files.get(key(userId, slot));
            return value == null ? null : value.clone();
        }
        @Override public void write(int userId, SystemRuntimeConfigPersistence.Slot slot, byte[] bytes) throws IOException {
            calls++;
            if (slot == failWrite) throw new IOException("injected-write-failure");
            files.put(key(userId, slot), bytes.clone());
        }
        @Override public void delete(int userId, SystemRuntimeConfigPersistence.Slot slot) throws IOException {
            calls++;
            if (slot == failDelete) throw new IOException("injected-delete-failure");
            files.remove(key(userId, slot));
        }
        private String key(int userId, SystemRuntimeConfigPersistence.Slot slot) { return userId + "/" + slot.name(); }
    }
}
