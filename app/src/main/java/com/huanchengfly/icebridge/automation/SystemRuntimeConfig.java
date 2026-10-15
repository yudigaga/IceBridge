package com.huanchengfly.icebridge.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure, bounded codec for configuration explicitly retained by system_server. No package state. */
public final class SystemRuntimeConfig {
    public static final int VERSION = 1;
    // Two 1 MB online bundles may grow when represented as JSON strings, plus scoped rules.
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String FORMAT = "icebridge-system-runtime";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private SystemRuntimeConfig() { }

    public static final class State {
        public final int providerSchemaVersion;
        public final int userId;
        public final long savedAtEpochMillis;
        public final boolean autonomousEnabled;
        public final boolean recoveryEnabled;
        public final boolean autoOpenLinks;
        public final boolean nativeLinkCapture;
        public final boolean clipboardAutoOpen;
        public final boolean paymentSdkBridge;
        public final boolean onlyFrozen;
        public final boolean browserFallback;
        public final boolean onlineUpdatesEnabled;
        public final List<LinkRuleEntry> scopedRules;
        public final OnlineState online;

        public State(int providerSchemaVersion, int userId, long savedAtEpochMillis,
                     boolean autonomousEnabled, boolean recoveryEnabled, boolean autoOpenLinks,
                     boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge,
                     boolean onlyFrozen, boolean browserFallback, boolean onlineUpdatesEnabled, String[] scopedRules,
                     OnlineState online) throws IOException {
            checkUserId(userId);
            require(providerSchemaVersion > 0 && providerSchemaVersion <= 10000, "runtime-provider-schema");
            require(savedAtEpochMillis >= 0, "runtime-saved-at");
            this.providerSchemaVersion = providerSchemaVersion;
            this.userId = userId;
            this.savedAtEpochMillis = savedAtEpochMillis;
            this.autonomousEnabled = autonomousEnabled;
            this.recoveryEnabled = recoveryEnabled;
            this.autoOpenLinks = autoOpenLinks;
            this.nativeLinkCapture = nativeLinkCapture;
            this.clipboardAutoOpen = clipboardAutoOpen;
            this.paymentSdkBridge = paymentSdkBridge;
            this.onlyFrozen = onlyFrozen;
            this.browserFallback = browserFallback;
            this.onlineUpdatesEnabled = onlineUpdatesEnabled;
            try {
                this.scopedRules = ScopedRuleResolver.decode(scopedRules == null ? null : scopedRules.clone());
            } catch (IllegalArgumentException error) {
                throw new IOException("runtime-scoped-rules", error);
            }
            this.online = online == null ? OnlineState.empty() : online;
        }

        public String[] encodedRules() { return ScopedRuleResolver.encode(scopedRules); }

        /** Keeps manual/source-specific rules intact while replacing only the validated online cache. */
        public State withOnline(OnlineState replacement, String[] mergedRules, long savedAt) throws IOException {
            return new State(providerSchemaVersion, userId, savedAt, autonomousEnabled,
                    recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                    paymentSdkBridge, onlyFrozen, browserFallback, onlineUpdatesEnabled, mergedRules, replacement);
        }
    }

    /** Fixed extension slot; raw data, revision high-water mark and backup are saved atomically. */
    public static final class OnlineState {
        public final String activeRaw;
        public final String activeSha256;
        public final String backupRaw;
        public final String backupSha256;
        public final long highestRevision;
        public final String highestSha256;
        public final boolean migrated;

        public OnlineState(String activeRaw, String activeSha256, String backupRaw, String backupSha256,
                           long highestRevision, String highestSha256, boolean migrated) throws IOException {
            this.activeRaw = nonNull(activeRaw);
            this.activeSha256 = nonNull(activeSha256).toLowerCase(Locale.ROOT);
            this.backupRaw = nonNull(backupRaw);
            this.backupSha256 = nonNull(backupSha256).toLowerCase(Locale.ROOT);
            this.highestRevision = highestRevision;
            this.highestSha256 = nonNull(highestSha256).toLowerCase(Locale.ROOT);
            this.migrated = migrated;
            require(highestRevision >= 0, "runtime-online-revision");
            if (highestRevision == 0) {
                require(this.highestSha256.isEmpty() && this.activeRaw.isEmpty()
                        && this.activeSha256.isEmpty() && this.backupRaw.isEmpty()
                        && this.backupSha256.isEmpty(), "runtime-online-empty-state");
            } else {
                require(HASH.matcher(this.highestSha256).matches(), "runtime-online-highest-hash");
                validateBundle(this.activeRaw, this.activeSha256, true);
                validateBundle(this.backupRaw, this.backupSha256, false);
            }
        }

