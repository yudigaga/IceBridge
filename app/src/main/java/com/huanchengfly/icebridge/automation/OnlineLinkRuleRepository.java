package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads only the declarative host-to-package subset of the Fluid Cloud repository.
 * It never interprets ShortX JavaScript/MVEL actions, Activities, intent flags, or any shell code.
 */
public final class OnlineLinkRuleRepository {
    public interface Callback {
        void onComplete(Result result);
    }

    public static final String KEY_ENABLED = "online_link_rule_updates";
    private static final String PREFS = LinkAutomationConfig.PREFS;
    private static final String KEY_RULES = "online_link_rule_hosts";
    private static final String KEY_UPDATED_AT = "online_link_rule_updated_at";
    private static final String KEY_LAST_STATUS = "online_link_rule_last_status";
    private static final String KEY_LAST_SOURCE = "online_link_rule_last_source";
    private static final long UPDATE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_RULE_FILES = 64;
    private static final int MAX_RULE_FILE_BYTES = 8 * 1024;
    private static final int MAX_HOSTS = 96;
    /** Official GitHub is preferred; jsDelivr is a fixed CDN fallback for networks that cannot reach GitHub directly. */
    private static final RuleSource[] SOURCES = {
            new RuleSource(
                    "GitHub",
                    "https://api.github.com/repos/nyehueh/shortx-Fluid_Cloud/git/trees/main?recursive=1",
                    "https://raw.githubusercontent.com/nyehueh/shortx-Fluid_Cloud/main/",
                    RuleSource.TreeFormat.GITHUB),
            new RuleSource(
                    "jsDelivr",
                    "https://data.jsdelivr.com/v1/package/gh/nyehueh/shortx-Fluid_Cloud@main",
                    "https://cdn.jsdelivr.net/gh/nyehueh/shortx-Fluid_Cloud@main/",
                    RuleSource.TreeFormat.JSDELIVR)
    };
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> new Thread(r, "IceBridge-online-rules"));
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build();

    private OnlineLinkRuleRepository() {
    }

    public static void maybeRefreshAsync(Context context) {
        Context app = app(context);
        if (app == null || !isEnabled(app) || System.currentTimeMillis() - lastUpdatedAt(app) < UPDATE_INTERVAL_MILLIS) {
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
        EXECUTOR.execute(() -> {
            Result result;
            try {
                DownloadResult downloaded = downloadRules();
                if (downloaded.rules.isEmpty()) {
                    result = Result.failure("\u672a\u83b7\u53d6\u5230\u53ef\u7528\u7684\u5b89\u5168\u89c4\u5219\uff1b\u5df2\u7ee7\u7eed\u4f7f\u7528\u7f13\u5b58\u6216\u5185\u7f6e\u89c4\u5219");
                } else {
                    int mergedCount = mergeAndSaveRules(app, downloaded.rules);
                    result = Result.success(mergedCount, System.currentTimeMillis(), downloaded.sourceName);
                }
            } catch (IOException error) {
                result = Result.failure("\u5728\u7ebf\u89c4\u5219\u66f4\u65b0\u5931\u8d25\uff1a\u65e0\u6cd5\u8fde\u63a5\u66f4\u65b0\u6e90\uff1b\u5df2\u7ee7\u7eed\u4f7f\u7528\u7f13\u5b58\u6216\u5185\u7f6e\u89c4\u5219");
            } catch (Throwable error) {
                result = Result.failure("\u89c4\u5219\u6821\u9a8c\u5931\u8d25\uff1b\u5df2\u7ee7\u7eed\u4f7f\u7528\u7f13\u5b58\u6216\u5185\u7f6e\u89c4\u5219");
            }
            SharedPreferences.Editor editor = prefs(app).edit().putString(KEY_LAST_STATUS, result.message);
            if (result.updatedAt > 0L) {
                editor.putLong(KEY_UPDATED_AT, result.updatedAt);
                editor.putString(KEY_LAST_SOURCE, result.sourceName);
            }
            editor.apply();
            dispatch(callback, result);
        });
    }

    /** Ordered online candidates that have passed strict declarative parsing. */
    public static Map<String, List<String>> hostCandidates(Context context) {
        Context app = app(context);
        if (app == null) return Collections.emptyMap();
        return parseStoredCandidates(prefs(app).getString(KEY_RULES, ""));
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
        if (enabled) maybeRefreshAsync(context);
    }

    public static long lastUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATED_AT, 0L);
    }

    public static String statusSummary(Context context) {
        int count = hostMappings(context).size();
        long updatedAt = lastUpdatedAt(context);
        if (updatedAt <= 0L) return count == 0 ? "\u5c1a\u672a\u4e0b\u8f7d\uff1b\u4ec5\u4f7f\u7528 APK \u5185\u7f6e\u89c4\u5219" : "\u5df2\u7f13\u5b58 " + count + " \u6761\u5728\u7ebf\u89c4\u5219";
        String source = prefs(context).getString(KEY_LAST_SOURCE, "");
        return "\u5df2\u7f13\u5b58 " + count + " \u6761\u5728\u7ebf\u89c4\u5219" + (source == null || source.isEmpty() ? "" : "\uff08" + source + "\uff09");
    }

    /** Single-line state intended for privacy-safe diagnostic exports. */
    public static String diagnosticSummary(Context context) {
        Context app = app(context);
        if (app == null) return "context-unavailable";
        SharedPreferences preferences = prefs(app);
        return "enabled=" + preferences.getBoolean(KEY_ENABLED, false)
                + ", cachedHosts=" + hostMappings(app).size()
                + ", updatedAt=" + preferences.getLong(KEY_UPDATED_AT, 0L)
                + ", source=" + preferences.getString(KEY_LAST_SOURCE, "")
                + ", status=" + preferences.getString(KEY_LAST_STATUS, "\u5c1a\u672a\u68c0\u67e5");
    }

    public static String lastStatus(Context context) {
        return prefs(context).getString(KEY_LAST_STATUS, "\u5c1a\u672a\u68c0\u67e5");
    }

    private static DownloadResult downloadRules() throws IOException {
        IOException lastError = null;
        for (RuleSource source : SOURCES) {
            try {
                List<String> rulePaths = listRulePaths(source);
                if (rulePaths.isEmpty()) throw new IOException("no-rule-files");
                Map<String, List<String>> merged = new LinkedHashMap<>();
                for (String path : rulePaths) {
                    String body = fetch(rawRuleUrl(source, path));
                    if (body.length() > MAX_RULE_FILE_BYTES) continue;
                    addSafeRule(merged, jsonObject(body));
                    if (merged.size() >= MAX_HOSTS) break;
                }
                return new DownloadResult(merged, source.name);
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw lastError == null ? new IOException("all-sources-unavailable") : lastError;
    }

    private static List<String> listRulePaths(RuleSource source) throws IOException {
        JsonObject index = jsonObject(fetch(source.indexUrl));
        List<String> result = new ArrayList<>();
        if (source.format == RuleSource.TreeFormat.GITHUB) {
            JsonArray tree = index.has("tree") && index.get("tree").isJsonArray() ? index.getAsJsonArray("tree") : null;
            if (tree == null) throw new IOException("github-tree-invalid");
            for (JsonElement element : tree) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String path = string(item, "path");
                if ("blob".equals(string(item, "type")) && isRulePath(path)) result.add(path);
            }
        } else {
            JsonArray files = index.has("files") && index.get("files").isJsonArray() ? index.getAsJsonArray("files") : null;
            if (files == null) throw new IOException("jsdelivr-tree-invalid");
            collectJsDelivrRulePaths(files, "", result);
        }
        Collections.sort(result);
        if (result.size() > MAX_RULE_FILES) return new ArrayList<>(result.subList(0, MAX_RULE_FILES));
        return result;
    }

    private static void collectJsDelivrRulePaths(JsonArray files, String parent, List<String> target) {
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name");
            if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) continue;
            String path = parent.isEmpty() ? name : parent + "/" + name;
            String type = string(item, "type");
            if ("file".equals(type)) {
                if (isRulePath(path)) target.add(path);
            } else if ("directory".equals(type) && item.has("files") && item.get("files").isJsonArray()) {
                collectJsDelivrRulePaths(item.getAsJsonArray("files"), path, target);
            }
        }
    }

    private static boolean isRulePath(String path) {
        return path != null && path.startsWith("rules/") && path.endsWith(".json")
                && !path.contains("..") && path.indexOf('\\') < 0 && path.length() <= 256;
    }

    private static HttpUrl rawRuleUrl(RuleSource source, String path) throws IOException {
        if (source == null || source.rawBase == null) throw new IOException("raw-base-invalid");
        HttpUrl.Builder builder = source.rawBase.newBuilder();
        for (String part : path.split("/")) builder.addPathSegment(part);
        return builder.build();
    }

    private static String fetch(HttpUrl url) throws IOException {
        if (url == null) throw new IOException("url-invalid");
        Request request = new Request.Builder().url(url).header("User-Agent", "IceBridge-safe-rules/1").build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("http-" + response.code());
            return response.body().string();
        }
    }

    private static JsonObject jsonObject(String input) throws IOException {
        try {
            JsonElement parsed = new JsonParser().parse(input);
            if (!parsed.isJsonObject()) throw new IOException("json-not-object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("json-invalid", error);
        }
    }

    /** Fluid Cloud safe subset: type=pkg, pkg=<package>, tigger=[host patterns]. */
    private static void addSafeRule(Map<String, List<String>> target, JsonObject rule) {
        if (!"pkg".equalsIgnoreCase(string(rule, "type"))) return;
        String packageName = string(rule, "pkg");
        if (packageName == null || !PACKAGE_PATTERN.matcher(packageName).matches()) return;
        JsonElement triggers = rule.get("tigger");
        if (triggers == null || !triggers.isJsonArray()) return;
        for (JsonElement trigger : triggers.getAsJsonArray()) {
            if (!trigger.isJsonPrimitive()) continue;
            String host = normalizeHost(trigger.getAsString());
            if (host == null || (target.size() >= MAX_HOSTS && !target.containsKey(host))) continue;
            List<String> candidates = target.get(host);
            if (candidates == null) { candidates = new ArrayList<>(); target.put(host, candidates); }
            if (!candidates.contains(packageName)) candidates.add(packageName);
        }
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

    /**
     * Merges a downloaded snapshot into the existing online cache. A refresh is additive:
     * old valid host/package candidates are retained, while duplicate host/package pairs are
     * written only once. This prevents a partial source response from deleting usable rules.
     */
    private static int mergeAndSaveRules(Context context, Map<String, List<String>> downloaded) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        appendCandidates(merged, hostCandidates(context));
        appendCandidates(merged, downloaded);
        saveRules(context, merged);
        int count = 0;
        for (List<String> packages : merged.values()) count += packages.size();
        return count;
    }

    private static void appendCandidates(Map<String, List<String>> target, Map<String, List<String>> source) {
        if (source == null) return;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String host = normalizeHost(entry.getKey());
            if (host == null) continue;
            if (target.size() >= MAX_HOSTS && !target.containsKey(host)) continue;
            List<String> packages = target.get(host);
            if (packages == null) { packages = new ArrayList<>(); target.put(host, packages); }
            if (entry.getValue() == null) continue;
            for (String packageName : entry.getValue()) {
                if (packageName == null || !PACKAGE_PATTERN.matcher(packageName).matches()) continue;
                if (!packages.contains(packageName)) packages.add(packageName);
            }
        }
    }

    private static void saveRules(Context context, Map<String, List<String>> rules) {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("host", entry.getKey());
            JsonArray packages = new JsonArray();
            for (String packageName : entry.getValue()) packages.add(packageName);
            item.add("packages", packages);
            array.add(item);
        }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply();
    }

    /** Reads both the former host/package cache and the current host/packages cache. */
    private static Map<String, List<String>> parseStoredCandidates(String raw) {
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

    private static final class DownloadResult {
        final Map<String, List<String>> rules;
        final String sourceName;

        DownloadResult(Map<String, List<String>> rules, String sourceName) {
            this.rules = rules;
            this.sourceName = sourceName;
        }
    }

    private static final class RuleSource {
        enum TreeFormat { GITHUB, JSDELIVR }

        final String name;
        final HttpUrl indexUrl;
        final HttpUrl rawBase;
        final TreeFormat format;

        RuleSource(String name, String indexUrl, String rawBase, TreeFormat format) {
            this.name = name;
            this.indexUrl = HttpUrl.parse(indexUrl);
            this.rawBase = HttpUrl.parse(rawBase);
            this.format = format;
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