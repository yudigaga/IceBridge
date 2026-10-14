package com.huanchengfly.icebridge.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.net.URI;

/** Reports rejected editor input before the repository's defensive sanitization can discard it. */
public final class RuleEditorValidation {
    private RuleEditorValidation() {}

    public static final class Result {
        public final List<LinkRuleEntry> entries;
        public final List<String> errors;
        Result(List<LinkRuleEntry> entries, List<String> errors) {
            this.entries = Collections.unmodifiableList(entries);
            this.errors = Collections.unmodifiableList(errors);
        }
        public boolean isValid() { return errors.isEmpty(); }
    }

    public static Result validate(String source, String target, String text, String conversion, String note) {
        List<String> errors = new ArrayList<>();
        List<LinkRuleEntry> entries = new ArrayList<>();
        source = source == null || source.trim().isEmpty() ? "*" : source.trim();
        target = target == null ? "" : target.trim();
        note = note == null ? "" : note.trim();
        if (source.length() > 255 || !RuleConfigRepository.isValidRulePackage(source)) errors.add("发起应用包名无效；填写不超过 255 字符的完整包名或 *");
        if (target.length() > 255 || "*".equals(target) || !RuleConfigRepository.isValidRulePackage(target)) errors.add("目标应用包名无效或超过 255 字符");
        if (!LinkRuleEntry.CONVERSION_NONE.equals(conversion)
                && !LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(conversion)) errors.add("URL 转换类型无效");
        if (note.length() > 160) errors.add("备注不能超过 160 个字符");
        Set<String> hosts = new LinkedHashSet<>();
        if (text != null) for (String token : text.split("[,，;；\\n\\r]+")) {
            if (token.trim().isEmpty()) continue;
            List<String> normalized = RuleConfigRepository.normalizeUserHosts(token);
            if (normalized.isEmpty()) errors.add("域名或 HTTP(S) URL 无效：" + token.trim());
            else if (normalized.get(0).length() > 128) errors.add("域名不能超过 128 个字符：" + token.trim());
            else hosts.addAll(normalized);
        }
        if (hosts.isEmpty()) errors.add("至少填写一个有效域名或 HTTP(S) URL；不需要的行请删除");
        if (hosts.size() > 20) errors.add("每行最多 20 个不同域名，请拆分为多条规则");
        if (errors.isEmpty()) for (String host : hosts) {
            entries.add(new LinkRuleEntry(source, host, target, LinkRuleEntry.SOURCE_MANUAL, conversion, note));
        }
        return new Result(entries, errors);
    }

    public static String validateCapacity(List<LinkRuleEntry> entries) {
        if (entries.size() > 768) return "手动规则不能超过 768 条";
        Set<String> hosts = new LinkedHashSet<>();
        for (LinkRuleEntry entry : entries) hosts.add(entry.host);
        return hosts.size() > 96 ? "手动规则不能超过 96 个不同域名" : null;
    }

    /** The caller supplies the complete durable manual set, including migrated legacy mappings. */
    public static List<LinkRuleEntry> replaceTarget(List<LinkRuleEntry> durableManual, String target,
                                                     List<LinkRuleEntry> edited) {
        List<LinkRuleEntry> combined = new ArrayList<>();
        for (LinkRuleEntry entry : durableManual) {
            if (!target.equals(entry.targetPackage)) combined.add(entry);
        }
        for (LinkRuleEntry entry : edited) {
            if (!target.equals(entry.targetPackage)) throw new IllegalArgumentException("编辑器不能改变其他目标应用的规则");
            combined.add(entry);
        }
        return combined;
    }

    /** Parse only the preview input; matching and priority are delegated to ScopedRuleResolver. */
    public static String previewHost(String source, String url) {
        if (source == null || !RuleConfigRepository.isValidRulePackage(source.trim())) {
            throw new IllegalArgumentException("请选择发起应用、填写完整包名，或用 * 测试剪贴板来源");
        }
        try {
            URI parsed = new URI(url == null ? "" : url.trim());
            if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null) throw new IllegalArgumentException();
            List<String> hosts = RuleConfigRepository.normalizeUserHosts(parsed.getHost());
            if (hosts.size() != 1) throw new IllegalArgumentException();
            return hosts.get(0);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请输入完整的 HTTP(S) URL，例如 https://example.com/article");
        }
    }
}
