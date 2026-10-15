package com.huanchengfly.icebridge.automation;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * System-UID-only private persistence. Call from the configuration worker, never an Activity hook.
 * No application credential/data path is used, so hiding or disabling IceBridge does not revoke it.
 */
public final class SystemRuntimeConfigStore {
    private static final String DIRECTORY = "/data/system/icebridge";
    private final SystemRuntimeConfigPersistence persistence = new SystemRuntimeConfigPersistence(
            new SystemRuntimeConfigPersistence.Storage() {
                @Override public boolean exists(int userId, SystemRuntimeConfigPersistence.Slot slot) {
                    return existsAny(file(userId, slot));
                }

                @Override public byte[] read(int userId, SystemRuntimeConfigPersistence.Slot slot) throws IOException {
                    AtomicFile atomic = file(userId, slot);
                    if (!existsAny(atomic)) return null;
                    secureAtomicFiles(atomic);
                    try (FileInputStream input = atomic.openRead()) {
                        if (input.getChannel().size() > SystemRuntimeConfig.MAX_BYTES) throw new IOException("runtime-size");
                        return SystemRuntimeConfig.readBounded(input);
                    }
                }

                @Override public void write(int userId, SystemRuntimeConfigPersistence.Slot slot, byte[] bytes) throws IOException {
                    SystemRuntimeConfigStore.write(file(userId, slot), bytes);
                }

                @Override public void delete(int userId, SystemRuntimeConfigPersistence.Slot slot) throws IOException {
                    remove(file(userId, slot));
                }
            });

    /** Returns only an explicitly enabled snapshot belonging to this user and Provider schema. */
    public synchronized SystemRuntimeConfig.State load(int userId, int expectedProviderSchema) throws IOException {
        prepare(userId);
        return persistence.load(userId, expectedProviderSchema);
    }

    /** Save only trusted Provider data or a validated online-cache replacement derived from it. */
    public synchronized void save(SystemRuntimeConfig.State state) throws IOException {
        if (state == null) throw new IOException("runtime-null");
        prepare(state.userId);
        persistence.save(state);
    }

    /** Revocation is committed first so neither an AtomicFile backup nor last-good can revive it. */
    public synchronized void disable(int userId) throws IOException {
        prepare(userId);
        persistence.disable(userId);
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        secureAtomicFiles(file);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            secureAtomicFiles(file);
            syncDirectory();
        } catch (ErrnoException error) {
            if (output != null) file.failWrite(output);
            throw new IOException("runtime-file-permissions", error);
        } catch (IOException | RuntimeException error) {
            if (output != null) file.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("runtime-file-write", error);
        }
    }

    private static void remove(AtomicFile file) throws IOException {
        secureAtomicFiles(file);
        file.delete();
        // Android releases differ between .bak and .new. Explicitly remove either leftover.
        for (File candidate : files(file)) {
            if (candidate.exists() && !candidate.delete()) throw new IOException("runtime-file-delete");
        }
        if (existsAny(file)) throw new IOException("runtime-file-delete");
        syncDirectory();
    }

    private static void prepare(int userId) throws IOException {
        SystemRuntimeConfig.checkUserId(userId);
        if (Process.myUid() != Process.SYSTEM_UID) throw new IOException("runtime-requires-system-uid");
        File directory = new File(DIRECTORY);
        if (!DIRECTORY.equals(directory.getCanonicalPath())) throw new IOException("runtime-directory-link");
        try {
            if (!directory.exists()) {
                try { Os.mkdir(DIRECTORY, 0700); }
                catch (ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
            }
            StructStat stat = Os.lstat(DIRECTORY);
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.SYSTEM_UID) {
                throw new IOException("runtime-directory-owner");
            }
            Os.chmod(DIRECTORY, 0700);
        } catch (ErrnoException error) {
            throw new IOException("runtime-directory-unavailable", error);
        }
    }

    private static void secureAtomicFiles(AtomicFile file) throws IOException {
        for (File candidate : files(file)) {
            try {
                StructStat stat = Os.lstat(candidate.getPath());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.SYSTEM_UID) {
                    throw new IOException("runtime-file-owner");
                }
                if (!candidate.getPath().equals(candidate.getCanonicalPath())) throw new IOException("runtime-file-link");
                Os.chmod(candidate.getPath(), 0600);
            } catch (ErrnoException error) {
                if (error.errno != OsConstants.ENOENT) throw new IOException("runtime-file-unavailable", error);
            }
        }
    }

    private static boolean existsAny(AtomicFile file) {
        for (File candidate : files(file)) if (candidate.exists()) return true;
        return false;
    }

    private static File[] files(AtomicFile file) {
        File base = file.getBaseFile();
        return new File[]{base, new File(base.getPath() + ".bak"), new File(base.getPath() + ".new")};
    }

    private static void syncDirectory() throws IOException {
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(DIRECTORY, OsConstants.O_RDONLY, 0);
            StructStat stat = Os.fstat(descriptor);
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.SYSTEM_UID) {
                throw new IOException("runtime-directory-owner");
            }
            Os.fsync(descriptor);
        } catch (ErrnoException error) {
            throw new IOException("runtime-directory-sync", error);
        } finally {
            if (descriptor != null) {
                try { Os.close(descriptor); } catch (ErrnoException ignored) { }
            }
        }
    }

    private static AtomicFile file(int userId, SystemRuntimeConfigPersistence.Slot slot) {
        // All callers have validated userId; no path or suffix enters through public methods.
        String suffix;
        switch (slot) {
            case CONFIG: suffix = ".json"; break;
            case LAST_GOOD: suffix = ".last-good.json"; break;
            case REVOKED: suffix = ".disabled"; break;
            default: throw new AssertionError(slot);
        }
        return new AtomicFile(new File(DIRECTORY, "user-" + userId + suffix));
    }
}
