package com.faster.affinity.core;

import com.faster.affinity.cache.HotPathCache;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.exceptions.ErrorCodes;
import com.faster.affinity.platform.PlatformProvider;
import com.faster.affinity.performance.HFTPerformanceProfiler;
import com.faster.affinity.annotations.HotPath;
import com.faster.affinity.annotations.ColdPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

/**
 * Lock-free, high-performance affinity operations for HFT hot paths.
 * Uses thread-local caching and pre-allocated objects to minimize latency.
 */
public final class LockFreeAffinityOperations {
    private static final Logger logger = LoggerFactory.getLogger(LockFreeAffinityOperations.class);

    private final PlatformProvider platformProvider;
    private final HotPathCache hotPathCache;
    private final HFTPerformanceProfiler profiler;

    public LockFreeAffinityOperations(PlatformProvider platformProvider, HotPathCache hotPathCache, HFTPerformanceProfiler profiler) {
        this.platformProvider = platformProvider;
        this.hotPathCache = hotPathCache;
        this.profiler = profiler;
    }

    /**
     * Get current thread affinity with aggressive caching.
     * This is the primary hot path method - optimized for minimal latency.
     */
    @HotPath(value = "Lock-free current thread affinity query", expectedFrequency = 1000000, targetLatencyNs = 100)
    public OperationResult<BitSet> getCurrentThreadAffinityFast() {
        long threadId = platformProvider.getCurrentThreadId();
        return getThreadAffinityFast(threadId);
    }

    /**
     * Get thread affinity with hot path optimization.
     * Uses cached values when possible to avoid system calls.
     */
    @HotPath(value = "Lock-free thread affinity query", expectedFrequency = 500000, targetLatencyNs = 150)
    public OperationResult<BitSet> getThreadAffinityFast(long threadId) {
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();

        // Fast path: check cache first
        BitSet cachedAffinity = cache.getCachedThreadAffinity(threadId);
        if (cachedAffinity != null) {
            // Cache hit
            if (profiler != null) profiler.recordCacheAccess(true);
            return OperationResult.success(cachedAffinity);
        }

        // Cache miss - record and go to slow path
        if (profiler != null) profiler.recordCacheAccess(false);
        return getThreadAffinitySlowPath(threadId, cache);
    }

    /**
     * Set current thread affinity with hot path optimization.
     * Updates cache and uses pre-allocated arrays.
     */
    @HotPath(value = "Lock-free current thread affinity update", expectedFrequency = 100000, targetLatencyNs = 200)
    public OperationResult<Void> setCurrentThreadAffinityFast(BitSet cpuMask) {
        long threadId = platformProvider.getCurrentThreadId();
        return setThreadAffinityFast(threadId, cpuMask);
    }

    /**
     * Set thread affinity with hot path optimization.
     * Uses pre-allocated arrays and updates cache.
     */
    @HotPath(value = "Lock-free thread affinity update", expectedFrequency = 50000, targetLatencyNs = 250)
    public OperationResult<Void> setThreadAffinityFast(long threadId, BitSet cpuMask) {
        // Fast validation (no exceptions in hot path)
        if (cpuMask == null || cpuMask.isEmpty()) {
            return OperationResult.failure(ErrorCodes.ERROR_INVALID_PARAMETER, "setThreadAffinity", "Invalid CPU mask");
        }

        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();

        // Use pre-allocated array from cache
        long[] maskArray = cache.getTempMaskArray();

        // Convert BitSet to mask array (optimized)
        convertBitSetToMaskArray(cpuMask, maskArray);

        // Perform system call
        int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);

