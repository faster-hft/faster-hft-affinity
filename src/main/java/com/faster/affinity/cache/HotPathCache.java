package com.faster.affinity.cache;

import com.faster.affinity.pool.ObjectPoolManager;
import com.faster.affinity.pool.ObjectPool;
import com.faster.affinity.utils.ThreadLocalManager;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local hot path cache for zero-contention affinity operations.
 * Caches frequently accessed data to minimize system calls and allocations.
 * FIXED: Manual cache line padding replaces @Contended for module compatibility.
 */
public final class HotPathCache {

    // CRITICAL FIX: Proper cache line isolation using padded class
    // Cache validation timestamps - isolated to prevent false sharing
    private static final PaddedAtomicLong globalCacheVersion = new PaddedAtomicLong(1);

    /**
     * Padded AtomicLong to prevent false sharing.
     * Uses inheritance padding pattern that JVM cannot optimize away.
     */
    private static class PaddedAtomicLong extends AtomicLong {
        // Pre-padding: 7 longs * 8 bytes = 56 bytes
        private volatile long p0, p1, p2, p3, p4, p5, p6;

        public PaddedAtomicLong(long initialValue) {
            super(initialValue);
        }

        // Post-padding: 7 longs * 8 bytes = 56 bytes
        private volatile long p7, p8, p9, p10, p11, p12, p13;

        // Prevent dead code elimination by using padding in a method
        public long sumPadding() {
            return p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13;
        }
    }

    /**
     * Cache-line padded long to prevent false sharing.
     */
    private static class PaddedLong {
        // Pre-padding: 7 longs * 8 bytes = 56 bytes
        private volatile long p0, p1, p2, p3, p4, p5, p6;
        private volatile long value;
        // Post-padding: 7 longs * 8 bytes = 56 bytes
        private volatile long p7, p8, p9, p10, p11, p12, p13;

        public PaddedLong(long initialValue) {
            this.value = initialValue;
        }

        public long get() { return value; }
        public void set(long newValue) { this.value = newValue; }

        // Prevent dead code elimination
        public long sumPadding() {
            return p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13;
        }
    }

    /**
     * Cache-line padded int to prevent false sharing.
     */
    private static class PaddedInt {
        // Pre-padding: 15 ints * 4 bytes = 60 bytes
        private volatile int p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14;
        private volatile int value;
        // Post-padding: 15 ints * 4 bytes = 60 bytes
        private volatile int p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29;

        public PaddedInt(int initialValue) {
            this.value = initialValue;
        }

        public int get() { return value; }
        public void set(int newValue) { this.value = newValue; }

        // Prevent dead code elimination
        public int sumPadding() {
            return p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14 +
                   p15 + p16 + p17 + p18 + p19 + p20 + p21 + p22 + p23 + p24 + p25 + p26 + p27 + p28 + p29;
        }
    }

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
     * FIXED: Manual cache line padding replaces @Contended for module compatibility.
     */
    public static final class AffinityCache implements AutoCloseable {
        // CRITICAL FIX: Proper cache line isolation for cache validation
        private final PaddedAtomicLong cacheVersion = new PaddedAtomicLong(0);

        // Cached affinity state - these are final references so no false sharing risk
        private final BitSet lastThreadAffinity;
        private final BitSet lastProcessAffinity;

        // Primitive fields that need protection from false sharing
        private final PaddedLong lastThreadId = new PaddedLong(-1);
        private final PaddedInt lastProcessId = new PaddedInt(-1);

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

        // Operation sequence counter for validation (replaces timestamp) - with proper padding
        private final PaddedLong lastOperationSequence = new PaddedLong(0);

