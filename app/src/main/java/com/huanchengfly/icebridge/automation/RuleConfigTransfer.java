package com.huanchengfly.icebridge.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, user-facing transfer format for manual routing rules. */
public final class RuleConfigTransfer {
    public static final String FORMAT = "icebridge-rules";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_RULES = 768;

    private RuleConfigTransfer() { }

    public static String exportJson(List<LinkRuleEntry> sourceRules) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("exportedAt", System.currentTimeMillis());
        JsonArray rules = new JsonArray();
        Map<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        if (sourceRules != null) {
            for (LinkRuleEntry raw : sourceRules) {
                LinkRuleEntry safe = manualEntry(raw);
                if (safe != null && unique.size() < MAX_RULES) unique.put(key(safe), safe);
            }
        }
        for (LinkRuleEntry entry : unique.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("sourcePackage", entry.sourcePackage);
            item.addProperty("host", entry.host);
            item.addProperty("package", entry.targetPackage);
            item.addProperty("conversion", entry.urlConversion);
            item.addProperty("note", entry.note);
            rules.add(item);
        }
        root.add("rules", rules);
        return root.toString();
    }

    public static List<LinkRuleEntry> importJson(String json) throws TransferException {
        if (json == null || json.trim().isEmpty()) throw new TransferException("文件为空");
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new TransferException("文件超过 1 MB 限制");
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(json);
        } catch (RuntimeException error) {
            throw new TransferException("JSON 格式无效", error);
        }
        if (!parsed.isJsonObject()) throw new TransferException("根节点必须是 JSON 对象");
        JsonObject root = parsed.getAsJsonObject();
        if (!FORMAT.equals(stringValue(root, "format"))) throw new TransferException("不是 IceBridge 规则文件");
        Integer version = integerValue(root, "version");
        if (version == null || version != VERSION) throw new TransferException("不支持的规则文件版本");
        JsonElement rawRules = root.get("rules");
        if (rawRules == null || !rawRules.isJsonArray()) throw new TransferException("缺少 rules 数组");
        if (rawRules.getAsJsonArray().size() > MAX_RULES) throw new TransferException("规则数量超过限制");

        Map<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        int hostCount = 0;
        for (JsonElement element : rawRules.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new TransferException("规则项必须是对象");
            JsonObject item = element.getAsJsonObject();
            String sourcePackage = stringValue(item, "sourcePackage");
            String host = stringValue(item, "host");
            String targetPackage = stringValue(item, "package");
            if (targetPackage == null) targetPackage = stringValue(item, "targetPackage");
            String conversion = stringValue(item, "conversion");
            String note = stringValue(item, "note");
            if (sourcePackage == null || sourcePackage.trim().isEmpty()) sourcePackage = "*";
            if (conversion == null || conversion.trim().isEmpty()) conversion = LinkRuleEntry.CONVERSION_NONE;
            if (note == null) note = "";
            LinkRuleEntry entry = manualEntry(new LinkRuleEntry(sourcePackage, host, targetPackage,
                    LinkRuleEntry.SOURCE_MANUAL, conversion, note));
            if (entry == null) throw new TransferException("规则包含无效的域名、包名或转换配置");
            String key = key(entry);
            if (!unique.containsKey(key)) {
                unique.put(key, entry);
                if (hosts.add(entry.host)) hostCount++;
                if (hostCount > 96) throw new TransferException("域名数量超过限制");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static LinkRuleEntry manualEntry(LinkRuleEntry raw) {
        if (raw == null || !LinkRuleEntry.SOURCE_MANUAL.equals(raw.source)) return null;
        LinkRuleEntry safe = RuleConfigRepository.sanitizeForTransfer(raw);
        if (safe == null) return null;
        if (!RuleConfigRepository.isValidRulePackage(safe.sourcePackage)) return null;
        return safe;
    }

    private static String key(LinkRuleEntry entry) {
        return entry.sourcePackage + '\u0000' + entry.host + '\u0000' + entry.targetPackage
                + '\u0000' + entry.urlConversion;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integerValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        try { return value.getAsInt(); } catch (RuntimeException ignored) { return null; }
    }

    public static final class TransferException extends Exception {
        public TransferException(String message) { super(message); }
        public TransferException(String message, Throwable cause) { super(message, cause); }
    }
}

