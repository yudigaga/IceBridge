package com.huanchengfly.icebridge.automation;

import java.util.Objects;

/** One auditable source-to-target routing rule. */
public final class LinkRuleEntry {
    public static final String SOURCE_BUILT_IN = "built_in";
    public static final String SOURCE_ONLINE = "online";
    public static final String SOURCE_MANUAL = "manual";
    public static final String CONVERSION_NONE = "none";
    public static final String CONVERSION_FIXED_DEEP_LINK = "fixed_deep_link";

    /** Wildcard keeps rules created before source-app editing compatible. */
    public final String sourcePackage;
    public final String host;
    public final String targetPackage;
    public final String source;
    public final String urlConversion;
    public final String note;

    public LinkRuleEntry(String host, String targetPackage, String source, String urlConversion, String note) {
        this("*", host, targetPackage, source, urlConversion, note);
    }

    public LinkRuleEntry(String sourcePackage, String host, String targetPackage, String source,
                         String urlConversion, String note) {
        this.sourcePackage = sourcePackage == null || sourcePackage.trim().isEmpty() ? "*" : sourcePackage.trim();
        this.host = host;
        this.targetPackage = targetPackage;
        this.source = source;
        this.urlConversion = urlConversion == null ? CONVERSION_NONE : urlConversion;
        this.note = note == null ? "" : note;
    }

    public boolean hasFixedUrlConversion() { return CONVERSION_FIXED_DEEP_LINK.equals(urlConversion); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LinkRuleEntry)) return false;
        LinkRuleEntry value = (LinkRuleEntry) other;
        return Objects.equals(sourcePackage, value.sourcePackage) && Objects.equals(host, value.host)
                && Objects.equals(targetPackage, value.targetPackage) && Objects.equals(source, value.source)
                && Objects.equals(urlConversion, value.urlConversion) && Objects.equals(note, value.note);
    }

    @Override public int hashCode() { return Objects.hash(sourcePackage, host, targetPackage, source, urlConversion, note); }
}
