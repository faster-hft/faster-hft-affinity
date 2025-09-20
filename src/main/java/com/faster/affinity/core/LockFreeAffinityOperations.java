package com.faster.affinity.core;

import com.faster.affinity.cache.HotPathCache;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;

import java.util.BitSet;

/**
 * Lock-free, high-performance affinity operations for HFT hot paths.
 * Uses thread-local caching and pre-allocated objects to minimize latency.
 */
public final class LockFreeAffinityOperations {
    private static final Logger logger = LoggerFactory.getLogger(LockFreeAffinityOperations.class);

    private final PlatformProvider platformProvider;
    private final HotPathCache hotPathCache;

    public LockFreeAffinityOperations(PlatformProvider platformProvider, HotPathCache hotPathCache) {
        this.platformProvider = platformProvider;
        this.hotPathCache = hotPathCache;
    }

    /**
     * Get current thread affinity with aggressive caching.
     * This is the primary hot path method - optimized for minimal latency.
     */
    @ForceInline
    public OperationResult<BitSet> getCurrentThreadAffinityFast() {
        long threadId = platformProvider.getCurrentThreadId();
        return getThreadAffinityFast(threadId);
    }

    /**
     * Get thread affinity with hot path optimization.
     * Uses cached values when possible to avoid system calls.
     */
    @ForceInline
    public OperationResult<BitSet> getThreadAffinityFast(long threadId) {
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();

        // Fast path: check cache first
        BitSet cachedAffinity = cache.getCachedThreadAffinity(threadId);
        if (cachedAffinity != null) {
            return OperationResult.success(cachedAffinity);
        }

        // Slow path: query system and update cache
        return getThreadAffinitySlowPath(threadId, cache);
    }

    /**
     * Set current thread affinity with hot path optimization.
     * Updates cache and uses pre-allocated arrays.
     */
    @ForceInline
    public OperationResult<Void> setCurrentThreadAffinityFast(BitSet cpuMask) {
        long threadId = platformProvider.getCurrentThreadId();
        return setThreadAffinityFast(threadId, cpuMask);
    }

    /**
     * Set thread affinity with hot path optimization.
     * Uses pre-allocated arrays and updates cache.
     */
    @ForceInline
    public OperationResult<Void> setThreadAffinityFast(long threadId, BitSet cpuMask) {
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();

        // Fast validation (no exceptions in hot path)
        if (cpuMask == null || cpuMask.isEmpty()) {
            return OperationResult.failure(new IllegalArgumentException("Invalid CPU mask"));
        }

        // Use pre-allocated array from cache
        long[] maskArray = cache.getTempMaskArray();

        // Convert BitSet to mask array (optimized)
        convertBitSetToMaskArray(cpuMask, maskArray);

        // Perform system call
        int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);

        if (result == 0) {
            // Success: update cache
            cache.setCachedThreadAffinity(threadId, cpuMask);
            return OperationResult.success(null);
        } else {
            // Failure: don't update cache, return error
            return OperationResult.failure(createAffinityException(result, "setThreadAffinity"));
        }
    }

    /**
     * Bulk affinity operations for multiple threads.
     * Optimized for scenarios where many threads need affinity changes.
     */
    @ForceInline
    public int setBulkThreadAffinity(long[] threadIds, BitSet cpuMask) {
        if (threadIds == null || threadIds.length == 0) {
            return 0;
        }

        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        long[] maskArray = cache.getTempMaskArray();
        convertBitSetToMaskArray(cpuMask, maskArray);

        int successCount = 0;
        for (long threadId : threadIds) {
            int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);
            if (result == 0) {
                cache.setCachedThreadAffinity(threadId, cpuMask);
                successCount++;
            }
        }

        return successCount;
    }

    /**
     * Check if thread affinity has changed (cache invalidation check).
     */
    @ForceInline
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
    @DontInline
    private OperationResult<BitSet> getThreadAffinitySlowPath(long threadId, HotPathCache.AffinityCache cache) {
        long[] maskArray = cache.getTempMaskArray();

        int result = platformProvider.getThreadAffinity(threadId, maskArray, maskArray.length);

        if (result == 0) {
            BitSet affinity = convertMaskArrayToBitSet(maskArray);
            cache.setCachedThreadAffinity(threadId, affinity);
            return OperationResult.success(affinity);
        } else {
            return OperationResult.failure(createAffinityException(result, "getThreadAffinity"));
        }
    }

    /**
     * Convert BitSet to long array mask (optimized for hot path).
     */
    @ForceInline
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
    @ForceInline
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
    @DontInline
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