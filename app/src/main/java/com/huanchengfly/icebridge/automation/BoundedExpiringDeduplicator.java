package com.huanchengfly.icebridge.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A fixed window from the first claimant, with atomic claims and bounded retained identities. */
public final class BoundedExpiringDeduplicator {
    private final long ttlMillis;
    private final int capacity;
    private final LinkedHashMap<String, Long> claimed = new LinkedHashMap<>();

    public BoundedExpiringDeduplicator(long ttlMillis, int capacity) {
        if (ttlMillis <= 0 || capacity <= 0) throw new IllegalArgumentException("positive bounds required");
        this.ttlMillis = ttlMillis;
        this.capacity = capacity;
    }

    public synchronized boolean claim(String key, long now) {
        prune(now);
        if (claimed.containsKey(key)) return false;
        while (claimed.size() >= capacity) claimed.remove(claimed.keySet().iterator().next());
        claimed.put(key, now);
        return true;
    }

    public synchronized int size(long now) { prune(now); return claimed.size(); }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> iterator = claimed.entrySet().iterator();
        while (iterator.hasNext()) {
            long timestamp = iterator.next().getValue();
            if (now < timestamp || now - timestamp >= ttlMillis) iterator.remove();
        }
    }

    public static String fingerprint(int userId, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + value).getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[2 * i] = alphabet[(digest[i] & 0xff) >>> 4];
                hex[2 * i + 1] = alphabet[digest[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
