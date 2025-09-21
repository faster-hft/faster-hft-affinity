package com.faster.affinity.cache;

import com.faster.affinity.pool.ObjectPoolManager;
import com.faster.affinity.pool.ObjectPool;
import com.faster.affinity.utils.ThreadLocalManager;
import jdk.internal.vm.annotation.Contended;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local hot path cache for zero-contention affinity operations.
 * Caches frequently accessed data to minimize system calls and allocations.
 */
@Contended
public final class HotPathCache {

    // Cache validation timestamps - isolated to prevent false sharing
    @Contended("global-state")
    private static final AtomicLong globalCacheVersion = new AtomicLong(1);

    private final ThreadLocalManager.ManagedThreadLocal<AffinityCache> affinityCache =
        ThreadLocalManager.create("hotpath-affinity-cache", AffinityCache::new, AffinityCache::close);

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
     * Thread-local affinity operation cache with proper resource management.
     */
    @Contended
    public static final class AffinityCache implements AutoCloseable {
        // Cache validation - isolated to prevent false sharing
        @Contended("validation")
        private long cacheVersion;

        // Cached affinity state - separate cache line group
        @Contended("affinity-state")
        private final BitSet lastThreadAffinity;
        @Contended("affinity-state")
        private final BitSet lastProcessAffinity;
        @Contended("affinity-state")
        private long lastThreadId = -1;
        @Contended("affinity-state")
        private int lastProcessId = -1;

        // Pooled temporary objects with proper resource tracking
        private final PooledResource<long[]> tempMaskArrayResource;
        private final PooledResource<int[]> tempIntArrayResource;
        private final PooledResource<BitSet> tempBitSetResource;
        private final PooledResource<BitSet> tempBitSetResource2; // Second BitSet for conversions

        // Convenience accessors for pooled objects
        private final long[] tempMaskArray;
        private final int[] tempIntArray;
        private final BitSet tempBitSet;
        private final BitSet tempBitSet2; // Second BitSet for conversions

        // Resource management
        private volatile boolean closed = false;

        // Performance counters
        private long cacheHits = 0;
        private long cacheMisses = 0;

        // Operation sequence counter for validation (replaces timestamp)
        @Contended("validation")
        private long lastOperationSequence = 0;

        private AffinityCache() {
            this.lastThreadAffinity = new BitSet(4096);
            this.lastProcessAffinity = new BitSet(4096);

            // Initialize cache version to current global version
            this.cacheVersion = getCacheVersion();

            // Get pooled objects for this thread with proper resource tracking
            this.tempMaskArrayResource = new PooledResource<>(ObjectPoolManager.getLongArrayPool());
            this.tempIntArrayResource = new PooledResource<>(ObjectPoolManager.getIntArrayPool());
            this.tempBitSetResource = new PooledResource<>(ObjectPoolManager.getBitSetPool());
            this.tempBitSetResource2 = new PooledResource<>(ObjectPoolManager.getBitSetPool());

            // Cache the actual objects for convenience
            this.tempMaskArray = tempMaskArrayResource.get();
            this.tempIntArray = tempIntArrayResource.get();
            this.tempBitSet = tempBitSetResource.get();
            this.tempBitSet2 = tempBitSetResource2.get();
        }

        /**
         * Check if cached thread affinity is valid.
         */
        public boolean isThreadAffinityValid(long threadId) {
            return isCacheValid() &&
                   threadId == lastThreadId &&
                   (lastOperationSequence > 0); // Simple sequence check replaces expensive timestamp
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
            if (affinity != null) {
                copyBitsManually(affinity, this.lastThreadAffinity);
            }
            updateValidation();
        }

        /**
         * Check if cached process affinity is valid.
         */
        public boolean isProcessAffinityValid(int processId) {
            return isCacheValid() && processId == lastProcessId;
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
            if (affinity != null) {
                copyBitsManually(affinity, this.lastProcessAffinity);
            }
            updateValidation();
        }

        /**
         * Get temporary long array for system calls (pre-allocated, no GC).
         * Returns null if cache is closed - optimized for hot path performance.
         */
        public long[] getTempMaskArray() {
            if (closed) {
                return null; // Hot path optimization - no exception throwing
            }
            // Clear the array before use
            java.util.Arrays.fill(tempMaskArray, 0);
            return tempMaskArray;
        }

