package rateLimiter;

import java.util.concurrent.locks.ReentrantLock;

public class TokenBucket {

    private final int capacity;          // max tokens
    private final int refillRate;        // tokens per second
    private double tokens;               // current tokens
    private long lastRefillTime;         // last refill timestamp

    private final ReentrantLock lock = new ReentrantLock();

    public TokenBucket(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }

    // Try to consume 1 token
    public boolean allowRequest() {
        lock.lock(); // ensure thread safety
        try {
            refill();

            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;

        } finally {
            lock.unlock();
        }
    }

    // Refill tokens based on time passed
    private void refill() {
        long now = System.currentTimeMillis();
        double tokensToAdd = ((now - lastRefillTime) / 1000.0) * refillRate;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}