        if (result == 0) {
            // Success: update cache (only if operation succeeded)
            cache.setCachedThreadAffinity(threadId, cpuMask);
            return OperationResult.success(null);
        } else {
            // Failure: don't update cache, return error
            return OperationResult.failure(result, "setThreadAffinity", "System call failed");
        }
    }

    /**
     * Bulk affinity operations for multiple threads.
     * Optimized for scenarios where many threads need affinity changes.
     */
    @HotPath(value = "Bulk thread affinity operations", expectedFrequency = 10000, targetLatencyNs = 500)
    public int setBulkThreadAffinity(long[] threadIds, BitSet cpuMask) {
        if (threadIds == null || threadIds.length == 0) {
            return 0;
        }

        // Fast validation once for all operations
        if (cpuMask == null || cpuMask.isEmpty()) {
            return 0;
        }

        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        long[] maskArray = cache.getTempMaskArray();

        // Convert BitSet to mask array once for all operations
        convertBitSetToMaskArray(cpuMask, maskArray);

        int successCount = 0;
        // Optimize loop for bulk operations
        for (int i = 0; i < threadIds.length; i++) {
            long threadId = threadIds[i];
            int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);
            if (result == 0) {
                // Batch cache updates for better performance
                cache.setCachedThreadAffinity(threadId, cpuMask);
                successCount++;
            }
        }

        return successCount;
    }

    /**
     * Check if thread affinity has changed (cache invalidation check).
     */
    @HotPath(value = "Cache invalidation check", expectedFrequency = 100000, targetLatencyNs = 50)
    public boolean hasAffinityChanged(long threadId) {
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        return !cache.isThreadAffinityValid(threadId);
    }

    /**
     * Invalidate all cached affinity data.
     * Use when system configuration changes.
     */
    public void invalidateCache() {
        HotPathCache.invalidateAll();
    }

    /**
     * Slow path for thread affinity retrieval.
     * Separated to keep fast path optimized.
     */
    @ColdPath("Slow path for cache misses")
    private OperationResult<BitSet> getThreadAffinitySlowPath(long threadId, HotPathCache.AffinityCache cache) {
        long[] maskArray = cache.getTempMaskArray();

        int result = platformProvider.getThreadAffinity(threadId, maskArray, maskArray.length);

        if (result == 0) {
            BitSet affinity = convertMaskArrayToBitSet(maskArray);
            cache.setCachedThreadAffinity(threadId, affinity);
            return OperationResult.success(affinity);
        } else {
            return OperationResult.failure(result, "getThreadAffinity", "System call failed");
        }
    }

    /**
     * Convert BitSet to long array mask (optimized for hot path).
     */
    @HotPath(value = "BitSet to mask array conversion", expectedFrequency = 200000, targetLatencyNs = 100)
    private static void convertBitSetToMaskArray(BitSet bitSet, long[] maskArray) {
        // Clear the array first
        for (int i = 0; i < maskArray.length; i++) {
            maskArray[i] = 0;
        }

        // Set bits efficiently
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            int longIndex = i / 64;
            int bitIndex = i % 64;

            if (longIndex < maskArray.length) {
                maskArray[longIndex] |= (1L << bitIndex);
            }

            // Avoid infinite loop on Integer.MAX_VALUE
            if (i == Integer.MAX_VALUE) {
                break;
            }
        }
    }

    /**
     * Convert long array mask to BitSet (optimized for hot path).
     */
    @HotPath(value = "Mask array to BitSet conversion", expectedFrequency = 200000, targetLatencyNs = 100)
    private static BitSet convertMaskArrayToBitSet(long[] maskArray) {
        BitSet result = new BitSet();

        for (int longIndex = 0; longIndex < maskArray.length; longIndex++) {
            long mask = maskArray[longIndex];
            if (mask != 0) {
                for (int bitIndex = 0; bitIndex < 64; bitIndex++) {
                    if ((mask & (1L << bitIndex)) != 0) {
                        result.set(longIndex * 64 + bitIndex);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Create appropriate exception for error codes (don't inline to keep hot path fast).
     */
    @ColdPath("Error exception creation")
    private static RuntimeException createAffinityException(int errorCode, String operation) {
        switch (errorCode) {
            case 1: // EPERM
                return new SecurityException(operation + " failed: insufficient permissions");
            case 3: // ESRCH
                return new IllegalArgumentException(operation + " failed: invalid thread ID");
            case 22: // EINVAL
                return new IllegalArgumentException(operation + " failed: invalid parameters");
            default:
                return new RuntimeException(operation + " failed with error code: " + errorCode);
        }
    }
}