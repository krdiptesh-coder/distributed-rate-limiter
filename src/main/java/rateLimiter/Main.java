package rateLimiter;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Demo 1: Single client — burst then throttle ===\n");
        demoSingleClientBurst();

        Thread.sleep(500);

        System.out.println("\n=== Demo 2: Multiple clients — independent buckets ===\n");
        demoMultipleClients();

        Thread.sleep(500);

        System.out.println("\n=== Demo 3: Concurrent requests — thread safety ===\n");
        demoConcurrency();
    }

    // Demo 1: Show burst capacity then rejection then recovery
    private static void demoSingleClientBurst() throws InterruptedException {
        RateLimiterService rateLimiter = new RateLimiterService();
        String client = "client-A";

        System.out.println("Sending 12 requests instantly (bucket capacity = 10):");
        for (int i = 1; i <= 12; i++) {
            boolean allowed = rateLimiter.allowRequest(client);
            System.out.printf("  Request %2d → %s%n", i, allowed ? "ALLOWED" : "DENIED ← rate limited");
        }

        System.out.println("\nWaiting 1 second for token refill...");
        Thread.sleep(1000);

        System.out.println("\nSending 3 more requests after refill:");
        for (int i = 13; i <= 15; i++) {
            boolean allowed = rateLimiter.allowRequest(client);
            System.out.printf("  Request %2d → %s%n", i, allowed ? "ALLOWED" : "DENIED");
        }
    }

    // Demo 2: Show client A exhausting budget doesn't affect client B
    private static void demoMultipleClients() throws InterruptedException {
        RateLimiterService rateLimiter = new RateLimiterService();

        System.out.println("Exhausting client-A's bucket:");
        for (int i = 1; i <= 11; i++) {
            boolean allowed = rateLimiter.allowRequest("client-A");
            System.out.printf("  [client-A] Request %2d → %s%n", i, allowed ? "ALLOWED" : "DENIED");
        }

        System.out.println("\nclient-B makes requests (independent bucket — should all be allowed):");
        for (int i = 1; i <= 5; i++) {
            boolean allowed = rateLimiter.allowRequest("client-B");
            System.out.printf("  [client-B] Request %2d → %s%n", i, allowed ? "ALLOWED" : "DENIED");
        }
    }

    // Demo 3: 50 threads fire simultaneously — prove no over-allowance
    private static void demoConcurrency() throws InterruptedException {
        RateLimiterService rateLimiter = new RateLimiterService();
        String client = "client-concurrent";

        int threadCount = 50;
        int bucketCapacity = 10; // hardcoded in RateLimiterService

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied  = new AtomicInteger(0);

        ExecutorService pool  = Executors.newFixedThreadPool(threadCount);
        CountDownLatch  ready = new CountDownLatch(threadCount);
        CountDownLatch  start = new CountDownLatch(1);
        CountDownLatch  done  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(); // all threads wait here — fire simultaneously
                    if (rateLimiter.allowRequest(client)) allowed.incrementAndGet();
                    else                                   denied.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();   // wait until all threads are ready
        start.countDown(); // fire all at once
        done.await();

        pool.shutdown();

        System.out.printf("  Threads fired simultaneously : %d%n", threadCount);
        System.out.printf("  Bucket capacity              : %d%n", bucketCapacity);
        System.out.printf("  Allowed                      : %d%n", allowed.get());
        System.out.printf("  Denied                       : %d%n", denied.get());
        System.out.printf("  Over-allowance               : %s%n",
                allowed.get() <= bucketCapacity ? "NONE — thread safety verified" : "BUG DETECTED");
    }
}