        public static OnlineState empty() {
            try { return new OnlineState("", "", "", "", 0, "", false); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
        }

        private void validateBundle(String raw, String checksum, boolean active) throws IOException {
            if (raw.isEmpty()) {
                require(checksum.isEmpty(), "runtime-online-orphan-hash");
                return;
            }
            require(raw.length() <= OnlineRuleBundle.MAX_BUNDLE_BYTES
                    && raw.getBytes(StandardCharsets.UTF_8).length <= OnlineRuleBundle.MAX_BUNDLE_BYTES,
                    "runtime-online-size");
            OnlineRuleBundle bundle = OnlineRuleBundle.parse(raw, checksum);
            require(bundle.revision <= highestRevision
                    && (!active || bundle.revision == highestRevision), "runtime-online-cache-revision");
            require(bundle.revision != highestRevision || bundle.checksum.equals(highestSha256),
                    "runtime-online-cache-hash");
        }
    }

    public static byte[] encode(State state) throws IOException {
        require(state != null, "runtime-null");
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("providerSchemaVersion", state.providerSchemaVersion);
        root.addProperty("userId", state.userId);
        root.addProperty("savedAtEpochMillis", state.savedAtEpochMillis);
        root.addProperty("autonomousEnabled", state.autonomousEnabled);
        root.addProperty("recoveryEnabled", state.recoveryEnabled);
        root.addProperty("autoOpenLinks", state.autoOpenLinks);
        root.addProperty("nativeLinkCapture", state.nativeLinkCapture);
        root.addProperty("clipboardAutoOpen", state.clipboardAutoOpen);
        root.addProperty("paymentSdkBridge", state.paymentSdkBridge);
        root.addProperty("onlyFrozen", state.onlyFrozen);
        root.addProperty("browserFallback", state.browserFallback);
        root.addProperty("onlineUpdatesEnabled", state.onlineUpdatesEnabled);
        JsonArray rules = new JsonArray();
        for (String rule : state.encodedRules()) rules.add(new JsonPrimitive(rule));
        root.add("scopedRules", rules);
        JsonObject online = new JsonObject();
        online.addProperty("activeRaw", state.online.activeRaw);
        online.addProperty("activeSha256", state.online.activeSha256);
        online.addProperty("backupRaw", state.online.backupRaw);
        online.addProperty("backupSha256", state.online.backupSha256);
        online.addProperty("highestRevision", state.online.highestRevision);
        online.addProperty("highestSha256", state.online.highestSha256);
        online.addProperty("migrated", state.online.migrated);
        root.add("online", online);
        byte[] result = root.toString().getBytes(StandardCharsets.UTF_8);
        require(result.length <= MAX_BYTES, "runtime-size");
        return result;
    }

