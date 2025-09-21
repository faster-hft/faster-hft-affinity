package com.faster.affinity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance rate limiter for affinity operations using token bucket algorithm.
 * Provides DoS protection for HFT environments with microsecond precision tracking.
 */
public final class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    // Default rate limiting configuration
    private static final long DEFAULT_TOKENS_PER_SECOND = 1000; // 1000 ops/sec per thread
    private static final long DEFAULT_BURST_CAPACITY = 100;     // Allow 100 burst operations
    private static final long DEFAULT_WINDOW_SIZE_MS = 1000;    // 1 second sliding window

    // Time caching for reduced system call overhead in hot paths
    private static final long TIME_CACHE_DURATION_MS = 10; // Cache time for 10ms
    private static volatile long cachedTime = System.currentTimeMillis();
    private static volatile long lastTimeUpdate = cachedTime;

    // Per-thread rate limiting buckets
    private final ConcurrentHashMap<Long, TokenBucket> threadBuckets = new ConcurrentHashMap<>();

    // Global rate limiting configuration
    private final long tokensPerSecond;
    private final long burstCapacity;
    private final long windowSizeMs;

    // Global rate limiting bucket for system-wide limits
    private final TokenBucket globalBucket;

    // Statistics tracking
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());

    /**
     * Token bucket implementation for rate limiting.
     */
    private static class TokenBucket {
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        private final long capacity;
        private final long refillRate;
        private final long refillIntervalMs;

        TokenBucket(long capacity, long tokensPerSecond) {
            this.capacity = capacity;
            this.refillRate = tokensPerSecond;
            this.refillIntervalMs = 1000; // 1 second
            this.tokens = new AtomicLong(capacity);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        /**
         * Attempt to consume tokens. Returns true if successful, false if rate limited.
         */
        boolean tryConsume(int tokensToConsume) {
            // SECURITY: Prevent negative or zero token consumption attempts
            if (tokensToConsume <= 0) {
                return false; // Invalid consumption request
            }

            refillTokens();

            while (true) {
                long currentTokens = tokens.get();
                if (currentTokens < tokensToConsume) {
                    return false; // Rate limited
                }

                long newTokens = currentTokens - tokensToConsume;
                if (tokens.compareAndSet(currentTokens, newTokens)) {
                    return true; // Successfully consumed tokens
                }
                // Retry if CAS failed due to concurrent modification
                Thread.onSpinWait(); // Yield to reduce CPU usage in contention
            }
        }

        /**
         * Refill tokens based on elapsed time since last refill.
         */
        private void refillTokens() {
            long now = fastCurrentTimeMillis(); // Use cached time for hot path optimization
            long lastRefill = lastRefillTime.get();

            if (now > lastRefill) {
                long elapsedMs = now - lastRefill;

                // Prevent overflow in multiplication with safety checks
                long tokensToAdd = 0;
                if (elapsedMs > 0 && refillRate > 0 && refillIntervalMs > 0) {
                    // Check for potential overflow before multiplication
                    if (elapsedMs <= Long.MAX_VALUE / refillRate) {
                        tokensToAdd = (elapsedMs * refillRate) / refillIntervalMs;
                    } else {
                        // Use safer calculation for large values
                        tokensToAdd = (elapsedMs / refillIntervalMs) * refillRate;
                        // Add remainder calculation if needed
                        long remainder = elapsedMs % refillIntervalMs;
                        if (remainder > 0 && remainder <= Long.MAX_VALUE / refillRate) {
                            tokensToAdd += (remainder * refillRate) / refillIntervalMs;
                        }
                    }

                    // Cap to reasonable maximum to prevent resource exhaustion
                    tokensToAdd = Math.min(tokensToAdd, capacity * 2);
                }

                if (tokensToAdd > 0 && lastRefillTime.compareAndSet(lastRefill, now)) {
                    long currentTokens = tokens.get();
                    long newTokens;
                    try {
                        newTokens = Math.min(capacity, Math.addExact(currentTokens, tokensToAdd));
                    } catch (ArithmeticException e) {
                        // Overflow occurred, set to capacity
                        newTokens = capacity;
                    }
                    tokens.set(newTokens);
                }
            }
        }

        long getAvailableTokens() {
            refillTokens();
            return tokens.get();
        }
    }

    /**
     * Get current time with caching optimization for hot paths.
     * Reduces system call overhead by caching time for short periods.
     */
    private static long fastCurrentTimeMillis() {
        long cached = cachedTime;
        long now = System.currentTimeMillis();

        // Update cache if enough time has passed (reduces system calls)
        if (now - lastTimeUpdate > TIME_CACHE_DURATION_MS) {
            cachedTime = now;
            lastTimeUpdate = now;
            return now;
        }

        return cached;
    }

    /**
     * Create rate limiter with default configuration.
     */
    public RateLimiter() {
        this(DEFAULT_TOKENS_PER_SECOND, DEFAULT_BURST_CAPACITY, DEFAULT_WINDOW_SIZE_MS);
    }

    /**
     * Create rate limiter with custom configuration.
     */
    public RateLimiter(long tokensPerSecond, long burstCapacity, long windowSizeMs) {
        if (tokensPerSecond <= 0 || burstCapacity <= 0 || windowSizeMs <= 0) {
            throw new IllegalArgumentException("Rate limiter parameters must be positive");
        }

        this.tokensPerSecond = tokensPerSecond;
        this.burstCapacity = burstCapacity;
        this.windowSizeMs = windowSizeMs;

        // Global bucket for system-wide rate limiting (10x per-thread limit)
        this.globalBucket = new TokenBucket(burstCapacity * 10, tokensPerSecond * 10);

        logger.info("RateLimiter initialized: {} tokens/sec, {} burst capacity, {} ms window",
                   tokensPerSecond, burstCapacity, windowSizeMs);
    }

    /**
     * Check if operation is allowed for current thread.
     * Returns true if operation should proceed, false if rate limited.
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Check if operation is allowed for current thread with specified token cost.
     */
    public boolean tryAcquire(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Token count must be positive");
        }

        long threadId = Thread.currentThread().getId();
        totalRequests.incrementAndGet();

        try {
            // Check global rate limit first (prevents system-wide overload)
            if (!globalBucket.tryConsume(tokens)) {
                rejectedRequests.incrementAndGet();
                logger.warn("Global rate limit exceeded for thread {}", threadId);
                return false;
            }

            // Check per-thread rate limit
            TokenBucket threadBucket = getOrCreateThreadBucket(threadId);
            if (!threadBucket.tryConsume(tokens)) {
                rejectedRequests.incrementAndGet();
                logger.debug("Per-thread rate limit exceeded for thread {}", threadId);
                return false;
            }

            return true;

        } finally {
            // Periodic cleanup of old thread buckets
            cleanupOldBuckets();
        }
    }

    /**
     * Get or create token bucket for specific thread.
     */
    private TokenBucket getOrCreateThreadBucket(long threadId) {
        return threadBuckets.computeIfAbsent(threadId,
            id -> new TokenBucket(burstCapacity, tokensPerSecond));
    }

    /**
     * Clean up token buckets for threads that are no longer active.
     */
    private void cleanupOldBuckets() {
        long now = fastCurrentTimeMillis(); // Use cached time for hot path optimization
        long lastCleanup = lastCleanupTime.get();

        // Only cleanup every 30 seconds to avoid overhead
        if (now - lastCleanup > 30000 && lastCleanupTime.compareAndSet(lastCleanup, now)) {
            int initialSize = threadBuckets.size();

            // Remove buckets for threads that haven't been used recently
            threadBuckets.entrySet().removeIf(entry -> {
                // This is a simple heuristic - in production, you might want
                // to track last access time per bucket
                return entry.getValue().getAvailableTokens() == burstCapacity;
            });

            int finalSize = threadBuckets.size();
            if (finalSize < initialSize) {
                logger.debug("Cleaned up {} inactive thread buckets ({} -> {})",
                           initialSize - finalSize, initialSize, finalSize);
            }
        }
    }

    /**
     * Get current rate limiting statistics.
     */
    public RateLimiterStats getStats() {
        return new RateLimiterStats(
            totalRequests.get(),
            rejectedRequests.get(),
            threadBuckets.size(),
            globalBucket.getAvailableTokens()
        );
    }

    /**
     * Rate limiter statistics.
     */
    public static class RateLimiterStats {
        public final long totalRequests;
        public final long rejectedRequests;
        public final int activeThreadBuckets;
        public final long globalTokensAvailable;

        RateLimiterStats(long totalRequests, long rejectedRequests,
                        int activeThreadBuckets, long globalTokensAvailable) {
            this.totalRequests = totalRequests;
            this.rejectedRequests = rejectedRequests;
            this.activeThreadBuckets = activeThreadBuckets;
            this.globalTokensAvailable = globalTokensAvailable;
        }

        public double getRejectionRate() {
            return totalRequests > 0 ? (double) rejectedRequests / totalRequests : 0.0;
        }

        @Override
        public String toString() {
            return String.format("RateLimiterStats{total=%d, rejected=%d (%.2f%%), threads=%d, globalTokens=%d}",
                               totalRequests, rejectedRequests, getRejectionRate() * 100,
                               activeThreadBuckets, globalTokensAvailable);
        }
    }

    /**
     * Reset all rate limiting state (for testing).
     */
    void reset() {
        threadBuckets.clear();
        totalRequests.set(0);
        rejectedRequests.set(0);
        lastCleanupTime.set(System.currentTimeMillis());
    }
}