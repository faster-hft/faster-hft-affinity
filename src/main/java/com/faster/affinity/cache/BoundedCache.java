package com.faster.affinity.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe bounded cache with LRU eviction policy.
 * Provides memory-safe caching for HFT environments with configurable size limits
 * and automatic eviction to prevent memory leaks.
 */
public final class BoundedCache<K, V> {
    private static final Logger logger = LoggerFactory.getLogger(BoundedCache.class);

    // Default cache configuration
    private static final int DEFAULT_MAX_SIZE = 1000;
    private static final long DEFAULT_TTL_MS = 30_000; // 30 seconds

    private final ConcurrentHashMap<K, CacheEntry<V>> cache;
    private final ConcurrentLinkedQueue<K> accessOrder; // LRU tracking
    private final ReentrantLock evictionLock; // Protects eviction operations
    private final AtomicBoolean evictionInProgress; // Coordination flag

    private final int maxSize;
    private final long ttlMs;

    // Statistics tracking
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    // Last cleanup timestamp to avoid excessive cleanup
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /**
     * Cache entry with timestamp for TTL support.
     */
    private static class CacheEntry<V> {
        final V value;
        final long timestamp;
        volatile long lastAccessTime;

        CacheEntry(V value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
            this.lastAccessTime = timestamp;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }

        void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    /**
     * Create bounded cache with default configuration.
     */
    public BoundedCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MS);
    }

    /**
     * Create bounded cache with custom configuration.
     */
    public BoundedCache(int maxSize, long ttlMs) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Max size must be positive");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 256));
        this.accessOrder = new ConcurrentLinkedQueue<>();
        this.evictionLock = new ReentrantLock();
        this.evictionInProgress = new AtomicBoolean(false);

        logger.debug("Created BoundedCache with maxSize={}, ttlMs={}", maxSize, ttlMs);
    }

    /**
     * Get value from cache.
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }

        // Check TTL expiration
        if (entry.isExpired(ttlMs)) {
            cache.remove(key);
            misses.incrementAndGet();
            logger.trace("Cache entry expired: {}", key);
            return null;
        }

        // Update access time and LRU order
        entry.updateAccessTime();
        accessOrder.offer(key); // Add to end of access queue

        hits.incrementAndGet();
        return entry.value;
    }

    /**
     * Put value into cache with automatic eviction if needed.
     */
    public void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }

        puts.incrementAndGet();

        // Create new cache entry
        CacheEntry<V> newEntry = new CacheEntry<>(value);
        CacheEntry<V> oldEntry = cache.put(key, newEntry);

        // Add to access order queue
        accessOrder.offer(key);

        // If this was an update, don't count it as a size increase
        boolean isUpdate = (oldEntry != null);

        // Check if eviction is needed
        if (!isUpdate && cache.size() > maxSize) {
            evictOldEntries();
        }

        // Periodic cleanup of expired entries
        cleanupExpiredEntries();

        logger.trace("Cache put: {} (size={})", key, cache.size());
    }

    /**
     * Remove value from cache.
     */
    public V remove(K key) {
        if (key == null) {
            return null;
        }

        CacheEntry<V> entry = cache.remove(key);
        return entry != null ? entry.value : null;
    }

    /**
     * Check if cache contains key.
     */
    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }

        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            return false;
        }

        // Check TTL expiration
        if (entry.isExpired(ttlMs)) {
            cache.remove(key);
            return false;
        }

        return true;
    }

    /**
     * Get current cache size.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Clear all entries from cache.
     */
    public void clear() {
        cache.clear();
        accessOrder.clear();
        logger.debug("Cache cleared");
    }

    /**
     * Evict oldest entries when cache exceeds max size.
     * Uses coordinated eviction to prevent cache stampede.
     */
    private void evictOldEntries() {
        // Fast check: if eviction is already in progress, return
        if (evictionInProgress.get()) {
            return;
        }

        // Try to acquire eviction coordination
        if (!evictionInProgress.compareAndSet(false, true)) {
            return; // Another thread is coordinating eviction
        }

        try {
            // Double-check cache size after acquiring coordination
            int currentSize = cache.size();
            if (currentSize <= maxSize) {
                return; // Size is fine now
            }

            // Use tryLock with fallback to prevent blocking
            boolean acquired = false;
            try {
                acquired = evictionLock.tryLock();
                if (!acquired) {
                    // If we can't get the lock immediately, use probabilistic eviction
                    performProbabilisticEviction(currentSize);
                    return;
                }

                // Perform coordinated eviction
                performCoordinatedEviction(currentSize);

            } finally {
                if (acquired) {
                    evictionLock.unlock();
                }
            }

        } finally {
            evictionInProgress.set(false);
        }
    }

    /**
     * Perform coordinated eviction with proper locking.
     */
    private void performCoordinatedEviction(int currentSize) {
        int targetSize = (int) (maxSize * 0.8); // Evict to 80% of max size
        int toEvict = currentSize - targetSize;

        if (toEvict <= 0) {
            return;
        }

        logger.debug("Evicting {} entries from cache (size={}, max={})",
                    toEvict, currentSize, maxSize);

        int evicted = 0;
        K key;

        // Evict based on LRU order
        while (evicted < toEvict && (key = accessOrder.poll()) != null) {
            if (cache.remove(key) != null) {
                evicted++;
            }
        }

        evictions.addAndGet(evicted);
        logger.debug("Evicted {} entries, new size: {}", evicted, cache.size());
    }

    /**
     * Perform probabilistic eviction when lock is not available.
     * This prevents cache stampede by allowing multiple threads to evict safely.
     */
    private void performProbabilisticEviction(int currentSize) {
        if (currentSize <= maxSize) {
            return;
        }

        // Calculate eviction probability based on how far over maxSize we are
        double overflowRatio = (double) (currentSize - maxSize) / maxSize;
        double evictionProbability = Math.min(0.1, overflowRatio); // Max 10% chance

        // Each thread has a small chance to evict a few entries
        if (ThreadLocalRandom.current().nextDouble() < evictionProbability) {
            int toEvict = Math.min(5, currentSize - maxSize); // Evict at most 5 entries
            int evicted = 0;

            for (int i = 0; i < toEvict && evicted < toEvict; i++) {
                K key = accessOrder.poll();
                if (key != null && cache.remove(key) != null) {
                    evicted++;
                }
            }

            if (evicted > 0) {
                evictions.addAndGet(evicted);
                logger.debug("Probabilistic eviction: removed {} entries", evicted);
            }
        }
    }

    /**
     * Clean up expired entries periodically.
     */
    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();

        // Only cleanup every 10 seconds to avoid overhead
        if (now - lastCleanupTime < 10_000) {
            return;
        }

        if (!evictionLock.tryLock()) {
            return; // Another thread is cleaning up
        }

        try {
            lastCleanupTime = now;
            int removed = 0;

            // Remove expired entries
            for (K key : cache.keySet()) {
                CacheEntry<V> entry = cache.get(key);
                if (entry != null && entry.isExpired(ttlMs)) {
                    if (cache.remove(key) != null) {
                        removed++;
                    }
                }
            }

            if (removed > 0) {
                logger.debug("Cleaned up {} expired entries", removed);
            }

        } finally {
            evictionLock.unlock();
        }
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getStats() {
        long totalRequests = hits.get() + misses.get();
        double hitRate = totalRequests > 0 ? (double) hits.get() / totalRequests : 0.0;

        return new CacheStats(
            cache.size(),
            maxSize,
            hits.get(),
            misses.get(),
            evictions.get(),
            puts.get(),
            hitRate
        );
    }

    /**
     * Cache statistics for monitoring and debugging.
     */
    public static class CacheStats {
        public final int currentSize;
        public final int maxSize;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final long puts;
        public final double hitRate;

        CacheStats(int currentSize, int maxSize, long hits, long misses,
                  long evictions, long puts, double hitRate) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.puts = puts;
            this.hitRate = hitRate;
        }

        @Override
        public String toString() {
            return String.format(
                "CacheStats{size=%d/%d, hits=%d, misses=%d, evictions=%d, puts=%d, hitRate=%.2f%%}",
                currentSize, maxSize, hits, misses, evictions, puts, hitRate * 100
            );
        }
    }

    /**
     * Check for potential memory issues and log warnings.
     */
    public void checkHealth() {
        CacheStats stats = getStats();

        if (stats.hitRate < 0.5 && stats.hits + stats.misses > 1000) {
            logger.warn("Low cache hit rate detected: {:.2f}% (may indicate poor cache sizing)",
                       stats.hitRate * 100);
        }

        if (stats.currentSize > stats.maxSize * 0.9) {
            logger.warn("Cache is nearly full: {}/{} (frequent evictions likely)",
                       stats.currentSize, stats.maxSize);
        }

        if (stats.evictions > stats.puts * 0.5) {
            logger.warn("High eviction rate detected: {} evictions vs {} puts " +
                       "(consider increasing cache size)", stats.evictions, stats.puts);
        }
    }
}