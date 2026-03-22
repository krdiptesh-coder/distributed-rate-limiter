package rateLimiter;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimiterService {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // Get or create bucket per client
    private TokenBucket getBucket(String clientId) {
        return buckets.computeIfAbsent(clientId,
                id -> new TokenBucket(10, 1)); // capacity=10, refill=5/sec
    }

    public boolean allowRequest(String clientId) {
        TokenBucket bucket = getBucket(clientId);
        return bucket.allowRequest();
    }
}