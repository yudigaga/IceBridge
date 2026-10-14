package com.huanchengfly.icebridge.engines;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.huanchengfly.icebridge.automation.FluidCloudRuleCatalog;

/**
 * Executes the small set of commands required by RootEngine without depending on RootTools.
 */
public final class RootCommandRunner {
    private static final long COMMAND_TIMEOUT_MILLIS = 10_000L;
    private static final int MAX_OUTPUT_LENGTH = 16 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public enum Status {
        UNKNOWN,
        NOT_AVAILABLE,
        NEED_PERMISSION,
        WORKING
    }

    public interface Callback {
        void onComplete(@NonNull Result result);
    }

    public static final class Result {
        private final boolean started;
        private final boolean timedOut;
        private final int exitCode;
        private final boolean root;
        private final String output;

        private Result(boolean started, boolean timedOut, int exitCode, boolean root, String output) {
            this.started = started;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.root = root;
            this.output = output;
        }

        public boolean isSuccessful() {
            return started && !timedOut && exitCode == 0;
        }

        public boolean isRoot() {
            return root;
        }
    }

    private static volatile Status status = Status.UNKNOWN;
    private static volatile Handler mainHandler;

    private RootCommandRunner() {
    }

    public static Status getStatus() {
        return status;
    }

    public static void refreshStatus(Callback callback) {
        executeCommand("id -u", result -> {
            if (!result.started) {
                status = Status.NOT_AVAILABLE;
            } else if (result.isSuccessful() && result.isRoot()) {
                status = Status.WORKING;
            } else {
                status = Status.NEED_PERMISSION;
            }
            if (callback != null) {
                callback.onComplete(result);
            }
        });
    }

    public static void setPackageEnabled(String packageName, boolean enabled, int userId, Callback callback) {
        String command = packageStateCommand(packageName, enabled, userId);
        if (command == null) {
            completeFailure(callback);
            return;
        }
        executeCommand(command, callback);
    }

    static String packageStateCommand(String packageName, boolean enabled, int userId) {
        if (!isValidPackageName(packageName) || !isValidUserId(userId)) return null;
        String target = " --user " + userId + " " + packageName;
        return enabled ? "pm enable" + target + " && pm unhide" + target : "pm disable-user" + target;
    }

    public static void startViewAsUser(Uri uri, String targetPackage, int userId, Callback callback) {
        startViewAsUser(uri, targetPackage, userId, false, callback);
    }

    /** Accepts original web URLs only; custom schemes can only be produced by our reviewed catalog. */
    public static void startViewAsUser(Uri originalHttpUri, String targetPackage, int userId,
                                       boolean fixedConversion, Callback callback) {
        String command = viewCommand(originalHttpUri == null ? null : originalHttpUri.toString(),
                targetPackage, userId, fixedConversion,
                (source, target) -> FluidCloudRuleCatalog.rewriteForTarget(Uri.parse(source), target).toString());
        if (command == null) {
            completeFailure(callback);
            return;
        }
        executeCommand(command, callback);
    }

    interface FixedLinkConverter { String convert(String source, String target); }

    static String viewCommand(String originalHttpUrl, String targetPackage, int userId,
                              boolean fixedConversion, FixedLinkConverter converter) {
        if (!isValidWebUrl(originalHttpUrl) || originalHttpUrl.length() > 8192
                || !isValidPackageName(targetPackage) || !isValidUserId(userId)) return null;
        String launchUrl = fixedConversion ? converter.convert(originalHttpUrl, targetPackage) : originalHttpUrl;
        if (launchUrl == null || launchUrl.isEmpty() || launchUrl.length() > 32768
                || launchUrl.indexOf('\0') >= 0 || launchUrl.indexOf('\r') >= 0 || launchUrl.indexOf('\n') >= 0) return null;
        return "am start --user " + userId + " -a android.intent.action.VIEW -d "
                + shellQuote(launchUrl) + " -p " + shellQuote(targetPackage);
    }