        /**
         * Get temporary int array for calculations (pre-allocated, no GC).
         * Returns null if cache is closed - optimized for hot path performance.
         */
        public int[] getTempIntArray() {
            if (closed) {
                return null; // Hot path optimization - no exception throwing
            }
            // Clear the array before use
            java.util.Arrays.fill(tempIntArray, 0);
            return tempIntArray;
        }

        /**
         * Get temporary BitSet for operations (pre-allocated, no GC).
         * Returns null if cache is closed - optimized for hot path performance.
         */
        public BitSet getTempBitSet() {
            if (closed) {
                return null; // Hot path optimization - no exception throwing
            }
            tempBitSet.clear();
            return tempBitSet;
        }

        /**
         * Get second temporary BitSet for conversions (pre-allocated, no GC).
         * Returns null if cache is closed - optimized for hot path performance.
         */
        public BitSet getTempBitSet2() {
            if (closed) {
                return null; // Hot path optimization - no exception throwing
            }
            tempBitSet2.clear();
            return tempBitSet2;
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
            return new CacheStats(cacheHits, cacheMisses, isCacheValid(), lastOperationSequence);
        }

        private boolean isCacheValid() {
            return cacheVersion == getCacheVersion();
        }

        private void updateValidation() {
            this.cacheVersion = getCacheVersion();
            this.lastOperationSequence++; // Simple increment replaces expensive nanoTime()
        }

        /**
         * Manually copy bits from source to destination BitSet to avoid allocations.
         * This prevents potential memory allocation that could occur in BitSet.or() if resizing is needed.
         */
        private static void copyBitsManually(BitSet source, BitSet destination) {
            if (source == null || destination == null) {
                return;
            }

            // Efficiently copy all set bits without risking allocation
            for (int i = source.nextSetBit(0); i >= 0; i = source.nextSetBit(i + 1)) {
                destination.set(i);

                // Avoid infinite loop on Integer.MAX_VALUE
                if (i == Integer.MAX_VALUE) {
                    break;
                }
            }
        }

        /**
         * Check if the cache is closed.
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Close the cache and return all pooled resources.
         * This method is idempotent and thread-safe.
         */
        @Override
        public void close() {
            if (!closed) {
                synchronized (this) {
                    if (!closed) {
                        closed = true;
                        try {
                            if (tempMaskArrayResource != null) {
                                tempMaskArrayResource.close();
                            }
                            if (tempIntArrayResource != null) {
                                tempIntArrayResource.close();
                            }
                            if (tempBitSetResource != null) {
                                tempBitSetResource.close();
                            }
                            if (tempBitSetResource2 != null) {
                                tempBitSetResource2.close();
                            }
                        } catch (Exception e) {
                            // Silent cleanup - no logging in production hot paths to avoid I/O blocking
                            // Resource cleanup failures are non-critical and should not impact performance
                        }
                    }
                }
            }
        }

        /**
         * Check if cache is usable (not closed).
         */
        private void ensureNotClosed() {
            if (closed) {
                throw new IllegalStateException("Cache has been closed");
            }
        }
    }

    /**
     * Wrapper for pooled resources that provides automatic return-to-pool functionality.
     */
    private static class PooledResource<T> implements AutoCloseable {
        private final ObjectPool<T> pool;
        private final T resource;
        private volatile boolean returned = false;

        public PooledResource(ObjectPool<T> pool) {
            this.pool = pool;
            this.resource = pool.acquire();
        }

        public T get() {
            if (returned) {
                return null; // Hot path optimization - no exception throwing
            }
            return resource;
        }

        @Override
        public void close() {
            if (!returned) {
                synchronized (this) {
                    if (!returned) {
                        returned = true;
                        try {
                            pool.release(resource);
                        } catch (Exception e) {
                            // Silent cleanup - no logging in production hot paths to avoid I/O blocking
                            // Pool return failures are non-critical and should not impact performance
                        }
                    }
                }
            }
        }

        public boolean isReturned() {
            return returned;
        }
    }

    /**
     * Cache statistics for monitoring.
     */
    public static class CacheStats {
        private final long hits;
        private final long misses;
        private final boolean valid;
        private final long lastOperationSequence;

        public CacheStats(long hits, long misses, boolean valid, long lastOperationSequence) {
            this.hits = hits;
            this.misses = misses;
            this.valid = valid;
            this.lastOperationSequence = lastOperationSequence;
        }

        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public boolean isValid() { return valid; }
        public long getLastOperationSequence() { return lastOperationSequence; }

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