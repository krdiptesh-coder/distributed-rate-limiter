package rateLimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    private static final int CAPACITY    = 10;
    private static final int REFILL_RATE = 2; // tokens per second

    private TokenBucket bucket;

    @BeforeEach
    void setUp() {
        bucket = new TokenBucket(CAPACITY, REFILL_RATE);
    }

    // ── Correctness ──────────────────────────────────────────────

    @Test
    @DisplayName("Allows exactly capacity requests on a full bucket")
    void testBurstUpToCapacity() {
        int allowed = 0;
        for (int i = 0; i < CAPACITY + 5; i++) {
            if (bucket.allowRequest()) allowed++;
        }
        assertEquals(CAPACITY, allowed,
                "Should allow exactly " + CAPACITY + " requests before denying");
    }

    @Test
    @DisplayName("Denies requests when bucket is empty")
    void testDeniesWhenEmpty() {
        // drain the bucket
        for (int i = 0; i < CAPACITY; i++) bucket.allowRequest();

        assertFalse(bucket.allowRequest(), "Should deny when bucket is empty");
    }

    @Test
    @DisplayName("Refills tokens at the configured rate after waiting")
    void testRefillAfterWait() throws InterruptedException {
        // drain the bucket
        for (int i = 0; i < CAPACITY; i++) bucket.allowRequest();
        assertFalse(bucket.allowRequest(), "Bucket should be empty");

        // wait for 1 second — expect ~2 tokens refilled (refill rate = 2/sec)
        Thread.sleep(1000);

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (bucket.allowRequest()) allowed++;
        }

        assertTrue(allowed >= 1 && allowed <= 3,
                "After 1s with refill rate 2/sec, expected 1-3 tokens, got: " + allowed);
    }

    @Test
    @DisplayName("Never exceeds bucket capacity after long idle period")
    void testCapacityCapAfterLongIdle() throws InterruptedException {
        // drain bucket, wait long enough that uncapped refill would exceed capacity
        for (int i = 0; i < CAPACITY; i++) bucket.allowRequest();
        Thread.sleep(10_000); // 10s wait — would add 20 tokens if uncapped

        int allowed = 0;
        for (int i = 0; i < CAPACITY + 5; i++) {
            if (bucket.allowRequest()) allowed++;
        }

        assertEquals(CAPACITY, allowed,
                "Bucket should not exceed capacity even after long idle");
    }

    @Test
    @DisplayName("Burst then recover pattern works correctly")
    void testBurstThenRecover() throws InterruptedException {
        // burst — exhaust bucket
        for (int i = 0; i < CAPACITY; i++) assertTrue(bucket.allowRequest());
        assertFalse(bucket.allowRequest(), "Should be denied after burst");

        // wait 1 second for refill
        Thread.sleep(1000);
        assertTrue(bucket.allowRequest(), "Should be allowed after refill");
    }

    // ── Concurrency ───────────────────────────────────────────────

    @Test
    @DisplayName("No over-allowance under 100 simultaneous threads")
    void testConcurrentRequests() throws InterruptedException {
        int threadCount = 100;
        AtomicInteger allowed = new AtomicInteger(0);

        ExecutorService pool  = Executors.newFixedThreadPool(threadCount);
        CountDownLatch  ready = new CountDownLatch(threadCount);
        CountDownLatch  start = new CountDownLatch(1);
        CountDownLatch  done  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(); // all threads wait — then fire simultaneously
                    if (bucket.allowRequest()) allowed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();    // wait until all threads are staged
        start.countDown(); // release all at once
        done.await();
        pool.shutdown();

        // Critical assertion: must never allow more than capacity
        assertTrue(allowed.get() <= CAPACITY,
                "Over-allowance detected: " + allowed.get() + " allowed, capacity is " + CAPACITY);

        // Should have allowed exactly capacity (all threads fired simultaneously on a full bucket)
        assertEquals(CAPACITY, allowed.get(),
                "Expected exactly " + CAPACITY + " allowed, got " + allowed.get());
    }

    @Test
    @DisplayName("No deadlock under sustained concurrent load")
    void testNoDeadlockUnderLoad() throws InterruptedException {
        int threadCount  = 20;
        int requestsEach = 50;
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < requestsEach; j++) {
                        bucket.allowRequest();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        boolean finished = done.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "Deadlock detected — threads did not complete within 5 seconds");
    }

    // ── Multi-client isolation ─────────────────────────────────────

    @Test
    @DisplayName("Client A exhausting budget does not affect Client B")
    void testClientIsolation() {
        RateLimiterService service = new RateLimiterService();

        // exhaust client-A
        for (int i = 0; i < CAPACITY + 5; i++) service.allowRequest("client-A");
        assertFalse(service.allowRequest("client-A"), "client-A should be rate limited");

        // client-B should be unaffected
        assertTrue(service.allowRequest("client-B"), "client-B should have its own independent bucket");
    }

    @Test
    @DisplayName("New client gets a full bucket")
    void testNewClientStartsFull() {
        RateLimiterService service = new RateLimiterService();
        int allowed = 0;
        for (int i = 0; i < CAPACITY + 5; i++) {
            if (service.allowRequest("new-client")) allowed++;
        }
        assertEquals(CAPACITY, allowed, "New client should start with a full bucket");
    }
}