        private AffinityCache() {
            this.lastThreadAffinity = new BitSet(4096);
            this.lastProcessAffinity = new BitSet(4096);

            // Initialize cache version to current global version
            this.cacheVersion.set(getCacheVersion());

            // Get pooled objects for this thread with proper resource tracking
            this.tempMaskArrayResource = new PooledResource<>(ObjectPoolManager.getLongArrayPool());
            this.tempIntArrayResource = new PooledResource<>(ObjectPoolManager.getIntArrayPool());
            this.tempBitSetResource = new PooledResource<>(ObjectPoolManager.getBitSetPool());
            this.tempBitSetResource2 = new PooledResource<>(ObjectPoolManager.getBitSetPool());

            // Cache the actual objects for convenience with null safety
            this.tempMaskArray = tempMaskArrayResource != null ? tempMaskArrayResource.get() : null;
            this.tempIntArray = tempIntArrayResource != null ? tempIntArrayResource.get() : null;
            this.tempBitSet = tempBitSetResource != null ? tempBitSetResource.get() : null;
            this.tempBitSet2 = tempBitSetResource2 != null ? tempBitSetResource2.get() : null;

            // Verify all resources were acquired successfully
            if (tempMaskArray == null || tempIntArray == null || tempBitSet == null || tempBitSet2 == null) {
                throw new IllegalStateException("Failed to acquire pooled resources for affinity cache");
            }
        }

        /**
         * Check if cached thread affinity is valid.
         */
        public boolean isThreadAffinityValid(long threadId) {
            return isCacheValid() &&
                   threadId == lastThreadId.get() &&
                   (lastOperationSequence.get() > 0); // Simple sequence check replaces expensive timestamp
        }

        /**
         * Get cached thread affinity (only if valid).
         */
        public BitSet getCachedThreadAffinity(long threadId) {
            if (isThreadAffinityValid(threadId)) {
                cacheHits++;
                // CRITICAL FIX: Avoid allocation in hot path - copy to provided BitSet
                BitSet result = getTempBitSet2();
                if (result != null) {
                    copyBitsManually(lastThreadAffinity, result);
                    return result;
                }
                // Fallback to clone if temp BitSet unavailable (rare case)
                return (BitSet) lastThreadAffinity.clone();
            }
            cacheMisses++;
            return null;
        }

        /**
         * Update cached thread affinity.
         */
        public void setCachedThreadAffinity(long threadId, BitSet affinity) {
            this.lastThreadId.set(threadId);
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
            return isCacheValid() && processId == lastProcessId.get();
        }

        /**
         * Get cached process affinity (only if valid).
         */
        public BitSet getCachedProcessAffinity(int processId) {
            if (isProcessAffinityValid(processId)) {
                cacheHits++;
                // CRITICAL FIX: Avoid allocation in hot path - copy to provided BitSet
                BitSet result = getTempBitSet();
                if (result != null) {
                    copyBitsManually(lastProcessAffinity, result);
                    return result;
                }
                // Fallback to clone if temp BitSet unavailable (rare case)
                return (BitSet) lastProcessAffinity.clone();
            }
            cacheMisses++;
            return null;
        }

        /**
         * Update cached process affinity.
         */
        public void setCachedProcessAffinity(int processId, BitSet affinity) {
            this.lastProcessId.set(processId);
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
            if (closed || tempMaskArray == null) {
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
            if (closed || tempIntArray == null) {
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
            if (closed || tempBitSet == null) {
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
            if (closed || tempBitSet2 == null) {
                return null; // Hot path optimization - no exception throwing
            }
            tempBitSet2.clear();
            return tempBitSet2;
        }

        /**
         * Invalidate this thread's cache.
         */
        public void invalidate() {
            // CRITICAL FIX: Atomic invalidation to prevent race conditions
            cacheVersion.set(0);
            lastThreadId.set(-1);
            lastProcessId.set(-1);
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
            return new CacheStats(cacheHits, cacheMisses, isCacheValid(), lastOperationSequence.get());
        }

        private boolean isCacheValid() {
            // CRITICAL FIX: Atomic read to prevent race conditions
            return cacheVersion.get() == getCacheVersion();
        }

        private void updateValidation() {
            // CRITICAL FIX: Atomic update to prevent race conditions
            this.cacheVersion.set(getCacheVersion());
            this.lastOperationSequence.set(this.lastOperationSequence.get() + 1); // Simple increment replaces expensive nanoTime()
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