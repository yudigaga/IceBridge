package com.huanchengfly.icebridge.automation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import okhttp3.HttpUrl;

/**
 * Synchronous autonomous updater. Run on a dedicated network executor, never a system Hook or
 * configuration worker. It owns no app Context, Provider, database, scheduler or persistence.
 */
public final class SystemOnlineRuleClient {
    private final OnlineRuleTransport transport = new OnlineRuleTransport();

    /** Rebase trusted Provider settings without discarding a newer complete system-side bundle. */
    public static SystemRuntimeConfig.State mergeProvider(SystemRuntimeConfig.State incoming,
                                                          SystemRuntimeConfig.State retained) throws IOException {
        if (incoming == null) throw new IOException("runtime-null");
        if (retained == null || retained.userId != incoming.userId
                || retained.online.highestRevision < incoming.online.highestRevision) return incoming;
        boolean sameRevision = retained.online.highestRevision == incoming.online.highestRevision;
        if (sameRevision && !retained.online.highestSha256.equals(incoming.online.highestSha256)) {
            throw new IOException("online-same-revision-conflict");
        }
        OnlineRuleBundle.Cache retainedCache = cache(retained.online);
        List<LinkRuleEntry> online = new ArrayList<>();
        if (retainedCache.bundle != null
                && (!sameRevision || retainedCache.bundle.revision == incoming.online.highestRevision)) {
            // The full raw bundle retains online rows shadowed by a former manual duplicate.
            online.addAll(retainedCache.bundle.rules);
        } else {
            // At the same revision the freshly read Provider is authoritative if raw is absent
            // (or only an older backup survived). A newer retained high-water mark still wins.
            List<LinkRuleEntry> source = sameRevision ? incoming.scopedRules : retained.scopedRules;
            for (LinkRuleEntry entry : source) {
                if (LinkRuleEntry.SOURCE_ONLINE.equals(entry.source)) online.add(entry);
            }
        }
        return incoming.withOnline(retained.online, mergeRules(incoming.scopedRules, online), incoming.savedAtEpochMillis);
    }

    public Result fetch(SystemRuntimeConfig.State current) throws IOException {
        if (current == null) throw new IOException("runtime-null");
        if (!current.autonomousEnabled || !current.onlineUpdatesEnabled) return new Result(current, null);
        long deadline = System.nanoTime() + OnlineRuleTransport.REFRESH_TIMEOUT_NANOS;
        OnlineRuleBundle downloaded;
        try {
            downloaded = downloadFrom(current.online, OnlineRuleTransport.MANIFEST_URL,
                    Math.min(deadline, System.nanoTime() + OnlineRuleTransport.PRIMARY_TIMEOUT_NANOS));
        } catch (IOException firstFailure) {
            try {
                downloaded = downloadFrom(current.online, OnlineRuleTransport.FALLBACK_MANIFEST_URL, deadline);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(firstFailure);
                throw fallbackFailure;
            }
        }
        return new Result(current, downloaded);
    }

    private OnlineRuleBundle downloadFrom(SystemRuntimeConfig.OnlineState online, HttpUrl manifestUrl,
                                          long deadline) throws IOException {
        OnlineRuleBundle.Manifest manifest = OnlineRuleBundle.parseManifest(
                transport.fetch(manifestUrl, OnlineRuleBundle.MAX_MANIFEST_BYTES, deadline));
        OnlineRuleBundle.checkRevision(manifest, online.highestRevision, online.highestSha256);
        OnlineRuleBundle.Cache cache = cache(online);
        // Metadata-only Provider snapshots deliberately cannot skip downloading the actual bundle.
        if (OnlineRuleBundle.canReuse(manifest, cache)) return cache.bundle;
        OnlineRuleBundle downloaded = OnlineRuleBundle.parse(
                transport.fetch(OnlineRuleTransport.bundleUrl(manifestUrl, manifest.file),
                        OnlineRuleBundle.MAX_BUNDLE_BYTES, deadline), manifest.checksum);
        if (downloaded.revision != manifest.revision) throw new IOException("manifest-bundle-revision-mismatch");
        if (System.nanoTime() >= deadline) throw new IOException("refresh-deadline");
        return downloaded;
    }

