package com.huanchengfly.icebridge.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, declarative online format. No executable expressions or intent payloads are accepted. */
final class OnlineRuleBundle {
    static final int MAX_MANIFEST_BYTES = 16 * 1024;
    static final int MAX_BUNDLE_BYTES = 1024 * 1024;
    static final int MAX_RULES = 768;
    static final int MAX_HOSTS = 96;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-]+(?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?(?:/[A-Za-z0-9_-]+(?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)*\\.json");

    final String raw;
    final String checksum;
    final long revision;
    final List<LinkRuleEntry> rules;

    private OnlineRuleBundle(String raw, String checksum, long revision, List<LinkRuleEntry> rules) {
        this.raw = raw;
        this.checksum = checksum;
        this.revision = revision;
        this.rules = Collections.unmodifiableList(rules);
    }

    static Manifest parseManifest(String raw) throws IOException {
        JsonObject object = object(raw, MAX_MANIFEST_BYTES);
        fields(object, "format", "version", "revision", "file", "sha256");
        require("icebridge-rule-manifest".equals(string(object, "format")), "manifest-format");
        require(positiveInteger(object, "version") == 1, "manifest-version");
        long revision = positiveInteger(object, "revision");
        String file = string(object, "file");
        require(file.length() <= 256 && PATH.matcher(file).matches()
                && !file.contains(".."), "manifest-file");
        String hash = string(object, "sha256");
        require(HASH.matcher(hash).matches(), "manifest-checksum");
        return new Manifest(revision, file, hash.toLowerCase(Locale.ROOT));
    }

    static OnlineRuleBundle parse(String raw, String expectedChecksum) throws IOException {
        require(expectedChecksum != null && HASH.matcher(expectedChecksum).matches(), "bundle-checksum");
        require(raw != null && raw.length() <= MAX_BUNDLE_BYTES, "bundle-size");
        require(checksumMatches(raw, expectedChecksum), "bundle-checksum-mismatch");
        JsonObject object = object(raw, MAX_BUNDLE_BYTES);
        fields(object, "format", "version", "revision", "rules", "provenance");
        require("icebridge-online-rules".equals(string(object, "format")), "bundle-format");
        require(positiveInteger(object, "version") == 1, "bundle-version");
        long revision = positiveInteger(object, "revision");
        if (object.has("provenance")) require(object.get("provenance").isJsonObject(), "bundle-provenance");
        JsonElement array = object.get("rules");
        require(array != null && array.isJsonArray(), "bundle-rules");
        require(array.getAsJsonArray().size() <= MAX_RULES, "bundle-rule-limit");
        List<LinkRuleEntry> result = new ArrayList<>();
        Set<String> hosts = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (JsonElement item : array.getAsJsonArray()) {
            require(item.isJsonObject(), "rule-object");
            JsonObject rule = item.getAsJsonObject();
            fields(rule, "sourcePackage", "host", "package", "conversion", "note");
            String source = string(rule, "sourcePackage");
            require("*".equals(source) || validPackage(source), "rule-source");
            String host = string(rule, "host");
            require(host.length() <= 128 && HOST.matcher(host).matches(), "rule-host");
            String target = string(rule, "package");
            require(validPackage(target), "rule-package");
            String conversion = string(rule, "conversion");
            require(LinkRuleEntry.CONVERSION_NONE.equals(conversion)
                    || LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(conversion), "rule-conversion");
            String note = string(rule, "note");
            require(note.length() <= 512, "rule-note");
            hosts.add(host);
            require(hosts.size() <= MAX_HOSTS, "bundle-host-limit");
            require(identities.add(source + '\u0000' + host + '\u0000' + target + '\u0000' + conversion), "rule-duplicate");
            result.add(new LinkRuleEntry(source, host, target, LinkRuleEntry.SOURCE_ONLINE, conversion, note));
        }
        // An explicit empty array is a valid withdrawal of every online rule.
        return new OnlineRuleBundle(raw, expectedChecksum.toLowerCase(Locale.ROOT), revision, result);
    }

    /** Highest accepted revision is independent of cache recovery, so a backup cannot reset it. */
    static void checkRevision(Manifest manifest, long highestRevision, String highestChecksum) throws IOException {
        require(highestRevision >= 0, "revision-state-invalid");
        if (highestRevision > 0) require(highestChecksum != null && HASH.matcher(highestChecksum).matches(), "revision-state-invalid");
        require(manifest.revision >= highestRevision, "revision-rollback");
        if (manifest.revision == highestRevision) {
            require(manifest.checksum.equalsIgnoreCase(highestChecksum), "revision-content-changed");
        }
    }

    static Cache selectCache(String primary, String primaryHash, String backup, String backupHash,
                             long highestRevision, String highestHash, boolean migrated) {
        // Presence of any owned-source state also closes the legacy migration path, even if a
        // preference is corrupt. Never resurrect an obsolete third-party cache after migration.
        boolean owned = migrated || highestRevision != 0 || present(primary) || present(backup)
                || present(primaryHash) || present(backupHash) || present(highestHash);
        OnlineRuleBundle active = validCache(primary, primaryHash, highestRevision, highestHash, true);
        if (active != null) return new Cache(active, false, false);
        OnlineRuleBundle previous = validCache(backup, backupHash, highestRevision, highestHash, false);
        if (previous != null) return new Cache(previous, true, false);
        return new Cache(null, false, !owned);
    }

    private static OnlineRuleBundle validCache(String raw, String hash, long highest, String highestHash, boolean active) {
        if (!present(raw) || highest <= 0 || highestHash == null || !HASH.matcher(highestHash).matches()) return null;
        try {
            OnlineRuleBundle bundle = parse(raw, hash);
            if (bundle.revision > highest || (active && bundle.revision != highest)) return null;
            if (bundle.revision == highest && !bundle.checksum.equalsIgnoreCase(highestHash)) return null;
            return bundle;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean canReuse(Manifest manifest, Cache cache) {
        return cache.bundle != null && cache.bundle.revision == manifest.revision
                && cache.bundle.checksum.equals(manifest.checksum);
    }

    /** Shared by app and injected processes without initializing the app repository. */
    static boolean checksumMatches(String raw, String expected) {
        return raw != null && expected != null && !expected.isEmpty()
                && expected.equalsIgnoreCase(sha256(raw));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(Character.forDigit((item >>> 4) & 15, 16));
                result.append(Character.forDigit(item & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("sha256-unavailable", error);
        }
    }

    private static boolean present(String value) { return value != null && !value.isEmpty(); }
    private static boolean validPackage(String value) { return value.length() <= 255 && PACKAGE.matcher(value).matches(); }
    private static void require(boolean value, String reason) throws IOException { if (!value) throw new IOException(reason); }
    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "field-string-" + name);
        return value.getAsString();
    }

    private static long positiveInteger(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "field-integer-" + name);
        try {
            long result = value.getAsBigDecimal().longValueExact();
            require(result > 0, "field-positive-" + name);
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IOException("field-integer-" + name, error);
        }
    }

    private static void fields(JsonObject object, String... names) throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        for (String key : object.keySet()) require(allowed.contains(key), "unknown-field-" + key);
    }

    /** JsonParser is deliberately avoided: Gson 2.x temporarily enables lenient parsing there. */
    private static JsonObject object(String raw, int maxBytes) throws IOException {
        require(raw != null && raw.length() <= maxBytes
                && raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes, "json-size");
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false);
            JsonElement parsed = readStrict(reader, 0, new int[]{0});
            require(reader.peek() == JsonToken.END_DOCUMENT && parsed.isJsonObject(), "json-object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("json-invalid", error);
        }
    }

    private static JsonElement readStrict(JsonReader reader, int depth, int[] count) throws IOException {
        require(depth <= 16 && ++count[0] <= 16000, "json-complexity");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!object.has(name), "json-duplicate-key");
                    object.add(name, readStrict(reader, depth + 1, count));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readStrict(reader, depth + 1, count));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("json-token");
        }
    }

    static final class Manifest {
        final long revision;
        final String file;
        final String checksum;
        Manifest(long revision, String file, String checksum) { this.revision = revision; this.file = file; this.checksum = checksum; }
    }

    static final class Cache {
        final OnlineRuleBundle bundle;
        final boolean usingBackup;
        final boolean allowLegacy;
        Cache(OnlineRuleBundle bundle, boolean usingBackup, boolean allowLegacy) {
            this.bundle = bundle;
            this.usingBackup = usingBackup;
            this.allowLegacy = allowLegacy;
        }
    }
}
