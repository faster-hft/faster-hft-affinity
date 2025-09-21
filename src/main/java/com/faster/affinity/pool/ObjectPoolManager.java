package com.faster.affinity.pool;

import com.faster.affinity.numa.NumaAffinityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry for all object pools in the HFT affinity library.
 * Provides pre-configured pools for common objects and pool lifecycle management.
 */
public final class ObjectPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(ObjectPoolManager.class);

    // Default pool sizes optimized for HFT workloads
    private static final int DEFAULT_BITSET_POOL_SIZE = 32;
    private static final int DEFAULT_ARRAY_POOL_SIZE = 16;
    private static final int DEFAULT_RESULT_POOL_SIZE = 64;

    // Pool registry
    private static final ConcurrentMap<String, ObjectPool<?>> pools = new ConcurrentHashMap<>();

    // Pre-configured pools for common objects
    private static volatile ObjectPool<BitSet> bitSetPool;
    private static volatile ObjectPool<long[]> longArrayPool;
    private static volatile ObjectPool<int[]> intArrayPool;

    // NUMA-aware pools (enabled when NUMA checker is available)
    private static volatile NumaLocalObjectPool<BitSet> numaBitSetPool;
    private static volatile NumaAffinityChecker numaChecker;

    // Hot path optimized references (final after initialization to avoid cache line invalidation)
    private static ObjectPool<BitSet> cachedBitSetPool;
    private static ObjectPool<long[]> cachedLongArrayPool;
    private static ObjectPool<int[]> cachedIntArrayPool;

    static {
        initialize();
    }

    private static void initialize() {
        try {
            // BitSet pool for CPU affinity masks
            bitSetPool = new ThreadLocalObjectPool<>(
                    () -> new BitSet(4096), // Support up to 4096 CPUs
                    DEFAULT_BITSET_POOL_SIZE
            );
            registerPool("bitset", bitSetPool);

            // Long array pool for CPU masks in system calls
            longArrayPool = new ThreadLocalObjectPool<>(
                    () -> new long[64], // 64 longs = 4096 bits
                    DEFAULT_ARRAY_POOL_SIZE
            );
            registerPool("long-array", longArrayPool);

            // Int array pool for temporary calculations
            intArrayPool = new ThreadLocalObjectPool<>(
                    () -> new int[16], // General purpose array
                    DEFAULT_ARRAY_POOL_SIZE
            );
            registerPool("int-array", intArrayPool);

            // Set cached references for hot path optimization (no volatile reads needed)
            cachedBitSetPool = bitSetPool;
            cachedLongArrayPool = longArrayPool;
            cachedIntArrayPool = intArrayPool;

            logger.info("ObjectPoolManager initialized with {} pools", pools.size());

        } catch (Exception e) {
            logger.error("Failed to initialize ObjectPoolManager", e);
            throw new RuntimeException("ObjectPoolManager initialization failed", e);
        }
    }

    /**
     * Get the shared BitSet pool for CPU affinity operations.
     * Returns NUMA-aware pool if available, otherwise standard pool.
     * Hot path optimized - avoids volatile reads.
     */
    public static ObjectPool<BitSet> getBitSetPool() {
        // Check NUMA pool first (volatile read only once)
        NumaLocalObjectPool<BitSet> numaPool = numaBitSetPool;
        if (numaPool != null) {
            return numaPool;
        }
        // Use cached reference to avoid volatile reads in hot path
        return cachedBitSetPool;
    }

    /**
     * Get the shared long array pool for system call parameters.
     * Hot path optimized - avoids volatile reads.
     */
    public static ObjectPool<long[]> getLongArrayPool() {
        return cachedLongArrayPool;
    }

    /**
     * Get the shared int array pool for temporary calculations.
     * Hot path optimized - avoids volatile reads.
     */
    public static ObjectPool<int[]> getIntArrayPool() {
        return cachedIntArrayPool;
    }

    /**
     * Enable NUMA-aware pooling with the provided NUMA checker.
     * Should be called during system initialization.
     */
    public static void enableNumaAwareness(NumaAffinityChecker checker) {
        if (checker == null) {
            logger.warn("Null NUMA checker provided, NUMA awareness not enabled");
            return;
        }

        numaChecker = checker;

        try {
            // Create NUMA-aware BitSet pool
            numaBitSetPool = new NumaLocalObjectPool<>(
                    () -> new BitSet(4096),
                    DEFAULT_BITSET_POOL_SIZE,
                    numaChecker
            );
            registerPool("numa-bitset", numaBitSetPool);

            logger.info("NUMA-aware object pooling enabled");

        } catch (Exception e) {
            logger.error("Failed to enable NUMA-aware pooling", e);
            numaChecker = null;
            numaBitSetPool = null;
        }
    }

    /**
     * Check if NUMA-aware pooling is enabled.
     */
    public static boolean isNumaAwarenessEnabled() {
        return numaChecker != null && numaBitSetPool != null;
    }

    /**
     * Get NUMA locality statistics if NUMA awareness is enabled.
     */
    public static String getNumaLocalityStats() {
        if (numaBitSetPool != null) {
            return numaBitSetPool.getLocalityStats().toString();
        }
        return "NUMA awareness not enabled";
    }

    /**
     * Register a named pool for lookup.
     */
    public static void registerPool(String name, ObjectPool<?> pool) {
        if (name == null || pool == null) {
            throw new IllegalArgumentException("Pool name and instance cannot be null");
        }
        pools.put(name, pool);
        logger.debug("Registered pool: {}", name);
    }

    /**
     * Get a pool by name.
     */
    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> getPool(String name) {
        return (ObjectPool<T>) pools.get(name);
    }

    /**
     * Get all pool statistics for monitoring.
     */
    public static PoolManagerStats getStats() {
        int totalPools = pools.size();
        int healthyPools = 0;
        long totalAcquisitions = 0;
        long totalReleases = 0;

        for (ObjectPool<?> pool : pools.values()) {
            if (pool.isHealthy()) {
                healthyPools++;
            }
            ObjectPool.PoolStats stats = pool.getStats();
            totalAcquisitions += stats.getTotalAcquisitions();
            totalReleases += stats.getTotalReleases();
        }

        return new PoolManagerStats(totalPools, healthyPools, totalAcquisitions, totalReleases);
    }

    /**
     * Clear all pools and reset statistics.
     */
    public static void clearAllPools() {
        logger.info("Clearing all object pools");
        for (ObjectPool<?> pool : pools.values()) {
            try {
                pool.clear();
            } catch (Exception e) {
                logger.warn("Error clearing pool", e);
            }
        }
    }

    /**
     * Shutdown the pool manager and release all resources.
     */
    public static void shutdown() {
        logger.info("Shutting down ObjectPoolManager");
        clearAllPools();
        pools.clear();
    }

    /**
     * Overall statistics for the pool manager.
     */
    public static class PoolManagerStats {
        private final int totalPools;
        private final int healthyPools;
        private final long totalAcquisitions;
        private final long totalReleases;

        public PoolManagerStats(int totalPools, int healthyPools, long totalAcquisitions, long totalReleases) {
            this.totalPools = totalPools;
            this.healthyPools = healthyPools;
            this.totalAcquisitions = totalAcquisitions;
            this.totalReleases = totalReleases;
        }

        public int getTotalPools() { return totalPools; }
        public int getHealthyPools() { return healthyPools; }
        public long getTotalAcquisitions() { return totalAcquisitions; }
        public long getTotalReleases() { return totalReleases; }

        @Override
        public String toString() {
            return String.format("PoolManagerStats{pools=%d/%d healthy, acquisitions=%d, releases=%d}",
                    healthyPools, totalPools, totalAcquisitions, totalReleases);
        }
    }

    private ObjectPoolManager() {
        // Utility class - prevent instantiation
    }
}