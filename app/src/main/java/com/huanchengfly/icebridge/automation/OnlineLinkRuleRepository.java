package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;

import com.huanchengfly.icebridge.providers.ConfigProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads the owner's versioned declarative rule bundle. The former third-party host cache
 * is only a migration fallback until the first owned-source snapshot has been committed.
 */
public final class OnlineLinkRuleRepository {
    public interface Callback {
        void onComplete(Result result);
    }

    public static final String KEY_ENABLED = "online_link_rule_updates";
    private static final String PREFS = LinkAutomationConfig.PREFS;
    private static final String KEY_RULES = "online_link_rule_hosts";
    private static final String KEY_RULES_BACKUP = "online_link_rule_hosts_backup";
    private static final String KEY_RULES_CHECKSUM = "online_link_rule_hosts_sha256";
    private static final String KEY_RULES_BACKUP_CHECKSUM = "online_link_rule_hosts_backup_sha256";
    private static final String KEY_UPDATED_AT = "online_link_rule_updated_at";
    private static final String KEY_LAST_STATUS = "online_link_rule_last_status";
    private static final String KEY_LAST_SOURCE = "online_link_rule_last_source";
    private static final String KEY_OWNED_RULES = "online_owned_rules_v1";
    private static final String KEY_OWNED_HASH = "online_owned_rules_sha256";
    private static final String KEY_OWNED_BACKUP = "online_owned_rules_backup_v1";
    private static final String KEY_OWNED_BACKUP_HASH = "online_owned_rules_backup_sha256";
    private static final String KEY_HIGHEST_REVISION = "online_owned_rules_highest_revision";
    private static final String KEY_HIGHEST_HASH = "online_owned_rules_highest_sha256";
    private static final String KEY_MIGRATED = "online_owned_rules_migrated";
    private static final String KEY_FAILURE_COUNT = "online_rule_failure_count";
    private static final String KEY_RETRY_AT = "online_rule_retry_at";
    private static final long UPDATE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final long REFRESH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_HOSTS = 96;
    private static final String SOURCE_NAME = "自有规则 yudigaga/IceBridge";
    private static final HttpUrl MANIFEST_URL = HttpUrl.parse(
            "https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json");
    private static final HttpUrl FALLBACK_MANIFEST_URL = HttpUrl.parse(
            "https://cdn.jsdelivr.net/gh/yudigaga/IceBridge@rules/manifest.json");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> new Thread(r, "IceBridge-online-rules"));
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean();
    /** Callbacks joining an existing refresh receive its real result instead of a false
     * "already in progress" failure. This prevents the settings UI from losing the only
     * completion callback when startup refresh and a manual refresh overlap. */
    private static final List<Callback> WAITING_CALLBACKS = new ArrayList<>();
    private static final Object CALLBACK_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    private static volatile OwnedCacheSnapshot lastOwnedCache;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private OnlineLinkRuleRepository() {
    }

    public static void maybeRefreshAsync(Context context) {
        Context app = app(context);
        if (app == null || !isEnabled(app)) return;
        long now = System.currentTimeMillis();
        SharedPreferences preferences = prefs(app);
        if (!shouldRefresh(now, preferences.getLong(KEY_UPDATED_AT, 0L),
                preferences.getLong(KEY_RETRY_AT, 0L), readOwnedRules(preferences))) {
            return;
        }
        refreshAsync(app, null);
    }

    public static void refreshAsync(Context context, Callback callback) {
        Context app = app(context);
        if (app == null) {
            dispatch(callback, Result.failure("\u5e94\u7528\u4e0a\u4e0b\u6587\u4e0d\u53ef\u7528"));
            return;
        }
        synchronized (CALLBACK_LOCK) {
            if (REFRESH_IN_FLIGHT.get()) {
                if (callback != null) WAITING_CALLBACKS.add(callback);
                return;
            }
            REFRESH_IN_FLIGHT.set(true);
        }
        try {
            EXECUTOR.execute(() -> {
                Result result;
                try {
                    try {
                        OnlineRuleBundle downloaded = downloadRules(prefs(app));
                        long updatedAt = System.currentTimeMillis();
                        result = Result.success(downloaded.rules.size(), updatedAt,
                                SOURCE_NAME + " · revision " + downloaded.revision);
                        saveOwnedRules(app, downloaded, result);
                    } catch (IOException error) {
                        result = Result.failure("自有在线规则下载或校验失败；继续使用可用缓存或手动规则");
                    } catch (Throwable error) {
                        result = Result.failure("\u89c4\u5219\u6821\u9a8c\u5931\u8d25\uff1b\u5df2\u7ee7\u7eed\u4f7f\u7528\u7f13\u5b58\u6216\u624b\u52a8\u89c4\u5219");
                    }
                    try {
                        if (!result.success) {
                            SharedPreferences preferences = prefs(app);
                            int failures = Math.min(6, Math.max(0, preferences.getInt(KEY_FAILURE_COUNT, 0)) + 1);
                            preferences.edit().putString(KEY_LAST_STATUS, result.message)
                                    .putInt(KEY_FAILURE_COUNT, failures)
                                    .putLong(KEY_RETRY_AT, System.currentTimeMillis() + retryDelayMillis(failures))
                                    .apply();
                        }
                    } catch (Throwable error) {
                        result = Result.failure("\u89c4\u5219\u72b6\u6001\u4fdd\u5b58\u5931\u8d25");
                    }
                } catch (Throwable error) {
                    // Keep the refresh slot recoverable even if an unexpected runtime failure
                    // escapes the normal download/validation/status-save handling.
                    result = Result.failure("\u89c4\u5219\u66f4\u65b0\u5f02\u5e38");
                }
                List<Callback> joined = releaseRefreshAndTakeWaitingCallbacks();
                dispatch(callback, result);
                for (Callback joinedCallback : joined) {
                    dispatch(joinedCallback, result);
                }
            });
        } catch (RejectedExecutionException error) {
            Result failure = Result.failure("\u89c4\u5219\u66f4\u65b0\u4efb\u52a1\u65e0\u6cd5\u6392\u961f");
            List<Callback> joined = releaseRefreshAndTakeWaitingCallbacks();
            dispatch(callback, failure);
            for (Callback joinedCallback : joined) dispatch(joinedCallback, failure);
        }
    }

    /**
     * Releases the refresh slot and drains joined callbacks as one locked state transition.
     * Keeping the transition atomic prevents a callback arriving between queue draining and
     * REFRESH_IN_FLIGHT=false from being stranded forever.
     */
    private static List<Callback> releaseRefreshAndTakeWaitingCallbacks() {
        synchronized (CALLBACK_LOCK) {
            List<Callback> joined = new ArrayList<>(WAITING_CALLBACKS);
            WAITING_CALLBACKS.clear();
            REFRESH_IN_FLIGHT.set(false);
            return joined;
        }
    }

    /** Ordered online candidates that have passed strict declarative parsing. */
    public static Map<String, List<String>> hostCandidates(Context context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (LinkRuleEntry entry : ruleEntries(context)) {
            List<String> packages = result.get(entry.host);
            if (packages == null) { packages = new ArrayList<>(); result.put(entry.host, packages); }
            if (!packages.contains(entry.targetPackage)) packages.add(entry.targetPackage);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : result.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    /** Full online entries; source conditions and reviewed conversion flags are never projected away. */
    public static List<LinkRuleEntry> ruleEntries(Context context) {
        Context app = app(context);
        if (app == null) return Collections.emptyList();
        synchronized (CACHE_LOCK) {
            SharedPreferences preferences = prefs(app);
            OnlineRuleBundle.Cache owned = readOwnedRules(preferences);
            if (owned.bundle != null) return owned.bundle.rules;
            if (!owned.allowLegacy) return Collections.emptyList();
            List<LinkRuleEntry> entries = new ArrayList<>();
            for (Map.Entry<String, List<String>> item : readStoredRules(preferences).rules.entrySet()) {
                for (String target : item.getValue()) entries.add(new LinkRuleEntry(item.getKey(), target,
                        LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_NONE, "旧版在线缓存"));
            }
            return Collections.unmodifiableList(entries);
        }
    }

    /** First-candidate projection retained for legacy callers. */
    public static Map<String, String> hostMappings(Context context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : hostCandidates(context).entrySet()) {
            if (!entry.getValue().isEmpty()) result.put(entry.getKey(), entry.getValue().get(0));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Compact host=pkg1,pkg2 entries intended for the trusted system-server snapshot only. */
    public static String[] systemServerEntries(Context context) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hostCandidates(context).entrySet()) {
            if (!entry.getValue().isEmpty()) entries.add(entry.getKey() + "=" + TextUtils.join(",", entry.getValue()));
        }
        return entries.toArray(new String[0]);
    }

    /** Parses the compact host=package form received by legacy system_server snapshots. */
    public static Map<String, String> parseSystemServerEntries(String[] entries) {
        Map<String, List<String>> candidates = parseSystemServerCandidateEntries(entries);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
            if (!entry.getValue().isEmpty()) result.put(entry.getKey(), entry.getValue().get(0));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Parses host=pkg1,pkg2 entries. Old host=pkg entries remain fully compatible. */
    public static Map<String, List<String>> parseSystemServerCandidateEntries(String[] entries) {
        if (entries == null || entries.length == 0) return Collections.emptyMap();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null) continue;
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator >= entry.length() - 1) continue;
            String host = normalizeHost(entry.substring(0, separator));
            if (host == null) continue;
            List<String> values = new ArrayList<>();
            for (String candidate : entry.substring(separator + 1).split(",")) {
                String packageName = candidate.trim();
                if (PACKAGE_PATTERN.matcher(packageName).matches() && !values.contains(packageName)) values.add(packageName);
            }
            if (!values.isEmpty()) {
                result.put(host, Collections.unmodifiableList(values));
                if (result.size() >= MAX_HOSTS) break;
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        // Do not start an implicit refresh here. The settings screen enables the feature and
        // immediately starts an explicit refresh with its own callback; starting both requests
        // races on REFRESH_IN_FLIGHT and can leave the UI waiting on the wrong request. Startup
        // refreshes are still handled by maybeRefreshAsync() from MyApplication.
    }

    public static long lastUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATED_AT, 0L);
    }

    public static String statusSummary(Context context) {
        Context app = app(context);
        if (app == null) return "应用上下文不可用";
        OnlineRuleBundle.Cache owned = readOwnedRules(prefs(app));
        if (owned.bundle != null) {
            return SOURCE_NAME + " · revision " + owned.bundle.revision + " · "
                    + owned.bundle.rules.size() + " 条" + (owned.usingBackup ? "（使用已验证备份）" : "");
        }
        if (!owned.allowLegacy) return "自有规则缓存不可用；仅使用手动规则";
        int count = ruleEntries(app).size();
        return count == 0 ? "尚未下载自有规则；仅使用手动规则" : "旧版在线缓存 " + count + " 条；等待迁移自有源";
    }

    /** Single-line state intended for privacy-safe diagnostic exports. */
    public static String diagnosticSummary(Context context) {
        Context app = app(context);
        if (app == null) return "context-unavailable";
        SharedPreferences preferences = prefs(app);
        OnlineRuleBundle.Cache owned = readOwnedRules(preferences);
        StoredRules legacy = owned.allowLegacy ? readStoredRules(preferences) : StoredRules.invalid();
        return "enabled=" + preferences.getBoolean(KEY_ENABLED, false)
                + ", cachedRules=" + ruleEntries(app).size()
                + ", cacheValid=" + (owned.bundle != null || legacy.valid)
                + ", usingBackup=" + (owned.bundle != null ? owned.usingBackup : legacy.usingBackup)
                + ", revision=" + (owned.bundle == null ? 0 : owned.bundle.revision)
                + ", highestRevision=" + preferences.getLong(KEY_HIGHEST_REVISION, 0L)
                + ", legacy=" + owned.allowLegacy
                + ", updatedAt=" + preferences.getLong(KEY_UPDATED_AT, 0L)
                + ", retryAt=" + preferences.getLong(KEY_RETRY_AT, 0L)
                + ", source=" + SOURCE_NAME
                + ", status=" + preferences.getString(KEY_LAST_STATUS, "尚未检查");
    }

    public static String lastStatus(Context context) {
        return prefs(context).getString(KEY_LAST_STATUS, "尚未检查");
    }

    private static OnlineRuleBundle downloadRules(SharedPreferences preferences) throws IOException {
        long deadline = System.nanoTime() + REFRESH_TIMEOUT_NANOS;
        try {
            // Leave a bounded part of the same task deadline for the CDN on restricted networks.
            return downloadFrom(preferences, MANIFEST_URL,
                    Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(18)));
        } catch (IOException firstFailure) {
            return downloadFrom(preferences, FALLBACK_MANIFEST_URL, deadline);
        }
    }

    private static OnlineRuleBundle downloadFrom(SharedPreferences preferences, HttpUrl manifestUrl,
                                                long deadline) throws IOException {
        OnlineRuleBundle.Manifest manifest = OnlineRuleBundle.parseManifest(
                fetch(manifestUrl, OnlineRuleBundle.MAX_MANIFEST_BYTES, deadline));
        OnlineRuleBundle.checkRevision(manifest, preferences.getLong(KEY_HIGHEST_REVISION, 0L),
                preferences.getString(KEY_HIGHEST_HASH, ""));
        OnlineRuleBundle.Cache local = readOwnedRules(preferences);
        if (OnlineRuleBundle.canReuse(manifest, local)) return local.bundle;
        OnlineRuleBundle bundle = OnlineRuleBundle.parse(fetch(bundleUrl(manifestUrl, manifest.file),
                OnlineRuleBundle.MAX_BUNDLE_BYTES, deadline), manifest.checksum);
        if (bundle.revision != manifest.revision) throw new IOException("manifest-bundle-revision-mismatch");
        if (System.nanoTime() >= deadline) throw new IOException("refresh-deadline");
        return bundle;
    }

    /** A validated relative path stays within the manifest directory and HTTPS origin. */
    static HttpUrl bundleUrl(HttpUrl manifestUrl, String path) throws IOException {
        if (manifestUrl == null || !"https".equals(manifestUrl.scheme()) || path == null
                || path.startsWith("/") || path.contains("..") || path.contains("\\")
                || path.contains(":") || path.contains("%") || path.contains("?") || path.contains("#")) {
            throw new IOException("bundle-url-invalid");
        }
        HttpUrl base = manifestUrl.resolve(".");
        HttpUrl result = base == null ? null : base.resolve(path);
        if (result == null || !result.scheme().equals(manifestUrl.scheme())
                || !result.host().equals(manifestUrl.host()) || result.port() != manifestUrl.port()
                || !result.encodedPath().startsWith(base.encodedPath())) throw new IOException("bundle-origin");
        return result;
    }

    private static String fetch(HttpUrl url, int maxBytes, long deadline) throws IOException {
        if (url == null || maxBytes <= 0) throw new IOException("request-invalid");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("refresh-deadline");
        Request request = new Request.Builder().url(url).header("User-Agent", "IceBridge-online-rules/1")
                .header("Cache-Control", "no-cache").build();
        Call call = CLIENT.newCall(request);
        // Both requests consume one deadline, including DNS, connect, and response-body reads.
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("http-" + response.code());
            if (response.body().contentLength() > maxBytes) throw new IOException("response-too-large");
            try (InputStream input = response.body().byteStream()) {
                return readUtf8Bounded(input, maxBytes);
            }
        }
    }

    static long retryDelayMillis(int failures) {
        return Math.min(TimeUnit.HOURS.toMillis(6),
                TimeUnit.MINUTES.toMillis(15) * (1L << Math.min(5, Math.max(0, failures - 1))));
    }

    static boolean shouldRefresh(long now, long lastUpdated, long retryAt, OnlineRuleBundle.Cache cache) {
        if (now < retryAt) return false;
        // A third-party timestamp must not postpone migration, and a recovered backup should
        // attempt to repair the active snapshot without waiting another seven days.
        return cache.bundle == null || cache.usingBackup || now - lastUpdated >= UPDATE_INTERVAL_MILLIS;
    }

    private static OnlineRuleBundle.Cache readOwnedRules(SharedPreferences preferences) {
        synchronized (CACHE_LOCK) {
            // getAll captures one atomic preference view; individual getters could straddle a commit.
            Map<String, ?> values = preferences.getAll();
            OwnedCacheSnapshot previous = lastOwnedCache;
            if (previous != null && previous.matches(preferences, values)) return previous.cache;
            OwnedCacheSnapshot next = new OwnedCacheSnapshot(preferences, values);
            lastOwnedCache = next;
            return next.cache;
        }
    }

    private static void saveOwnedRules(Context context, OnlineRuleBundle bundle, Result result) {
        commitOwnedRules(prefs(context), bundle, result);
        // The durable snapshot is already available when the system process observes this signal.
        try { context.getContentResolver().notifyChange(ConfigProvider.URI, null); }
        catch (RuntimeException ignored) { /* Periodic configuration refresh remains a fallback. */ }
    }

    static void commitOwnedRules(SharedPreferences preferences, OnlineRuleBundle bundle, Result result) {
        synchronized (CACHE_LOCK) {
            Map<String, ?> beforeCommit = preferences.getAll();
            OnlineRuleBundle.Cache previous = readOwnedRules(preferences);
            OnlineRuleBundle backup = previous.bundle == null ? bundle : previous.bundle;
            // Active snapshot, validated backup, anti-rollback state and status form one disk commit.
            // Replacing the whole bundle deliberately permits removal and an explicit empty rule set.
            boolean saved = preferences.edit().putString(KEY_OWNED_RULES, bundle.raw)
                    .putString(KEY_OWNED_HASH, bundle.checksum)
                    .putString(KEY_OWNED_BACKUP, backup.raw).putString(KEY_OWNED_BACKUP_HASH, backup.checksum)
                    .putLong(KEY_HIGHEST_REVISION, bundle.revision).putString(KEY_HIGHEST_HASH, bundle.checksum)
                    .putBoolean(KEY_MIGRATED, true).putLong(KEY_UPDATED_AT, result.updatedAt)
                    .putString(KEY_LAST_STATUS, result.message).putString(KEY_LAST_SOURCE, result.sourceName)
                    .putInt(KEY_FAILURE_COUNT, 0).putLong(KEY_RETRY_AT, 0L)
                    .remove(KEY_RULES).remove(KEY_RULES_CHECKSUM).remove(KEY_RULES_BACKUP).remove(KEY_RULES_BACKUP_CHECKSUM)
                    .commit();
            if (!saved) {
                // SharedPreferences updates its in-memory map even when writing to disk fails.
                // Restore only this transaction's keys so a failed update cannot become active.
                SharedPreferences.Editor rollback = preferences.edit();
                String[] keys = {KEY_OWNED_RULES, KEY_OWNED_HASH, KEY_OWNED_BACKUP, KEY_OWNED_BACKUP_HASH,
                        KEY_HIGHEST_REVISION, KEY_HIGHEST_HASH, KEY_MIGRATED, KEY_UPDATED_AT,
                        KEY_LAST_STATUS, KEY_LAST_SOURCE, KEY_FAILURE_COUNT, KEY_RETRY_AT,
                        KEY_RULES, KEY_RULES_CHECKSUM, KEY_RULES_BACKUP, KEY_RULES_BACKUP_CHECKSUM};
                for (String key : keys) {
                    Object previousValue = beforeCommit.get(key);
                    if (previousValue instanceof String) rollback.putString(key, (String) previousValue);
                    else if (previousValue instanceof Long) rollback.putLong(key, (Long) previousValue);
                    else if (previousValue instanceof Integer) rollback.putInt(key, (Integer) previousValue);
                    else if (previousValue instanceof Boolean) rollback.putBoolean(key, (Boolean) previousValue);
                    else if (previousValue instanceof Float) rollback.putFloat(key, (Float) previousValue);
                    else rollback.remove(key);
                }
                rollback.commit();
                throw new IllegalStateException("rule-cache-commit-failed");
            }
        }
    }

    /** Reads a response without allowing a missing or forged Content-Length to cause unbounded allocation. */
    static String readUtf8Bounded(InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IOException("response-body-invalid");
        if (maxBytes <= 0) throw new IOException("response-limit-invalid");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) throw new IOException("response-too-large");
            output.write(buffer, 0, read);
            total += read;
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    /** Accept only exact host patterns such as xhslink\\.com; reject all general-purpose regex. */
    private static String normalizeHost(String raw) {
        if (raw == null || raw.length() > 128) return null;
        String host = raw.trim().toLowerCase(Locale.ROOT).replace("\\.", ".");
        if (host.indexOf('\\') >= 0 || host.indexOf('/') >= 0 || host.indexOf(':') >= 0
                || host.indexOf('*') >= 0 || host.indexOf('[') >= 0 || host.indexOf('(') >= 0
                || host.indexOf('?') >= 0 || host.indexOf('+') >= 0 || host.indexOf('|') >= 0
                || host.indexOf('^') >= 0 || host.indexOf('$') >= 0) return null;
        return HOST_PATTERN.matcher(host).matches() ? host : null;
    }

    private static StoredRules readStoredRules(SharedPreferences preferences) {
        if (preferences == null) return StoredRules.invalid();
        return selectStoredRules(
                preferences.getString(KEY_RULES, ""),
                preferences.getString(KEY_RULES_CHECKSUM, ""),
                preferences.getString(KEY_RULES_BACKUP, ""),
                preferences.getString(KEY_RULES_BACKUP_CHECKSUM, ""));
    }

    /** Selects the primary cache first and falls back to a validated backup snapshot. */
    static StoredRules selectStoredRules(String primary, String primaryChecksum,
                                         String backup, String backupChecksum) {
        Map<String, List<String>> primaryRules = parseStoredCandidates(primary);
        if (isValidStoredCache(primary, primaryChecksum, primaryRules)) {
            return new StoredRules(primaryRules, false, true, primaryChecksum != null && !primaryChecksum.isEmpty());
        }

        Map<String, List<String>> backupRules = parseStoredCandidates(backup);
        if (isValidStoredCache(backup, backupChecksum, backupRules)) {
            return new StoredRules(backupRules, true, true, backupChecksum != null && !backupChecksum.isEmpty());
        }
        return StoredRules.invalid();
    }

    static boolean isValidStoredCache(String raw, String checksum, Map<String, List<String>> parsed) {
        if (raw == null || raw.isEmpty() || parsed == null || parsed.isEmpty()) return false;
        // Keep compatibility with pre-checksum installations, but every newly written cache is
        // checksum protected and any present checksum must match exactly.
        return checksum == null || checksum.isEmpty() || checksumMatches(raw, checksum);
    }

    static boolean checksumMatches(String raw, String expected) {
        return expected != null && !expected.isEmpty() && expected.equalsIgnoreCase(sha256(raw));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("sha256-unavailable", error);
        }
    }

    /** Reads both the former host/package cache and the current host/packages cache. */
    static Map<String, List<String>> parseStoredCandidates(String raw) {
        if (raw == null || raw.length() > MAX_HOSTS * 320) return Collections.emptyMap();
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonArray()) return result;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String host = normalizeHost(string(item, "host"));
                if (host == null || (result.size() >= MAX_HOSTS && !result.containsKey(host))) continue;
                List<String> packages = result.get(host);
                if (packages == null) { packages = new ArrayList<>(); result.put(host, packages); }
                String legacy = string(item, "package");
                if (legacy != null && PACKAGE_PATTERN.matcher(legacy).matches() && !packages.contains(legacy)) packages.add(legacy);
                JsonElement values = item.get("packages");
                if (values != null && values.isJsonArray()) for (JsonElement value : values.getAsJsonArray()) {
                    if (!value.isJsonPrimitive()) continue;
                    String packageName = value.getAsString();
                    if (PACKAGE_PATTERN.matcher(packageName).matches() && !packages.contains(packageName)) packages.add(packageName);
                }
                if (packages.isEmpty()) result.remove(host);
            }
        } catch (RuntimeException ignored) { return Collections.emptyMap(); }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : result.entrySet()) immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        return Collections.unmodifiableMap(immutable);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context app(Context context) {
        return context == null ? null : context.getApplicationContext();
    }

    private static void dispatch(Callback callback, Result result) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(result));
    }

    static final class StoredRules {
        final Map<String, List<String>> rules;
        final boolean usingBackup;
        final boolean valid;
        final boolean checksumPresent;

        StoredRules(Map<String, List<String>> rules, boolean usingBackup, boolean valid,
                    boolean checksumPresent) {
            this.rules = rules;
            this.usingBackup = usingBackup;
            this.valid = valid;
            this.checksumPresent = checksumPresent;
        }

        static StoredRules invalid() {
            return new StoredRules(Collections.emptyMap(), false, false, false);
        }
    }

    private static final class OwnedCacheSnapshot {
        private static final String[] KEYS = {KEY_OWNED_RULES, KEY_OWNED_HASH, KEY_OWNED_BACKUP,
                KEY_OWNED_BACKUP_HASH, KEY_HIGHEST_REVISION, KEY_HIGHEST_HASH, KEY_MIGRATED};
        final SharedPreferences preferences;
        final Map<String, ?> values;
        final OnlineRuleBundle.Cache cache;

        OwnedCacheSnapshot(SharedPreferences preferences, Map<String, ?> values) {
            this.preferences = preferences;
            Map<String, Object> relevant = new LinkedHashMap<>();
            for (String key : KEYS) relevant.put(key, values.get(key));
            this.values = relevant;
            Object highest = values.get(KEY_HIGHEST_REVISION);
            Object migrated = values.get(KEY_MIGRATED);
            cache = OnlineRuleBundle.selectCache(text(values, KEY_OWNED_RULES), text(values, KEY_OWNED_HASH),
                    text(values, KEY_OWNED_BACKUP), text(values, KEY_OWNED_BACKUP_HASH),
                    highest == null ? 0 : (highest instanceof Long ? (Long) highest : -1),
                    text(values, KEY_HIGHEST_HASH), migrated != null && !Boolean.FALSE.equals(migrated));
        }

        boolean matches(SharedPreferences preferences, Map<String, ?> other) {
            if (this.preferences != preferences) return false;
            for (String key : KEYS) if (!Objects.equals(values.get(key), other.get(key))) return false;
            return true;
        }

        private static String text(Map<String, ?> values, String key) {
            Object value = values.get(key);
            return value == null ? "" : value instanceof String ? (String) value : "invalid-preference-type";
        }
    }

    public static final class Result {
        public final boolean success;
        public final int count;
        public final long updatedAt;
        public final String message;
        public final String sourceName;

        private Result(boolean success, int count, long updatedAt, String message, String sourceName) {
            this.success = success;
            this.count = count;
            this.updatedAt = updatedAt;
            this.message = message;
            this.sourceName = sourceName == null ? "" : sourceName;
        }

        static Result success(int count, long updatedAt, String sourceName) {
            return new Result(true, count, updatedAt, "\u5df2\u4ece " + sourceName + " \u66f4\u65b0 " + count + " \u6761\u5728\u7ebf\u89c4\u5219", sourceName);
        }

        static Result failure(String message) {
            return new Result(false, 0, 0L, message, "");
        }
    }

}