    /** Logical Provider configuration identity; timestamps and retained raw-cache bytes are excluded. */
    public static String fingerprint(State state) throws IOException {
        require(state != null, "runtime-null");
        OnlineState metadata = new OnlineState("", "", "", "", state.online.highestRevision,
                state.online.highestSha256, state.online.highestRevision > 0);
        State baseline = new State(state.providerSchemaVersion, state.userId, 0, state.autonomousEnabled,
                state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture, state.clipboardAutoOpen,
                state.paymentSdkBridge, state.onlyFrozen, state.browserFallback, state.onlineUpdatesEnabled,
                state.encodedRules(), metadata);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encode(baseline));
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("runtime-sha256-unavailable", impossible); }
    }

    public static State decode(byte[] bytes, int expectedUserId, int expectedProviderSchema) throws IOException {
        checkUserId(expectedUserId);
        require(bytes != null && bytes.length <= MAX_BYTES, "runtime-size");
        final String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("runtime-utf8", error);
        }
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false);
            JsonElement element = readStrict(reader, 0, new int[]{0});
            require(reader.peek() == JsonToken.END_DOCUMENT && element.isJsonObject(), "runtime-json-object");
            JsonObject root = element.getAsJsonObject();
            fields(root, "format", "version", "providerSchemaVersion", "userId", "savedAtEpochMillis",
                    "autonomousEnabled", "recoveryEnabled", "autoOpenLinks", "nativeLinkCapture",
                    "clipboardAutoOpen", "paymentSdkBridge", "onlyFrozen", "browserFallback", "onlineUpdatesEnabled", "scopedRules", "online");
            require(FORMAT.equals(string(root, "format", 64)), "runtime-format");
            require(integer(root, "version") == VERSION, "runtime-version");
            long userId = integer(root, "userId");
            require(userId == expectedUserId, "runtime-user-mismatch");
            long schema = integer(root, "providerSchemaVersion");
            require(schema == expectedProviderSchema, "runtime-provider-schema-mismatch");
            JsonElement rawRules = root.get("scopedRules");
            require(rawRules != null && rawRules.isJsonArray() && rawRules.getAsJsonArray().size() <= 768,
                    "runtime-scoped-rules");
            String[] rules = new String[rawRules.getAsJsonArray().size()];
            for (int i = 0; i < rules.length; i++) {
                JsonElement rule = rawRules.getAsJsonArray().get(i);
                require(rule.isJsonPrimitive() && rule.getAsJsonPrimitive().isString()
                        && rule.getAsString().length() <= 800, "runtime-scoped-rule");
                rules[i] = rule.getAsString();
            }
            JsonElement onlineElement = root.get("online");
            require(onlineElement != null && onlineElement.isJsonObject(), "runtime-online-object");
            JsonObject online = onlineElement.getAsJsonObject();
            fields(online, "activeRaw", "activeSha256", "backupRaw", "backupSha256", "highestRevision", "highestSha256", "migrated");
            OnlineState cache = new OnlineState(string(online, "activeRaw", OnlineRuleBundle.MAX_BUNDLE_BYTES),
                    string(online, "activeSha256", 64), string(online, "backupRaw", OnlineRuleBundle.MAX_BUNDLE_BYTES),
                    string(online, "backupSha256", 64), integer(online, "highestRevision"),
                    string(online, "highestSha256", 64), bool(online, "migrated"));
            return new State((int) schema, (int) userId, integer(root, "savedAtEpochMillis"),
                    bool(root, "autonomousEnabled"), bool(root, "recoveryEnabled"), bool(root, "autoOpenLinks"),
                    bool(root, "nativeLinkCapture"), bool(root, "clipboardAutoOpen"), bool(root, "paymentSdkBridge"),
                    bool(root, "onlyFrozen"), bool(root, "browserFallback"), bool(root, "onlineUpdatesEnabled"), rules, cache);
        } catch (RuntimeException error) {
            throw new IOException("runtime-json", error);
        }
    }

    /** Never allocates an unbounded file, even if its metadata changes during the read. */
    public static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            require(count <= MAX_BYTES - output.size(), "runtime-size");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static void checkUserId(int userId) throws IOException {
        require(userId >= 0 && userId <= 999, "runtime-user-id");
    }

    private static String nonNull(String value) { return value == null ? "" : value; }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static String string(JsonObject object, String key, int maxLength) throws IOException {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "runtime-string-" + key);
        String result = value.getAsString();
        require(result.length() <= maxLength, "runtime-string-size-" + key);
        return result;
    }

    private static long integer(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "runtime-integer-" + key);
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw new IOException("runtime-integer-" + key, error); }
    }

    private static boolean bool(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "runtime-boolean-" + key);
        return value.getAsBoolean();
    }

    private static void fields(JsonObject object, String... keys) throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList(keys));
        for (String key : object.keySet()) require(allowed.contains(key), "runtime-unknown-field-" + key);
    }

    private static JsonElement readStrict(JsonReader reader, int depth, int[] count) throws IOException {
        require(depth <= 8 && ++count[0] <= 4096, "runtime-json-complexity");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    require(key.length() <= 64 && !object.has(key), "runtime-json-key");
                    object.add(key, readStrict(reader, depth + 1, count));
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
            case NUMBER:
                String number = reader.nextString();
                require(number.length() <= 20, "runtime-json-number");
                return new JsonPrimitive(new BigDecimal(number));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("runtime-json-token");
        }
    }
}
