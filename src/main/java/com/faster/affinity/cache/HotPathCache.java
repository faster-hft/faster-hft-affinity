package com.faster.affinity.cache;

import com.faster.affinity.pool.ObjectPoolManager;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local hot path cache for zero-contention affinity operations.
 * Caches frequently accessed data to minimize system calls and allocations.
 */
public final class HotPathCache {

    // Cache validation timestamps
    private static final AtomicLong globalCacheVersion = new AtomicLong(1);

    private final ThreadLocal<AffinityCache> affinityCache = ThreadLocal.withInitial(AffinityCache::new);

    /**
     * Get thread-local affinity cache for the current thread.
     */
    public AffinityCache getAffinityCache() {
        return affinityCache.get();
    }

    /**
     * Invalidate all thread-local caches globally.
     * Use when system configuration changes.
     */
    public static void invalidateAll() {
        globalCacheVersion.incrementAndGet();
    }

    /**
     * Get current global cache version for validation.
     */
    public static long getCacheVersion() {
        return globalCacheVersion.get();
    }

    /**
     * Thread-local affinity operation cache.
     */
    public static final class AffinityCache {
        // Cache validation
        private long cacheVersion = 0;

        // Cached affinity state
        private final BitSet lastThreadAffinity;
        private final BitSet lastProcessAffinity;
        private long lastThreadId = -1;
        private int lastProcessId = -1;

        // Pooled temporary objects
        private final long[] tempMaskArray;
        private final int[] tempIntArray;
        private final BitSet tempBitSet;

        // Performance counters
        private long cacheHits = 0;
        private long cacheMisses = 0;
        private long lastValidationTime = 0;

        private AffinityCache() {
            this.lastThreadAffinity = new BitSet(4096);
            this.lastProcessAffinity = new BitSet(4096);

            // Initialize cache version to current global version
            this.cacheVersion = getCacheVersion();

            // Get pooled objects for this thread
            this.tempMaskArray = ObjectPoolManager.getLongArrayPool().acquire();
            this.tempIntArray = ObjectPoolManager.getIntArrayPool().acquire();
            this.tempBitSet = ObjectPoolManager.getBitSetPool().acquire();
        }

        /**
         * Check if cached thread affinity is valid.
         */
        public boolean isThreadAffinityValid(long threadId) {
            if (!isCacheValid()) {
                return false;
            }
            if (threadId != lastThreadId) {
                return false;
            }
            // Check for cache staleness (1 millisecond expiration for HFT)
            if (lastValidationTime > 0 && (System.nanoTime() - lastValidationTime) > 1_000_000) {
                return false;
            }
            return true;
        }

        /**
         * Get cached thread affinity (only if valid).
         */
        public BitSet getCachedThreadAffinity(long threadId) {
            if (isThreadAffinityValid(threadId)) {
                cacheHits++;
                return (BitSet) lastThreadAffinity.clone();
            }
            cacheMisses++;
            return null;
        }

        /**
         * Update cached thread affinity.
         */
        public void setCachedThreadAffinity(long threadId, BitSet affinity) {
            this.lastThreadId = threadId;
            this.lastThreadAffinity.clear();
            this.lastThreadAffinity.or(affinity);
            updateValidation();
        }

        /**
         * Check if cached process affinity is valid.
         */
        public boolean isProcessAffinityValid(int processId) {
            if (!isCacheValid()) {
                return false;
            }
            if (processId != lastProcessId) {
                return false;
            }
            return true;
        }

        /**
         * Get cached process affinity (only if valid).
         */
        public BitSet getCachedProcessAffinity(int processId) {
            if (isProcessAffinityValid(processId)) {
                cacheHits++;
                return (BitSet) lastProcessAffinity.clone();
            }
            cacheMisses++;
            return null;
        }

        /**
         * Update cached process affinity.
         */
        public void setCachedProcessAffinity(int processId, BitSet affinity) {
            this.lastProcessId = processId;
            this.lastProcessAffinity.clear();
            this.lastProcessAffinity.or(affinity);
            updateValidation();
        }

        /**
         * Get temporary long array for system calls (pre-allocated, no GC).
         */
        public long[] getTempMaskArray() {
            // Clear the array before use
            java.util.Arrays.fill(tempMaskArray, 0);
            return tempMaskArray;
        }

        /**
         * Get temporary int array for calculations (pre-allocated, no GC).
         */
        public int[] getTempIntArray() {
            // Clear the array before use
            java.util.Arrays.fill(tempIntArray, 0);
            return tempIntArray;
        }

        /**
         * Get temporary BitSet for operations (pre-allocated, no GC).
         */
        public BitSet getTempBitSet() {
            tempBitSet.clear();
            return tempBitSet;
        }

        /**
         * Invalidate this thread's cache.
         */
        public void invalidate() {
            cacheVersion = 0;
            lastThreadId = -1;
            lastProcessId = -1;
            lastThreadAffinity.clear();
            lastProcessAffinity.clear();
        }

        /**
         * Get cache hit rate for monitoring.
         */
        public double getHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }

        /**
         * Get cache statistics.
         */
        public CacheStats getStats() {
            return new CacheStats(cacheHits, cacheMisses, isCacheValid(), lastValidationTime);
        }

        private boolean isCacheValid() {
            return cacheVersion == getCacheVersion();
        }

        private void updateValidation() {
            this.cacheVersion = getCacheVersion();
            this.lastValidationTime = System.nanoTime();
        }
    }

    /**
     * Cache statistics for monitoring.
     */
    public static class CacheStats {
        private final long hits;
        private final long misses;
        private final boolean valid;
        private final long lastValidationTime;

        public CacheStats(long hits, long misses, boolean valid, long lastValidationTime) {
            this.hits = hits;
            this.misses = misses;
            this.valid = valid;
            this.lastValidationTime = lastValidationTime;
        }

        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public boolean isValid() { return valid; }
        public long getLastValidationTime() { return lastValidationTime; }

        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, valid=%s}",
                    hits, misses, getHitRate() * 100, valid);
        }
    }
}