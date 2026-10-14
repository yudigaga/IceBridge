package com.huanchengfly.icebridge.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure routing and wire format shared by the app, preview, and system_server. */
public final class ScopedRuleResolver {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private ScopedRuleResolver() { }

    public static List<LinkRuleEntry> filterInstalled(List<LinkRuleEntry> rules, Set<String> installedPackages) {
        if (rules == null || installedPackages == null) return Collections.emptyList();
        List<LinkRuleEntry> retained = new ArrayList<>();
        for (LinkRuleEntry rule : rules) {
            if (rule != null && installedPackages.contains(rule.targetPackage)) retained.add(rule);
        }
        return Collections.unmodifiableList(retained);
    }

    public static List<LinkRuleEntry> matchingRules(List<LinkRuleEntry> rules, String sourcePackage, String host) {
        if (rules == null || host == null) return Collections.emptyList();
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        List<LinkRuleEntry> matches = new ArrayList<>();
        for (LinkRuleEntry rule : rulesForSource(rules, sourcePackage)) {
            if (normalizedHost.equals(rule.host) || normalizedHost.endsWith("." + rule.host)) {
                matches.add(rule);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<LinkRuleEntry> rulesForSource(List<LinkRuleEntry> rules, String sourcePackage) {
        if (rules == null) return Collections.emptyList();
        String source = sourcePackage == null ? "*" : sourcePackage;
        List<LinkRuleEntry> matches = new ArrayList<>();
        for (LinkRuleEntry rule : rules) {
            if (rule != null && ("*".equals(rule.sourcePackage) || source.equals(rule.sourcePackage))) matches.add(rule);
        }
        Collections.sort(matches, Comparator.comparingInt(ScopedRuleResolver::originRank)
                .thenComparingInt(rule -> "*".equals(rule.sourcePackage) ? 1 : 0)
                .thenComparingInt(rule -> -rule.host.length()));
        return Collections.unmodifiableList(matches);
    }

    public static List<String> targetPackages(List<LinkRuleEntry> matches) {
        Set<String> targets = new LinkedHashSet<>();
        if (matches != null) for (LinkRuleEntry entry : matches) targets.add(entry.targetPackage);
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }

    /** Only the highest matching priority group may claim a frozen target. */
    public static LinkRuleEntry selectFrozenRule(List<LinkRuleEntry> matches, Set<String> frozenPackages) {
        if (matches == null || matches.isEmpty() || frozenPackages == null) return null;
        LinkRuleEntry first = matches.get(0), selected = null;
        for (LinkRuleEntry rule : matches) {
            if (!samePriority(first, rule)) break;
            if (!frozenPackages.contains(rule.targetPackage)) continue;
            if (selected != null && !selected.targetPackage.equals(rule.targetPackage)) return null;
            if (selected == null) selected = rule;
        }
        return selected;
    }

    private static boolean samePriority(LinkRuleEntry left, LinkRuleEntry right) {
        return originRank(left) == originRank(right)
                && "*".equals(left.sourcePackage) == "*".equals(right.sourcePackage)
                && left.host.length() == right.host.length();
    }

    private static int originRank(LinkRuleEntry rule) {
        return LinkRuleEntry.SOURCE_MANUAL.equals(rule.source) ? 0 : 1;
    }

    public static String[] encode(List<LinkRuleEntry> rules) {
        List<String> encoded = new ArrayList<>();
        if (rules != null) for (LinkRuleEntry rule : rules) {
            check(rule);
            encoded.add(rule.sourcePackage + "|" + rule.host + "|" + rule.targetPackage
                    + "|" + rule.source + "|" + rule.urlConversion);
        }
        String[] result = encoded.toArray(new String[0]);
        decode(result); // Apply the same aggregate bounds before IPC.
        return result;
    }

    public static List<LinkRuleEntry> decode(String[] entries) {
        if (entries == null) return Collections.emptyList();
        if (entries.length > 768) throw new IllegalArgumentException("too-many-scoped-rules");
        List<LinkRuleEntry> result = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.length() > 800) throw new IllegalArgumentException("invalid-scoped-rule");
            String[] fields = entry.split("\\|", -1);
            if (fields.length != 5) throw new IllegalArgumentException("invalid-scoped-fields");
            LinkRuleEntry rule = new LinkRuleEntry(fields[0], fields[1], fields[2], fields[3], fields[4], "");
            // The model normalizes blank sources; a malformed wire value must not become wildcard.
            if (!rule.sourcePackage.equals(fields[0])) throw new IllegalArgumentException("invalid-rule-source");
            check(rule);
            hosts.add(rule.host);
            if (hosts.size() > 96) throw new IllegalArgumentException("too-many-scoped-hosts");
            result.add(rule);
        }
        return Collections.unmodifiableList(result);
    }

    private static void check(LinkRuleEntry rule) {
        if (rule == null || rule.sourcePackage.length() > 255
                || (!"*".equals(rule.sourcePackage) && !PACKAGE.matcher(rule.sourcePackage).matches())
                || rule.host == null || rule.host.length() > 128 || !HOST.matcher(rule.host).matches()
                || rule.targetPackage == null || rule.targetPackage.length() > 255
                || !PACKAGE.matcher(rule.targetPackage).matches()
                || !(LinkRuleEntry.SOURCE_MANUAL.equals(rule.source) || LinkRuleEntry.SOURCE_ONLINE.equals(rule.source))
                || !(LinkRuleEntry.CONVERSION_NONE.equals(rule.urlConversion)
                || LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(rule.urlConversion))) {
            throw new IllegalArgumentException("invalid-scoped-rule");
        }
    }
}
