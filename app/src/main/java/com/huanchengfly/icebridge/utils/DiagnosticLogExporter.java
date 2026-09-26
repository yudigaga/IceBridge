package com.huanchengfly.icebridge.utils;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.huanchengfly.icebridge.BuildConfig;
import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics;
import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository;
import com.huanchengfly.icebridge.databases.AppConfig;

import org.litepal.LitePal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiagnosticLogExporter {
    private static final int MAX_LOG_LINES = 8000;
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider";
    private static final String LSPOSED_LOG_COMMAND = "for f in /data/adb/lspd/log/verbose_*.log /data/adb/lspd/log/modules_*.log; do [ -f \"$f\" ] && echo ===$f=== && tail -n 4000 \"$f\"; done";
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final long ROOT_TIMEOUT_MILLIS = 12_000L;
    private static final AtomicBoolean EXPORTING = new AtomicBoolean();

    private DiagnosticLogExporter() {
    }

    public static void export(Context context) {
        if (!EXPORTING.compareAndSet(false, true)) {
            Toast.makeText(context, R.string.toast_export_diagnostic_logs_running, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, R.string.toast_export_diagnostic_logs_starting, Toast.LENGTH_SHORT).show();
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> createExport(appContext));
    }

    private static void createExport(Context context) {
        File output = null;
        try {
            File directory = new File(context.getCacheDir(), "diagnostic-logs");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("cannot create cache directory");
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            output = new File(directory, "icebridge-diagnostic-" + timestamp + ".txt");
            writeBaseLog(output, context);
            appendLsposedLogs(context, output);
        } catch (Throwable error) {
            Log.e("IceBridge", "diagnostic export failed", error);
            if (output != null && output.exists()) {
                appendRootStatus(output, "export-" + error.getClass().getSimpleName());
                finish(context, output);
            } else {
                finish(context, null);
            }
        }
    }

    private static void writeBaseLog(File output, Context context) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            append(stream, "Ice Bridge diagnostic log\n");
            append(stream, "exportedAt=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
            append(stream, "appVersion=" + version(context) + "\n");
            append(stream, "sdk=" + Build.VERSION.SDK_INT + "\n");
            append(stream, "release=" + Build.VERSION.RELEASE + "\n");
            append(stream, "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            append(stream, "build=" + Build.DISPLAY + "\n");
            append(stream, "workMode=" + (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("work_mode", 0) == 1 ? "root" : "icebox") + "\n");
            append(stream, "automaticLinkBridgeEnabled=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("xhs_resolve_recovery", false) + "\n");
            append(stream, "automaticOpenLinksEnabled=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("auto_open_links", false) + "\n");
            append(stream, "systemServerConfig=" + describeSystemServerConfig() + "\n");
            append(stream, "nativeLinkCapture=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("native_link_capture", false) + "\n");
            append(stream, "paymentSdkBridge=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("payment_sdk_bridge", true) + "\n");
            append(stream, "onlyFrozenLinkTargets=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("only_frozen_link_targets", true) + "\n");
            append(stream, "browserFallback=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("link_browser_fallback", true) + "\n");
            append(stream, "clipboardAutoOpen=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("clipboard_auto_open", false) + "\n");
            append(stream, "onlineRules=" + OnlineLinkRuleRepository.diagnosticSummary(context) + "\n");
            append(stream, "debugLogging=" + context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("module_debug_logging", false) + "\n");
            append(stream, "\n--- URL automation events (URLs redacted) ---\n");
            String automationEvents = LinkAutomationDiagnostics.dump(context);
            append(stream, automationEvents.isEmpty() ? "[no events]\n" : automationEvents + "\n");
            append(stream, "\n");
        }
    }

    private static void appendLsposedLogs(Context context, File output) {
        StringBuilder logs = new StringBuilder();
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", LSPOSED_LOG_COMMAND)
                    .redirectErrorStream(true)
                    .start();
            Process runningProcess = process;
            Future<?> timeout = TIMEOUT_EXECUTOR.schedule(() -> {
                if (runningProcess.isAlive()) {
                    runningProcess.destroyForcibly();
                }
            }, ROOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                int bytes = 0;
                while (lines < MAX_LOG_LINES && (line = reader.readLine()) != null) {
                    if (!isRelevantLsposedLine(line)) {
                        continue;
                    }
                    byte[] encoded = (sanitize(line) + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes + encoded.length > MAX_OUTPUT_BYTES) {
                        break;
                    }
                    logs.append(sanitize(line)).append('\n');
                    bytes += encoded.length;
                    lines++;
                }
            }
            int exitCode = process.waitFor();
            timeout.cancel(false);
            if (exitCode == 0) {
                appendRootLogs(output, logs);
            } else {
                appendRootStatus(output, exitCode == 137 ? "root-timeout" : "root-command-exit-" + exitCode);
            }
        } catch (Throwable error) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            appendRootStatus(output, "root-command-" + error.getClass().getSimpleName());
        }
        finish(context, output);
    }

    private static void appendRootLogs(File output, StringBuilder logs) {
        try (FileOutputStream stream = new FileOutputStream(output, true)) {
            append(stream, "\n--- LSPosed Ice Bridge logs (root) ---\n");
            if (logs.length() == 0) {
                append(stream, "[no matching LSPosed Ice Bridge log lines found]\n");
            } else {
                append(stream, logs.toString());
            }
        } catch (Exception ignored) {
        }
    }

    private static void appendRootStatus(File output, String status) {
        try (FileOutputStream stream = new FileOutputStream(output, true)) {
            append(stream, "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] root=" + status + "\n");
        } catch (Exception ignored) {
        }
    }

    private static void finish(Context context, File output) {
        MAIN_HANDLER.post(() -> {
            EXPORTING.set(false);
            if (output == null) {
                Log.e("IceBridge", "diagnostic export failed: no output file");
                Toast.makeText(context, context.getString(R.string.toast_export_diagnostic_logs_failed), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(context, AUTHORITY, output);
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newRawUri("Ice Bridge diagnostic log", uri));
                Log.i("IceBridge", "diagnostic export share: " + uri);
                Intent chooser = Intent.createChooser(share, context.getString(R.string.title_export_diagnostic_logs));
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
            } catch (Throwable error) {
                Log.e("IceBridge", "diagnostic export share failed: " + error.getClass().getSimpleName(), error);
                Toast.makeText(context, context.getString(R.string.toast_export_diagnostic_logs_failed), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void appendLogs(FileOutputStream stream, String[] command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            int bytes = 0;
            while (lines < MAX_LOG_LINES && (line = reader.readLine()) != null) {
                if (!isRelevant(line)) {
                    continue;
                }
                byte[] encoded = (sanitize(line) + "\n").getBytes(StandardCharsets.UTF_8);
                if (bytes + encoded.length > MAX_OUTPUT_BYTES) {
                    append(stream, "[log output truncated]\n");
                    break;
                }
                stream.write(encoded);
                bytes += encoded.length;
                lines++;
            }
        } finally {
            process.destroy();
        }
    }

    private static boolean isRelevant(String line) {
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("icebridge")
                || lower.contains("configprovider")
                || lower.contains("resolve recovery")
                || lower.contains("activitystarterimpl")
                || lower.contains("activitytaskmanager")
                || lower.contains("activitymanager")
                || lower.contains("packagemanager")
                || lower.contains("start_class_not_found")
                || lower.contains("ainfo is null");
    }

    private static boolean isRelevantLsposedLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        return line.startsWith("===/data/adb/lspd/log/")
                || lower.contains("com.huanchengfly.icebridge")
                || lower.contains("io.github.yudigaga.icebridge")
                || lower.contains("icebridge")
                || lower.contains("resolve recovery")
                || lower.contains("package-state capability");
    }

    private static String sanitize(String line) {
        String result = line.replaceAll("(?i)(https?://)[^\\s]+", "$1[redacted]");
        return result.length() <= MAX_LINE_LENGTH ? result : result.substring(0, MAX_LINE_LENGTH) + " [truncated]";
    }

    private static String describeSystemServerConfig() {
        try {
            return "bridge=" + configValue("xhs_resolve_recovery")
                    + ",autoOpen=" + configValue("auto_open_links")
                    + ",nativeCapture=" + configValue("native_link_capture")
                    + ",payment=" + configValue("payment_sdk_bridge");
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static String configValue(String key) {
        AppConfig config = LitePal.where("key = ?", key).findFirst(AppConfig.class);
        return config == null ? "unset" : config.getValue();
    }

    private static void append(FileOutputStream stream, String value) throws Exception {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}

