package com.faster.affinity.core;

import com.faster.affinity.cache.HotPathCache;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.exceptions.ErrorCodes;
import com.faster.affinity.platform.PlatformProvider;
import com.faster.affinity.performance.HFTPerformanceProfiler;
import com.faster.affinity.pool.ObjectPoolManager;
import com.faster.affinity.utils.ThreadLocalManager;
import com.faster.affinity.annotations.HotPath;
import com.faster.affinity.annotations.ColdPath;

import java.util.BitSet;

/**
 * Lock-free, high-performance affinity operations for HFT hot paths.
 * Uses thread-local caching and pre-allocated objects to minimize latency.
 */
public final class LockFreeAffinityOperations {
    // Logger removed - not used in performance-critical code

    // Compile-time flag for profiling (set to false for production)
    private static final boolean PROFILING_ENABLED = false;

    // Thread-local empty BitSet to avoid race conditions while preventing allocations
    // MEMORY LEAK FIX: Use ThreadLocalManager for proper cleanup in thread pools
    private static final ThreadLocalManager.ManagedThreadLocal<BitSet> THREAD_LOCAL_EMPTY_BITSET =
        ThreadLocalManager.create("lockfree-empty-bitset", () -> new BitSet(), BitSet::clear);

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
        // NULL SAFETY FIX: Check platform provider before use to prevent NPE
        if (platformProvider == null) {
            return OperationResult.systemCallFailure();
        }

