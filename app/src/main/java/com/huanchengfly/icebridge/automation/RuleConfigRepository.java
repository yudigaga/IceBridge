package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persists an auditable routing snapshot. Individual rules are kept separate here; only the
 * system_server hand-off is compacted to host=package1,package2. Thus different candidates,
 * rule origins and the reviewed URL conversion flag are never lost because a host is shared.
 */
public final class RuleConfigRepository {
    private static final String PREFS = LinkAutomationConfig.PREFS;
    private static final String KEY_MANUAL = "rule_config_manual_v1";
    private static final String KEY_EFFECTIVE = "rule_config_effective_v1";
    private static final String KEY_EFFECTIVE_CANDIDATES = "rule_config_effective_candidates_v2";
    private static final String KEY_EFFECTIVE_RULES = "rule_config_effective_rules_v3";
    private static final String KEY_SOURCE_RULES = "rule_config_source_rules_v1";
    private static final int MAX_HOSTS = 96;
    private static final int MAX_HOSTS_PER_PACKAGE = 20;
    private static final int MAX_RULE_ENTRIES = MAX_HOSTS * 8;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");

    private RuleConfigRepository() { }

    /** The last explicitly saved rules, including source and fixed conversion metadata. */
    public static List<LinkRuleEntry> effectiveRuleEntries(Context context) {
        Context app = app(context);
        if (app == null) return Collections.emptyList();
        String v3 = prefs(app).getString(KEY_EFFECTIVE_RULES, "");
        List<LinkRuleEntry> stored = parseRuleEntries(v3);
        if (!stored.isEmpty()) return stored;

        // Preserve a prior explicit save during upgrade. Metadata is reconstructed conservatively;
        // the next press of Save stores the fully auditable v3 snapshot.
        Map<String, List<String>> v2 = parseCandidateMap(prefs(app).getString(KEY_EFFECTIVE_CANDIDATES, ""));
        if (v2.isEmpty()) v2 = candidatesFromFlat(parseHostMap(prefs(app).getString(KEY_EFFECTIVE, "")));
        if (!v2.isEmpty()) return annotateLegacyCandidates(app, v2);
        return buildEffectiveRuleEntries(manualRulesByPackage(app), onlineRuleEntries(app),
                FluidCloudRuleCatalog.builtInRuleEntries());
    }

    /** Ordered candidates for routing. The first candidate wins only when it is installed/usable. */
    public static Map<String, List<String>> effectiveHostCandidates(Context context) {
        return candidatesFromEntries(effectiveRuleEntries(context));
    }