    public static final class Result {
        public final SystemRuntimeConfig.State state;
        public final boolean changed;
        private final SystemRuntimeConfig.State original;
        private final OnlineRuleBundle downloaded;

        Result(SystemRuntimeConfig.State current, OnlineRuleBundle downloaded) throws IOException {
            this.original = current;
            this.downloaded = downloaded;
            this.state = downloaded == null ? current
                    : applyDownloaded(current, downloaded, System.currentTimeMillis());
            this.changed = state != current;
        }

        /**
         * Rebase onto the latest configuration immediately before atomic persistence. A manual edit,
         * disabled switch or higher accepted revision arriving during HTTP must take precedence.
         */
        public SystemRuntimeConfig.State applyTo(SystemRuntimeConfig.State latest, long savedAt) throws IOException {
            if (latest == null || latest.userId != original.userId
                    || latest.providerSchemaVersion != original.providerSchemaVersion) {
                throw new IOException("online-result-owner-mismatch");
            }
            if (downloaded == null || !latest.autonomousEnabled || !latest.onlineUpdatesEnabled) return latest;
            return applyDownloaded(latest, downloaded, savedAt);
        }
    }

    /** Pure state transition; a newer bundle replaces the complete online set, including empty. */
    static SystemRuntimeConfig.State applyDownloaded(SystemRuntimeConfig.State current,
                                                      OnlineRuleBundle downloaded, long savedAt) throws IOException {
        OnlineRuleBundle.Manifest manifest = new OnlineRuleBundle.Manifest(
                downloaded.revision, "rules.json", downloaded.checksum);
        // Recheck after HTTP, in particular if Provider published a newer high-water mark meanwhile.
        OnlineRuleBundle.checkRevision(manifest, current.online.highestRevision, current.online.highestSha256);
        String[] merged = mergeRules(current.scopedRules, downloaded.rules);
        OnlineRuleBundle previous = cache(current.online).bundle;
        SystemRuntimeConfig.OnlineState replacement;
        if (downloaded.raw.equals(current.online.activeRaw)
                && downloaded.checksum.equals(current.online.activeSha256)
                && downloaded.revision == current.online.highestRevision
                && current.online.migrated) {
            replacement = current.online;
        } else {
            OnlineRuleBundle backup = previous == null ? downloaded : previous;
            replacement = new SystemRuntimeConfig.OnlineState(downloaded.raw, downloaded.checksum,
                    backup.raw, backup.checksum, downloaded.revision, downloaded.checksum, true);
        }
        if (replacement == current.online && Arrays.equals(current.encodedRules(), merged)) return current;
        return current.withOnline(replacement, merged, savedAt);
    }

    /** A complete replacement must fit together with every manual rule; never truncate an update. */
    static String[] mergeRules(List<LinkRuleEntry> existing, List<LinkRuleEntry> online) throws IOException {
        List<LinkRuleEntry> ordered = new ArrayList<>();
        for (LinkRuleEntry rule : existing) {
            if (LinkRuleEntry.SOURCE_MANUAL.equals(rule.source)) ordered.add(rule);
        }
        ordered.addAll(online);
        LinkedHashMap<String, LinkRuleEntry> unique = new LinkedHashMap<>();
        for (LinkRuleEntry rule : ordered) {
            String key = rule.sourcePackage + '\u0000' + rule.host + '\u0000'
                    + rule.targetPackage + '\u0000' + rule.urlConversion;
            if (!unique.containsKey(key)) {
                unique.put(key, rule);
            }
        }
        try {
            return ScopedRuleResolver.encode(new ArrayList<>(unique.values()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("online-merged-rules", invalid);
        }
    }

    private static OnlineRuleBundle.Cache cache(SystemRuntimeConfig.OnlineState online) {
        return OnlineRuleBundle.selectCache(online.activeRaw, online.activeSha256,
                online.backupRaw, online.backupSha256, online.highestRevision, online.highestSha256, online.migrated);
    }
}
