package com.huanchengfly.icebridge.automation;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BoundedExpiringDeduplicatorTest {
    @Test public void duplicatesDoNotExtendWindowAndOldEntriesExpire() {
        BoundedExpiringDeduplicator cache = new BoundedExpiringDeduplicator(250, 2);
        assertTrue(cache.claim("same", 1000));
        assertFalse(cache.claim("same", 1200));
        assertTrue(cache.claim("same", 1250));
        assertEquals(0, cache.size(1500));
    }

    @Test public void capacityAndClockResetStayBounded() {
        BoundedExpiringDeduplicator cache = new BoundedExpiringDeduplicator(250, 2);
        for (int i = 0; i < 100; i++) cache.claim("key" + i, 1000);
        assertEquals(2, cache.size(1000));
        assertEquals(0, cache.size(900));
    }

    @Test public void simultaneousHooksHaveOneClaimant() throws Exception {
        BoundedExpiringDeduplicator cache = new BoundedExpiringDeduplicator(250, 8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            Thread worker = new Thread(() -> {
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                if (cache.claim("same", 1000)) accepted.incrementAndGet();
            });
            workers.add(worker); worker.start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        assertEquals(1, accepted.get());
    }

    @Test public void fingerprintSeparatesUsersAndDoesNotKeepClipboardText() {
        String raw = "private clipboard https://example.com";
        String fingerprint = BoundedExpiringDeduplicator.fingerprint(0, raw);
        assertFalse(fingerprint.contains(raw));
        assertNotEquals(fingerprint, BoundedExpiringDeduplicator.fingerprint(10, raw));
        assertEquals(64, fingerprint.length());
    }
}