    /** First-candidate compatibility projection retained for older callers. */
    /**
     * Returns candidates visible to a particular source application. Exact source rules are
     * evaluated before wildcard rules; candidates keep their saved order and are de-duplicated.
     * Clipboard and legacy callers should pass "*".
     */
    public static Map<String, List<String>> effectiveHostCandidates(Context context, String sourcePackage) {
        String source = sourcePackage == null || sourcePackage.trim().isEmpty() ? "*" : sourcePackage.trim();
        List<LinkRuleEntry> all = effectiveRuleEntries(context);
        List<LinkRuleEntry> exact = new ArrayList<>();
        List<LinkRuleEntry> wildcard = new ArrayList<>();
        for (LinkRuleEntry entry : all) {
            if (source.equals(entry.sourcePackage)) exact.add(entry);
            else if ("*".equals(entry.sourcePackage)) wildcard.add(entry);
        }
        List<LinkRuleEntry> selected = new ArrayList<>(exact);
        selected.addAll(wildcard);
        return candidatesFromEntries(selected);
    }
    public static Map<String, String> effectiveHostMappings(Context context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : effectiveHostCandidates(context).entrySet()) {
            if (!entry.getValue().isEmpty()) result.put(entry.getKey(), entry.getValue().get(0));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Compact snapshot passed to system_server; provenance remains in the app-side v3 snapshot. */
    public static String[] systemServerEntries(Context context) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : effectiveHostCandidates(context).entrySet()) {
            if (!entry.getValue().isEmpty()) entries.add(entry.getKey() + "=" + TextUtils.join(",", entry.getValue()));
        }
        return entries.toArray(new String[0]);
    }

    /** Manual fields grouped by target package, including one-time legacy free-form migration. */
    public static Map<String, List<String>> manualRulesByPackage(Context context) {
        Context app = app(context);
        if (app == null) return Collections.emptyMap();
        Map<String, List<String>> result = parseManualRules(prefs(app).getString(KEY_MANUAL, ""));
        for (Map.Entry<String, String> entry : LinkAutomationConfig.customMappings(app).entrySet()) {
            if (!validPackage(entry.getValue())) continue;
            List<String> hosts = result.get(entry.getValue());
            if (hosts == null) { hosts = new ArrayList<>(); result.put(entry.getValue(), hosts); }
            String host = normalizeHostOrUrl(entry.getKey());
            if (host != null && !hosts.contains(host) && hosts.size() < MAX_HOSTS_PER_PACKAGE) hosts.add(host);
        }
        return immutableRuleMap(result);
    }

    public static Map<String, List<String>> onlineRulesByPackage(Context context) {
        return groupByCandidatePackage(OnlineLinkRuleRepository.hostCandidates(context));
    }

    public static Map<String, List<String>> builtInRulesByPackage() {
        return groupByEntries(FluidCloudRuleCatalog.builtInRuleEntries());
    }

    public static Map<String, List<String>> effectiveRulesByPackage(Context context) {
        return groupByEntries(effectiveRuleEntries(context));
    }

    /** Rules explicitly created by the user with an optional source application. */
    public static List<LinkRuleEntry> sourceRuleEntries(Context context) {
        Context app = app(context);
        if (app == null) return Collections.emptyList();
        return parseRuleEntries(prefs(app).getString(KEY_SOURCE_RULES, ""));
    }

    /** Rules grouped by source application; wildcard rules are grouped under "*". */
    public static Map<String, List<LinkRuleEntry>> sourceRulesByPackage(Context context) {
        Map<String, List<LinkRuleEntry>> result = new LinkedHashMap<>();
        for (LinkRuleEntry entry : sourceRuleEntries(context)) {
            List<LinkRuleEntry> list = result.get(entry.sourcePackage);
            if (list == null) { list = new ArrayList<>(); result.put(entry.sourcePackage, list); }
            list.add(entry);
        }
        return result;
    }

    /** Saves source-scoped rules additively; blank/duplicate rows are discarded. */
    public static SaveResult saveSourceRules(Context context, List<LinkRuleEntry> rawRules) {
        Context app = app(context);
        if (app == null) return new SaveResult(0, 0, 0, 0);
        List<LinkRuleEntry> sourceRules = new ArrayList<>();
        if (rawRules != null) for (LinkRuleEntry raw : rawRules) {
            LinkRuleEntry safe = sanitizeEntry(raw);
            if (safe == null || !validPackage(safe.sourcePackage)) continue;
            sourceRules.add(new LinkRuleEntry(safe.sourcePackage, safe.host, safe.targetPackage,
                    LinkRuleEntry.SOURCE_MANUAL, safe.urlConversion, safe.note));
        }
        sourceRules = deduplicateAndOrderStored(sourceRules);
        List<LinkRuleEntry> wildcard = new ArrayList<>();
        for (LinkRuleEntry entry : sourceRules) {
            if ("*".equals(entry.sourcePackage)) wildcard.add(entry);
        }
        List<LinkRuleEntry> effective = buildEffectiveRuleEntriesFromEntries(sourceRules,
                onlineRuleEntries(app), FluidCloudRuleCatalog.builtInRuleEntries());
        Map<String, List<String>> candidates = candidatesFromEntries(effective);
        prefs(app).edit().putString(KEY_SOURCE_RULES, serializeRuleEntries(sourceRules))
                .putString(KEY_EFFECTIVE, serializeFlatMap(candidates))
                .putString(KEY_EFFECTIVE_CANDIDATES, serializeCandidateMap(candidates))
                .putString(KEY_EFFECTIVE_RULES, serializeRuleEntries(effective)).apply();
        return new SaveResult(candidates.size(), sourceRules.size(), distinctPackages(onlineRuleEntries(app)).size(),
                distinctPackages(FluidCloudRuleCatalog.builtInRuleEntries()).size());
    }

    /** Normalizes a newline, comma or semicolon separated list of domains or HTTP(S) URLs. */
    public static List<String> normalizeUserHosts(String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>();
        for (String item : raw.split("[,\uFF0C;\uFF1B\\n\\r]+")) {
            String host = normalizeHostOrUrl(item);
            if (host != null) result.add(host);
            if (result.size() >= MAX_HOSTS_PER_PACKAGE) break;
        }
        return new ArrayList<>(result);
    }

    public static String formatHosts(List<String> hosts) {
        return hosts == null || hosts.isEmpty() ? "\u65e0" : TextUtils.join("\n", hosts);
    }

    /** Saves editor input and atomically creates the only runtime snapshot used afterwards. */
    public static SaveResult save(Context context, Map<String, String> rawManualByPackage) {
        Context app = app(context);
        if (app == null) return new SaveResult(0, 0, 0, 0);
        Map<String, List<String>> manual = new LinkedHashMap<>();
        if (rawManualByPackage != null) for (Map.Entry<String, String> entry : rawManualByPackage.entrySet()) {
            if (!validPackage(entry.getKey())) continue;
            List<String> hosts = normalizeUserHosts(entry.getValue());
            if (!hosts.isEmpty()) manual.put(entry.getKey(), hosts);
        }
        List<LinkRuleEntry> online = onlineRuleEntries(app);
        List<LinkRuleEntry> defaults = FluidCloudRuleCatalog.builtInRuleEntries();
        List<LinkRuleEntry> effective = buildEffectiveRuleEntries(manual, online, defaults);
        Map<String, List<String>> candidates = candidatesFromEntries(effective);
        prefs(app).edit()
                .putString(KEY_MANUAL, serializeManualRules(manual))
                .putString(KEY_EFFECTIVE, serializeFlatMap(candidates))
                .putString(KEY_EFFECTIVE_CANDIDATES, serializeCandidateMap(candidates))
                .putString(KEY_EFFECTIVE_RULES, serializeRuleEntries(effective))
                .remove(LinkAutomationConfig.KEY_CUSTOM_MAPPINGS)
                .apply();
        return new SaveResult(candidates.size(), manual.size(), distinctPackages(online).size(), distinctPackages(defaults).size());
    }

    private static List<LinkRuleEntry> buildEffectiveRuleEntriesFromEntries(List<LinkRuleEntry> manual,
                                                                               List<LinkRuleEntry> online,
                                                                               List<LinkRuleEntry> defaults) {
        List<LinkRuleEntry> ordered = new ArrayList<>();
        if (manual != null) ordered.addAll(manual);
        appendSource(ordered, online, LinkRuleEntry.SOURCE_ONLINE, Collections.<String>emptySet());
        appendSource(ordered, defaults, LinkRuleEntry.SOURCE_BUILT_IN, Collections.<String>emptySet());
        LinkedHashMap<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (LinkRuleEntry raw : ordered) {
            LinkRuleEntry safe = sanitizeEntry(raw);
            if (safe == null || (!"*".equals(safe.sourcePackage) && !validPackage(safe.sourcePackage))) continue;
            if (!hosts.contains(safe.host) && hosts.size() >= MAX_HOSTS) continue;
            if (!unique.containsKey(ruleKey(safe))) { unique.put(ruleKey(safe), safe); hosts.add(safe.host); }
            if (unique.size() >= MAX_RULE_ENTRIES) break;
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    /** Pure merge used by persistence and unit tests: manual > online > built-in. */
    static List<LinkRuleEntry> buildEffectiveRuleEntries(Map<String, List<String>> manual,
                                                           List<LinkRuleEntry> online,
                                                           List<LinkRuleEntry> defaults) {
        Set<String> manualPackages = manual == null ? Collections.<String>emptySet() : manual.keySet();
        List<LinkRuleEntry> ordered = new ArrayList<>();
        if (manual != null) for (Map.Entry<String, List<String>> item : manual.entrySet()) {
            if (!validPackage(item.getKey())) continue;
            for (String rawHost : item.getValue()) {
                String host = normalizeHostOrUrl(rawHost);
                if (host != null) ordered.add(new LinkRuleEntry(host, item.getKey(), LinkRuleEntry.SOURCE_MANUAL,
                        LinkRuleEntry.CONVERSION_NONE, "\u7528\u6237\u624b\u52a8\u586b\u5199"));
            }
        }
        appendSource(ordered, online, LinkRuleEntry.SOURCE_ONLINE, manualPackages);
        appendSource(ordered, defaults, LinkRuleEntry.SOURCE_BUILT_IN, manualPackages);

        LinkedHashMap<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (LinkRuleEntry entry : ordered) {
            LinkRuleEntry safe = sanitizeEntry(entry);
            if (safe == null) continue;
            if (!hosts.contains(safe.host) && hosts.size() >= MAX_HOSTS) continue;
            String key = ruleKey(safe);
            if (!unique.containsKey(key)) { unique.put(key, safe); hosts.add(safe.host); }
            if (unique.size() >= MAX_RULE_ENTRIES) break;
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static void appendSource(List<LinkRuleEntry> target, List<LinkRuleEntry> entries,
                                     String source, Set<String> excludedPackages) {
        if (entries == null) return;
        for (LinkRuleEntry entry : entries) {
            LinkRuleEntry safe = sanitizeEntry(entry);
            if (safe == null || excludedPackages.contains(safe.targetPackage)) continue;
            target.add(new LinkRuleEntry(safe.sourcePackage, safe.host, safe.targetPackage, source, safe.urlConversion, safe.note));
        }
    }

    private static List<LinkRuleEntry> onlineRuleEntries(Context context) {
        List<LinkRuleEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : OnlineLinkRuleRepository.hostCandidates(context).entrySet()) {
            if (entry.getValue() == null) continue;
            for (String packageName : entry.getValue()) result.add(new LinkRuleEntry(entry.getKey(), packageName,
                    LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_NONE, "\u5728\u7ebf\u58f0\u660e\u5f0f\u89c4\u5219"));
        }
        return result;
    }

    private static List<LinkRuleEntry> annotateLegacyCandidates(Context context, Map<String, List<String>> legacy) {
        Map<String, List<String>> online = OnlineLinkRuleRepository.hostCandidates(context);
        List<LinkRuleEntry> defaults = FluidCloudRuleCatalog.builtInRuleEntries();
        Map<String, LinkRuleEntry> builtIn = indexEntries(defaults);
        List<LinkRuleEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> item : legacy.entrySet()) if (item.getValue() != null) for (String packageName : item.getValue()) {
            String host = normalizeHostOrUrl(item.getKey());
            if (host == null || !validPackage(packageName)) continue;
            LinkRuleEntry defaultEntry = builtIn.get(host + '\u0000' + packageName);
            if (defaultEntry != null) result.add(defaultEntry);
            else if (online.containsKey(host) && online.get(host).contains(packageName)) result.add(new LinkRuleEntry(host, packageName,
                    LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_NONE, "\u5728\u7ebf\u58f0\u660e\u5f0f\u89c4\u5219\uff08\u65e7\u5feb\u7167\uff09"));
            else result.add(new LinkRuleEntry(host, packageName, LinkRuleEntry.SOURCE_MANUAL,
                    LinkRuleEntry.CONVERSION_NONE, "\u65e7\u7248\u5df2\u4fdd\u5b58\u89c4\u5219"));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, LinkRuleEntry> indexEntries(List<LinkRuleEntry> entries) {
        Map<String, LinkRuleEntry> result = new LinkedHashMap<>();
        if (entries != null) for (LinkRuleEntry entry : entries) {
            LinkRuleEntry safe = sanitizeEntry(entry);
            if (safe != null) result.put(safe.host + '\u0000' + safe.targetPackage, safe);
        }
        return result;
    }

    private static Map<String, List<String>> candidatesFromEntries(List<LinkRuleEntry> entries) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (entries != null) for (LinkRuleEntry entry : entries) {
            LinkRuleEntry safe = sanitizeEntry(entry);
            if (safe == null || (result.size() >= MAX_HOSTS && !result.containsKey(safe.host))) continue;
            List<String> values = result.get(safe.host);
            if (values == null) { values = new ArrayList<>(); result.put(safe.host, values); }
            if (!values.contains(safe.targetPackage)) values.add(safe.targetPackage);
        }
        return immutableRuleMap(result);
    }

    private static Map<String, List<String>> candidatesFromFlat(Map<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source != null) for (Map.Entry<String, String> entry : source.entrySet()) {
            String host = normalizeHostOrUrl(entry.getKey());
            if (host == null || !validPackage(entry.getValue())) continue;
            List<String> values = result.get(host);
            if (values == null) { values = new ArrayList<>(); result.put(host, values); }
            if (!values.contains(entry.getValue())) values.add(entry.getValue());
        }
        return immutableRuleMap(result);
    }

    private static Map<String, List<String>> groupByEntries(List<LinkRuleEntry> entries) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (entries != null) for (LinkRuleEntry entry : entries) {
            LinkRuleEntry safe = sanitizeEntry(entry);
            if (safe == null) continue;
            List<String> hosts = result.get(safe.targetPackage);
            if (hosts == null) { hosts = new ArrayList<>(); result.put(safe.targetPackage, hosts); }
            if (!hosts.contains(safe.host) && hosts.size() < MAX_HOSTS_PER_PACKAGE) hosts.add(safe.host);
        }
        return immutableRuleMap(result);
    }

    private static Map<String, List<String>> groupByCandidatePackage(Map<String, List<String>> mappings) {
        List<LinkRuleEntry> entries = new ArrayList<>();
        if (mappings != null) for (Map.Entry<String, List<String>> entry : mappings.entrySet()) if (entry.getValue() != null) {
            for (String packageName : entry.getValue()) entries.add(new LinkRuleEntry(entry.getKey(), packageName,
                    LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_NONE, ""));
        }
        return groupByEntries(entries);
    }

    private static Set<String> distinctPackages(List<LinkRuleEntry> entries) {
        Set<String> result = new LinkedHashSet<>();
        if (entries != null) for (LinkRuleEntry entry : entries) if (sanitizeEntry(entry) != null) result.add(entry.targetPackage);
        return result;
    }

    private static Map<String, List<String>> parseManualRules(String raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.length() > 32 * 1024) return result;
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonArray()) return result;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String packageName = primitive(item, "package");
                JsonElement values = item.get("hosts");
                if (!validPackage(packageName) || values == null || !values.isJsonArray()) continue;
                List<String> hosts = new ArrayList<>();
                for (JsonElement value : values.getAsJsonArray()) {
                    if (!value.isJsonPrimitive()) continue;
                    String host = normalizeHostOrUrl(value.getAsString());
                    if (host != null && !hosts.contains(host)) hosts.add(host);
                    if (hosts.size() >= MAX_HOSTS_PER_PACKAGE) break;
                }
                if (!hosts.isEmpty()) result.put(packageName, hosts);
            }
        } catch (RuntimeException ignored) { return new LinkedHashMap<>(); }
        return result;
    }

    private static List<LinkRuleEntry> parseRuleEntries(String raw) {
        if (raw == null || raw.length() > 128 * 1024) return Collections.emptyList();
        List<LinkRuleEntry> result = new ArrayList<>();
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonArray()) return result;
            for (JsonElement value : parsed.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject item = value.getAsJsonObject();
                LinkRuleEntry entry = sanitizeEntry(new LinkRuleEntry(primitive(item, "sourcePackage"), primitive(item, "host"),
                        primitive(item, "package"), primitive(item, "source"), primitive(item, "conversion"), primitive(item, "note")));
                if (entry != null) result.add(entry);
                if (result.size() >= MAX_RULE_ENTRIES) break;
            }
        } catch (RuntimeException ignored) { return Collections.emptyList(); }
        return deduplicateAndOrderStored(result);
    }

    private static List<LinkRuleEntry> deduplicateAndOrderStored(List<LinkRuleEntry> entries) {
        List<LinkRuleEntry> ordered = new ArrayList<>(entries);
        Collections.sort(ordered, new Comparator<LinkRuleEntry>() {
            @Override public int compare(LinkRuleEntry a, LinkRuleEntry b) { return sourcePriority(a.source) - sourcePriority(b.source); }
        });
        LinkedHashMap<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        for (LinkRuleEntry entry : ordered) {
            String key = ruleKey(entry);
            if (!unique.containsKey(key)) unique.put(key, entry);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static String ruleKey(LinkRuleEntry entry) {
        return entry.sourcePackage + '\u0000' + entry.host + '\u0000' + entry.targetPackage + '\u0000' + entry.urlConversion;
    }

    private static int sourcePriority(String source) {
        if (LinkRuleEntry.SOURCE_MANUAL.equals(source)) return 0;
        if (LinkRuleEntry.SOURCE_ONLINE.equals(source)) return 1;
        return 2;
    }

    private static Map<String, List<String>> parseCandidateMap(String raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.length() > 64 * 1024) return result;
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonArray()) return result;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String host = normalizeHostOrUrl(primitive(object, "host"));
                JsonElement values = object.get("packages");
                if (host == null || values == null || !values.isJsonArray()) continue;
                for (JsonElement item : values.getAsJsonArray()) if (item.isJsonPrimitive()) {
                    String packageName = item.getAsString();
                    if (!validPackage(packageName)) continue;
                    List<String> list = result.get(host);
                    if (list == null) { list = new ArrayList<>(); result.put(host, list); }
                    if (!list.contains(packageName)) list.add(packageName);
                }
            }
        } catch (RuntimeException ignored) { return Collections.emptyMap(); }
        return immutableRuleMap(result);
    }

    private static Map<String, String> parseHostMap(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.length() > 32 * 1024) return result;
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonArray()) return result;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String host = normalizeHostOrUrl(primitive(item, "host"));
                String packageName = primitive(item, "package");
                if (host != null && validPackage(packageName)) result.put(host, packageName);
            }
        } catch (RuntimeException ignored) { return Collections.emptyMap(); }
        return Collections.unmodifiableMap(result);
    }

    private static String serializeManualRules(Map<String, List<String>> rules) {
        JsonArray values = new JsonArray();
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            JsonObject item = new JsonObject(); item.addProperty("package", entry.getKey());
            JsonArray hosts = new JsonArray(); for (String host : entry.getValue()) hosts.add(host);
            item.add("hosts", hosts); values.add(item);
        }
        return values.toString();
    }

    private static String serializeRuleEntries(List<LinkRuleEntry> entries) {
        JsonArray values = new JsonArray();
        for (LinkRuleEntry raw : entries) {
            LinkRuleEntry entry = sanitizeEntry(raw); if (entry == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("sourcePackage", entry.sourcePackage);
            item.addProperty("host", entry.host); item.addProperty("package", entry.targetPackage);
            item.addProperty("source", entry.source); item.addProperty("conversion", entry.urlConversion);
            item.addProperty("note", entry.note); values.add(item);
        }
        return values.toString();
    }

    private static String serializeCandidateMap(Map<String, List<String>> mappings) {
        JsonArray values = new JsonArray();
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            JsonObject item = new JsonObject(); item.addProperty("host", entry.getKey());
            JsonArray packages = new JsonArray(); for (String packageName : entry.getValue()) packages.add(packageName);
            item.add("packages", packages); values.add(item);
        }
        return values.toString();
    }

    private static String serializeFlatMap(Map<String, List<String>> mappings) {
        JsonArray values = new JsonArray();
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            JsonObject item = new JsonObject(); item.addProperty("host", entry.getKey()); item.addProperty("package", entry.getValue().get(0)); values.add(item);
        }
        return values.toString();
    }

    private static LinkRuleEntry sanitizeEntry(LinkRuleEntry raw) {
        if (raw == null) return null;
        String host = normalizeHostOrUrl(raw.host);
        if (host == null || !validPackage(raw.targetPackage) || !validSource(raw.source) || !validConversion(raw.urlConversion)) return null;
        String note = raw.note == null ? "" : raw.note.trim();
        if (note.length() > 160) note = note.substring(0, 160);
        return new LinkRuleEntry(raw.sourcePackage, host, raw.targetPackage, raw.source, raw.urlConversion, note);
    }

    private static boolean validPackage(String packageName) { return packageName != null && ("*".equals(packageName) || PACKAGE_PATTERN.matcher(packageName).matches()); }
    private static boolean validSource(String source) { return LinkRuleEntry.SOURCE_BUILT_IN.equals(source) || LinkRuleEntry.SOURCE_ONLINE.equals(source) || LinkRuleEntry.SOURCE_MANUAL.equals(source); }
    private static boolean validConversion(String conversion) { return LinkRuleEntry.CONVERSION_NONE.equals(conversion) || LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(conversion); }

    private static Map<String, List<String>> immutableRuleMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(entry.getValue()))));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String primitive(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String normalizeHostOrUrl(String value) {
        if (value == null) return null;
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        if (candidate.isEmpty() || candidate.length() > 256) return null;
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            try { candidate = Uri.parse(candidate).getHost(); } catch (Throwable ignored) { return null; }
        } else {
            if (candidate.startsWith("//")) candidate = candidate.substring(2);
            int end = candidate.length();
            for (char delimiter : new char[] {'/', '?', '#'}) { int index = candidate.indexOf(delimiter); if (index >= 0) end = Math.min(end, index); }
            candidate = candidate.substring(0, end);
        }
        if (candidate == null) return null;
        while (candidate.startsWith(".")) candidate = candidate.substring(1);
        return HOST_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static Context app(Context context) { return context == null ? null : context.getApplicationContext(); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static final class SaveResult {
        public final int effectiveHosts;
        public final int manualApps;
        public final int onlineApps;
        public final int defaultApps;
        SaveResult(int effectiveHosts, int manualApps, int onlineApps, int defaultApps) {
            this.effectiveHosts = effectiveHosts; this.manualApps = manualApps; this.onlineApps = onlineApps; this.defaultApps = defaultApps;
        }
    }
}