    private static void executeCommand(String command, Callback callback) {
        if (callback == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            Result result;
            try {
                result = executeBlocking(command);
            } catch (RuntimeException error) {
                // Some ROMs can reject process creation with a runtime exception. Treat it as
                // unavailable Root access instead of losing the callback on the executor thread.
                result = new Result(false, false, -1, false, "");
            }
            final Result completedResult = result;
            Handler handler = getMainHandler();
            if (handler != null) {
                handler.post(() -> callback.onComplete(completedResult));
            } else {
                callback.onComplete(completedResult);
            }
        });
    }

    private static Handler getMainHandler() {
        Handler current = mainHandler;
        if (current != null) {
            return current;
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) {
            return null;
        }
        Handler handler = new Handler(mainLooper);
        mainHandler = handler;
        return handler;
    }

    private static Result executeBlocking(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread outputReader = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            Process finalProcess = process;
            outputReader = new Thread(() -> readOutput(finalProcess, output), "IceBridge-root-output");
            outputReader.start();

            long deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MILLIS;
            while (true) {
                try {
                    int exitCode = process.exitValue();
                    joinOutputReader(outputReader);
                    String text = output.toString();
                    return new Result(true, false, exitCode, isRootOutput(text), text);
                } catch (IllegalThreadStateException ignored) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        destroyProcess(process);
                        joinOutputReader(outputReader);
                        return new Result(true, true, -1, false, output.toString());
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        destroyProcess(process);
                        joinOutputReader(outputReader);
                        return new Result(true, true, -1, false, output.toString());
                    }
                }
            }
        } catch (IOException | SecurityException error) {
            return new Result(false, false, -1, false, output.toString());
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private static void completeFailure(Callback callback) {
        if (callback != null) {
            Handler handler = getMainHandler();
            if (handler != null) {
                handler.post(() -> callback.onComplete(
                        new Result(false, false, -1, false, "")));
            } else {
                callback.onComplete(new Result(false, false, -1, false, ""));
            }
        }
    }

    static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    static boolean isValidUserId(int userId) {
        return userId >= 0 && userId <= 999;
    }

    static boolean isValidWebUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        return isValidWebUrl(uri.toString());
    }

    static boolean isValidWebUrl(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int schemeSeparator = value.indexOf("://");
        if (schemeSeparator <= 0) {
            return false;
        }
        String scheme = value.substring(0, schemeSeparator);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        int authorityStart = schemeSeparator + 3;
        int authorityEnd = value.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = value.indexOf('?', authorityStart);
        }
        if (authorityEnd < 0) {
            authorityEnd = value.indexOf('#', authorityStart);
        }
        if (authorityEnd < 0) {
            authorityEnd = value.length();
        }
        String authority = value.substring(authorityStart, authorityEnd);
        if (authority.isEmpty() || authority.startsWith(":")
                || authority.indexOf('@') >= 0 || authority.indexOf(' ') >= 0) {
            return false;
        }
        return value.indexOf('\'') < 0
                && value.indexOf('"') < 0
                && value.indexOf(';') < 0
                && value.indexOf('|') < 0
                && value.indexOf('&') < 0
                && value.indexOf('$') < 0
                && value.indexOf('`') < 0
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void destroyProcess(Process process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1 && output.length() < MAX_OUTPUT_LENGTH) {
                output.append(buffer, 0, Math.min(count, MAX_OUTPUT_LENGTH - output.length()));
            }
        } catch (IOException ignored) {
            // A process being destroyed closes the stream and is reported through its result.
        }
    }

    private static void joinOutputReader(Thread outputReader) {
        if (outputReader == null) {
            return;
        }
        try {
            outputReader.join(1_000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isRootOutput(String output) {
        if (output == null) {
            return false;
        }
        String normalized = output.trim();
        return "0".equals(normalized) || containsRootUid(normalized);
    }

    static boolean containsRootUid(String output) {
        return output != null && output.matches("(?s).*\\buid=0(?:\\D|$).*");
    }
}