        // BRANCH PREDICTION OPTIMIZATION: Assume provider is always valid (common case)
        // This structures the code so the CPU predicts the normal path correctly
        long threadId = platformProvider.getCurrentThreadId();
        if (threadId != 0) { // getCurrentThreadId() returns 0 on failure
            return getThreadAffinityFast(threadId);
        }
        // Rare failure case - moved to end to help branch predictor
        return OperationResult.systemCallFailure();
    }

    /**
     * Get thread affinity with hot path optimization.
     * Uses cached values when possible to avoid system calls.
     */
    @HotPath(value = "Lock-free thread affinity query", expectedFrequency = 500000, targetLatencyNs = 150)
    public OperationResult<BitSet> getThreadAffinityFast(long threadId) {
        // BRANCH PREDICTION OPTIMIZATION: Structure assuming cache hit (common case)
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        BitSet cachedAffinity = cache.getCachedThreadAffinity(threadId);

        // Predict cache hit as the common case - structure code accordingly
        if (cachedAffinity != null && !cache.isClosed()) {
            return OperationResult.success(cachedAffinity);
        }

        // Cache miss or cache closed - less frequent paths combined for branch prediction
        if (!cache.isClosed()) {
            return getThreadAffinitySlowPath(threadId, cache);
        }

        // Very rare cache closed case
        return OperationResult.cacheClosedFailure();
    }

    /**
     * Set current thread affinity with hot path optimization.
     * Updates cache and uses pre-allocated arrays.
     */
    @HotPath(value = "Lock-free current thread affinity update", expectedFrequency = 100000, targetLatencyNs = 200)
    public OperationResult<Void> setCurrentThreadAffinityFast(BitSet cpuMask) {
        // NULL SAFETY FIX: Check platform provider before use to prevent NPE
        if (platformProvider == null) {
            return OperationResult.systemCallFailure();
        }

        // BRANCH PREDICTION OPTIMIZATION: Validate cpuMask first (most common failure)
        if (cpuMask != null && !cpuMask.isEmpty()) {
            long threadId = platformProvider.getCurrentThreadId();
            if (threadId != 0) {
                return setThreadAffinityFast(threadId, cpuMask);
            }
            return OperationResult.systemCallFailure();
        }
        // Invalid parameter case - structured for predictable branching
        return OperationResult.invalidParameterFailure();
    }

    /**
     * Set thread affinity with hot path optimization.
     * Uses pre-allocated arrays and updates cache.
     */
    @HotPath(value = "Lock-free thread affinity update", expectedFrequency = 50000, targetLatencyNs = 250)
    public OperationResult<Void> setThreadAffinityFast(long threadId, BitSet cpuMask) {
        // NULL SAFETY FIX: Check platform provider before use to prevent NPE
        if (platformProvider == null) {
            return OperationResult.systemCallFailure();
        }

        // BRANCH PREDICTION OPTIMIZATION: Structure for success path prediction
        // Get thread-local cache (common case: cache is available)
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        long[] maskArray = cache.getTempMaskArray();

        // Branch on cache availability (extremely rare failure)
        if (maskArray != null && !cache.isClosed()) {
            // Convert BitSet to mask array (thread-safe operation)
            convertBitSetToMaskArray(cpuMask, maskArray);

            // Perform system call - success is the expected case
            int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);
            if (result == 0) {
                // Success path: update cache and return
                cache.setCachedThreadAffinity(threadId, cpuMask);
                return OperationResult.success(null);
            }
            // System call failure - less common but still structured for prediction
            return OperationResult.systemCallFailure();
        }

        // Cache failure case - very rare, moved to end for branch prediction
        return OperationResult.cacheClosedFailure();
    }

    /**
     * Bulk affinity operations for multiple threads.
     * Optimized for scenarios where many threads need affinity changes with vectorized system calls.
     */
    @HotPath(value = "Bulk thread affinity operations", expectedFrequency = 10000, targetLatencyNs = 500)
    public int setBulkThreadAffinity(long[] threadIds, BitSet cpuMask) {
        // NULL SAFETY FIX: Check platform provider before use to prevent NPE
        if (platformProvider == null) {
            return 0;
        }

        // BRANCH PREDICTION OPTIMIZATION: Structure for common success case
        // Assume valid inputs and available cache (normal operation)
        if (threadIds != null && threadIds.length > 0 && cpuMask != null && !cpuMask.isEmpty()) {
            HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
            long[] maskArray = cache.getTempMaskArray();

            if (maskArray != null && !cache.isClosed()) {
                // Convert BitSet once for all operations
                convertBitSetToMaskArray(cpuMask, maskArray);

                // Try vectorized bulk operation first (predict supported)
                int successCount = tryBulkSystemCall(threadIds, maskArray, cache, cpuMask);
                if (successCount >= 0) {
                    return successCount; // Bulk operation succeeded
                }

                // Fall back to individual calls if bulk not supported
                return performIndividualSystemCalls(threadIds, maskArray, cache, cpuMask);
            }
        }

        // Error cases - invalid inputs or cache issues (rare)
        return 0;
    }

    /**
     * Attempt vectorized bulk system call for maximum performance.
     */
    private int tryBulkSystemCall(long[] threadIds, long[] maskArray,
                                 HotPathCache.AffinityCache cache, BitSet cpuMask) {
        // Check if platform supports bulk operations
        if (platformProvider instanceof BatchCapablePlatformProvider) {
            BatchCapablePlatformProvider batchProvider = (BatchCapablePlatformProvider) platformProvider;
            int[] results = batchProvider.setBulkThreadAffinity(threadIds, maskArray, maskArray.length);

            if (results != null && results.length == threadIds.length) {
                int successCount = 0;
                // Update cache for successful operations only
                for (int i = 0; i < results.length; i++) {
                    if (results[i] == 0) {
                        cache.setCachedThreadAffinity(threadIds[i], cpuMask);
                        successCount++;
                    }
                }
                return successCount;
            }
        }

        return -1; // Bulk operation not supported
    }

    /**
     * Fall back to individual system calls with optimized batching.
     */
    private int performIndividualSystemCalls(long[] threadIds, long[] maskArray,
                                           HotPathCache.AffinityCache cache, BitSet cpuMask) {
        int successCount = 0;

        // Process in batches to reduce system call overhead
        final int batchSize = Math.min(8, threadIds.length); // Optimal batch size for cache locality

        for (int i = 0; i < threadIds.length; i += batchSize) {
            int endIndex = Math.min(i + batchSize, threadIds.length);

            // Process batch
            for (int j = i; j < endIndex; j++) {
                int result = platformProvider.setThreadAffinity(threadIds[j], maskArray, maskArray.length);
                if (result == 0) {
                    // Batch cache updates for better performance
                    cache.setCachedThreadAffinity(threadIds[j], cpuMask);
                    successCount++;
                }
            }

            // Small yield between batches to prevent CPU monopolization
            if (endIndex < threadIds.length) {
                Thread.onSpinWait();
            }
        }

        return successCount;
    }

    /**
     * Interface for platform providers that support batch operations.
     */
    public interface BatchCapablePlatformProvider {
        /**
         * Set affinity for multiple threads in a single system call.
         * @param threadIds Array of thread IDs
         * @param maskArray CPU mask array
         * @param maskLength Length of mask array
         * @return Array of result codes (0 = success, non-zero = error)
         */
        int[] setBulkThreadAffinity(long[] threadIds, long[] maskArray, int maskLength);
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
        if (maskArray == null) {
            return OperationResult.cacheClosedFailure();
        }

        if (platformProvider == null) {
            return OperationResult.systemCallFailure();
        }
        int result = platformProvider.getThreadAffinity(threadId, maskArray, maskArray.length);

        if (result == 0) {
            BitSet affinity = convertMaskArrayToBitSet(maskArray);
            cache.setCachedThreadAffinity(threadId, affinity);
            return OperationResult.success(affinity);
        } else {
            return OperationResult.systemCallFailure();
        }
    }

    /**
     * Convert BitSet to long array mask (optimized for hot path).
     * Thread-safe version that doesn't modify shared state.
     */
    @HotPath(value = "BitSet to mask array conversion", expectedFrequency = 200000, targetLatencyNs = 100)
    private static void convertBitSetToMaskArray(BitSet bitSet, long[] maskArray) {
        // Validate inputs
        if (bitSet == null || maskArray == null) {
            return;
        }

        // Clear the array first - this is safe as each thread has its own array
        java.util.Arrays.fill(maskArray, 0);

        // Set bits efficiently using lock-free operations
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            int longIndex = i >>> 6; // Faster division by 64
            int bitIndex = i & 63;   // Faster modulo 64

            if (longIndex < maskArray.length) {
                // This is thread-safe as each thread operates on its own maskArray
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
     * Uses cached BitSet to avoid allocation.
     */
    @HotPath(value = "Mask array to BitSet conversion", expectedFrequency = 200000, targetLatencyNs = 100)
    private BitSet convertMaskArrayToBitSet(long[] maskArray) {
        if (maskArray == null) {
            // Return thread-local empty BitSet to avoid race conditions
            return THREAD_LOCAL_EMPTY_BITSET.get();
        }

        // Use cached BitSet for intermediate operations
        HotPathCache.AffinityCache cache = hotPathCache.getAffinityCache();
        BitSet temp = cache.getTempBitSet();
        if (temp == null) {
            // Fallback: use thread-local empty BitSet to avoid race conditions
            temp = THREAD_LOCAL_EMPTY_BITSET.get();
        }

        // Convert with optimized bit operations
        for (int longIndex = 0; longIndex < maskArray.length; longIndex++) {
            long mask = maskArray[longIndex];
            if (mask != 0) {
                // Use bit scan for better performance
                while (mask != 0) {
                    int bitIndex = Long.numberOfTrailingZeros(mask);
                    temp.set((longIndex << 6) + bitIndex); // longIndex * 64 + bitIndex
                    mask &= mask - 1; // Clear the lowest set bit
                }
            }
        }

        // CRITICAL FIX: Never return internal temp BitSet directly to prevent cache corruption
        // Use second pre-allocated BitSet from cache for safe return
        BitSet result = cache.getTempBitSet2();
        if (result != null) {
            result.clear();
            // Manually copy bits to avoid potential allocation in or() operation
            copyBitsManually(temp, result);
            return result;
        }

        // Last resort: use thread-local BitSet and copy manually
        BitSet safeResult = THREAD_LOCAL_EMPTY_BITSET.get();
        safeResult.clear();
        copyBitsManually(temp, safeResult);
        return safeResult;
    }

    /**
     * Manually copy bits from source to destination BitSet to avoid allocations.
     * This prevents potential memory allocation that could occur in BitSet.or() if resizing is needed.
     */
    @HotPath(value = "Manual bit copying", expectedFrequency = 200000, targetLatencyNs = 50)
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
     * Create appropriate exception for error codes (don't inline to keep hot path fast).
     * Note: This method exists but is intentionally not used in hot paths to avoid allocations.
     * Error handling in hot paths uses OperationResult instead of exceptions.
     */
    @ColdPath("Error exception creation")
    private static RuntimeException createAffinityException(int errorCode, String operation) {
        switch (errorCode) {
            case 1: // EPERM
                return new SecurityException(operation + " failed: insufficient permissions") {
                    @Override
                    public synchronized Throwable fillInStackTrace() {
                        return this; // Disable stack trace for performance
                    }
                };
            case 3: // ESRCH
                return new IllegalArgumentException(operation + " failed: invalid thread ID") {
                    @Override
                    public synchronized Throwable fillInStackTrace() {
                        return this; // Disable stack trace for performance
                    }
                };
            case 22: // EINVAL
                return new IllegalArgumentException(operation + " failed: invalid parameters") {
                    @Override
                    public synchronized Throwable fillInStackTrace() {
                        return this; // Disable stack trace for performance
                    }
                };
            default:
                return new RuntimeException(operation + " failed with error code: " + errorCode) {
                    @Override
                    public synchronized Throwable fillInStackTrace() {
                        return this; // Disable stack trace for performance
                    }
                };
        }
    }
}