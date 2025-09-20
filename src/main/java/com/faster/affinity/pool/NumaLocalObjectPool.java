package com.faster.affinity.pool;

import com.faster.affinity.numa.NumaAffinityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * NUMA-aware object pool that maintains separate pools per NUMA node.
 * Optimizes memory locality by allocating objects on the same NUMA node
 * where they will be accessed.
 */
public class NumaLocalObjectPool<T> implements ObjectPool<T> {
    private static final Logger logger = LoggerFactory.getLogger(NumaLocalObjectPool.class);

    private final Supplier<T> objectFactory;
    private final int maxPoolSizePerNode;
    private final NumaAffinityChecker numaChecker;

    // Per-node pools - using NUMA node ID as key
    private final ConcurrentHashMap<Integer, ThreadLocalObjectPool<T>> nodeLocalPools = new ConcurrentHashMap<>();

    // Fallback pool for unknown/invalid NUMA nodes
    private final ThreadLocalObjectPool<T> fallbackPool;

    // Statistics
    private final AtomicInteger totalLocalAllocations = new AtomicInteger();
    private final AtomicInteger totalCrossNodeAllocations = new AtomicInteger();
    private final AtomicInteger totalFallbackAllocations = new AtomicInteger();

    public NumaLocalObjectPool(Supplier<T> objectFactory, int maxPoolSizePerNode, NumaAffinityChecker numaChecker) {
        this.objectFactory = objectFactory;
        this.maxPoolSizePerNode = maxPoolSizePerNode;
        this.numaChecker = numaChecker;

        // Create fallback pool for non-NUMA scenarios
        this.fallbackPool = new ThreadLocalObjectPool<>(objectFactory, maxPoolSizePerNode);

        logger.debug("Created NUMA-aware object pool with max size {} per node", maxPoolSizePerNode);
    }

    @Override
    public T acquire() {
        if (numaChecker == null) {
            // No NUMA awareness - use fallback pool
            totalFallbackAllocations.incrementAndGet();
            return fallbackPool.acquire();
        }

        int currentNodeId = numaChecker.getCurrentThreadNodeId();

        if (currentNodeId < 0) {
            // Unable to determine current NUMA node - use fallback
            totalFallbackAllocations.incrementAndGet();
            return fallbackPool.acquire();
        }

        // Get or create pool for this NUMA node
        ThreadLocalObjectPool<T> nodePool = getOrCreateNodePool(currentNodeId);
        T obj = nodePool.acquire();

        totalLocalAllocations.incrementAndGet();
        return obj;
    }

    @Override
    public void release(T obj) {
        if (obj == null) {
            return;
        }

        if (numaChecker == null) {
            fallbackPool.release(obj);
            return;
        }

        int currentNodeId = numaChecker.getCurrentThreadNodeId();

        if (currentNodeId < 0) {
            // Unable to determine current NUMA node - use fallback
            fallbackPool.release(obj);
            return;
        }

        // Check if returning to the same NUMA node where we acquired
        if (numaChecker.wouldCauseCrossNumaAccess(obj)) {
            // Cross-NUMA release detected - still better to return to local pool
            numaChecker.recordViolation("object_pool_release", obj);
            totalCrossNodeAllocations.incrementAndGet();
        }

        // Return to current node's pool
        ThreadLocalObjectPool<T> nodePool = getOrCreateNodePool(currentNodeId);
        nodePool.release(obj);
    }

    @Override
    public PoolStats getStats() {
        int totalAcquisitions = totalLocalAllocations.get() + totalCrossNodeAllocations.get() + totalFallbackAllocations.get();
        int totalReleases = 0;
        int currentPoolSize = 0;
        int totalInUse = 0;

        // Aggregate stats from all node pools
        for (ThreadLocalObjectPool<T> nodePool : nodeLocalPools.values()) {
            PoolStats nodeStats = nodePool.getStats();
            totalReleases += nodeStats.getTotalReleases();
            currentPoolSize += nodeStats.getCurrentPoolSize();
            totalInUse += nodeStats.getObjectsInUse();
        }

        // Add fallback pool stats
        PoolStats fallbackStats = fallbackPool.getStats();
        totalReleases += fallbackStats.getTotalReleases();
        currentPoolSize += fallbackStats.getCurrentPoolSize();
        totalInUse += fallbackStats.getObjectsInUse();

        // Calculate NUMA locality rate
        double localityRate = totalAcquisitions > 0 ?
                (double) totalLocalAllocations.get() / totalAcquisitions : 1.0;

        return new NumaPoolStats(totalAcquisitions, totalReleases, currentPoolSize,
                maxPoolSizePerNode * nodeLocalPools.size(), totalInUse, localityRate,
                nodeLocalPools.size(), totalLocalAllocations.get(),
                totalCrossNodeAllocations.get(), totalFallbackAllocations.get());
    }

    @Override
    public void clear() {
        for (ThreadLocalObjectPool<T> nodePool : nodeLocalPools.values()) {
            nodePool.clear();
        }
        fallbackPool.clear();
        nodeLocalPools.clear();

        // Reset statistics
        totalLocalAllocations.set(0);
        totalCrossNodeAllocations.set(0);
        totalFallbackAllocations.set(0);
    }

    @Override
    public boolean isHealthy() {
        if (!fallbackPool.isHealthy()) {
            return false;
        }

        for (ThreadLocalObjectPool<T> nodePool : nodeLocalPools.values()) {
            if (!nodePool.isHealthy()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get or create a thread-local pool for the specified NUMA node.
     */
    private ThreadLocalObjectPool<T> getOrCreateNodePool(int nodeId) {
        return nodeLocalPools.computeIfAbsent(nodeId, id -> {
            logger.debug("Creating thread-local pool for NUMA node {}", id);
            return new ThreadLocalObjectPool<>(objectFactory, maxPoolSizePerNode);
        });
    }

    /**
     * Get detailed NUMA locality statistics.
     */
    public NumaLocalityStats getLocalityStats() {
        int total = totalLocalAllocations.get() + totalCrossNodeAllocations.get() + totalFallbackAllocations.get();
        double localRate = total > 0 ? (double) totalLocalAllocations.get() / total : 0.0;
        double crossRate = total > 0 ? (double) totalCrossNodeAllocations.get() / total : 0.0;
        double fallbackRate = total > 0 ? (double) totalFallbackAllocations.get() / total : 0.0;

        return new NumaLocalityStats(
                totalLocalAllocations.get(),
                totalCrossNodeAllocations.get(),
                totalFallbackAllocations.get(),
                localRate, crossRate, fallbackRate,
                nodeLocalPools.size()
        );
    }

    /**
     * Extended pool statistics with NUMA information.
     */
    public static class NumaPoolStats extends PoolStats {
        private final int activeNodes;
        private final int localAllocations;
        private final int crossNodeAllocations;
        private final int fallbackAllocations;

        public NumaPoolStats(int totalAcquisitions, int totalReleases, int currentPoolSize,
                           int maxPoolSize, int objectsInUse, double hitRate,
                           int activeNodes, int localAllocations,
                           int crossNodeAllocations, int fallbackAllocations) {
            super(totalAcquisitions, totalReleases, currentPoolSize, maxPoolSize, objectsInUse, hitRate);
            this.activeNodes = activeNodes;
            this.localAllocations = localAllocations;
            this.crossNodeAllocations = crossNodeAllocations;
            this.fallbackAllocations = fallbackAllocations;
        }

        public int getActiveNodes() { return activeNodes; }
        public int getLocalAllocations() { return localAllocations; }
        public int getCrossNodeAllocations() { return crossNodeAllocations; }
        public int getFallbackAllocations() { return fallbackAllocations; }

        @Override
        public String toString() {
            return String.format("NumaPoolStats{acquisitions=%d, releases=%d, poolSize=%d/%d, " +
                               "inUse=%d, hitRate=%.2f%%, nodes=%d, local=%d, cross=%d, fallback=%d}",
                    getTotalAcquisitions(), getTotalReleases(), getCurrentPoolSize(),
                    getMaxPoolSize(), getObjectsInUse(), getHitRate() * 100,
                    activeNodes, localAllocations, crossNodeAllocations, fallbackAllocations);
        }
    }

    /**
     * Detailed NUMA locality statistics.
     */
    public static class NumaLocalityStats {
        private final int localAllocations;
        private final int crossNodeAllocations;
        private final int fallbackAllocations;
        private final double localRate;
        private final double crossRate;
        private final double fallbackRate;
        private final int activeNodes;

        public NumaLocalityStats(int localAllocations, int crossNodeAllocations, int fallbackAllocations,
                               double localRate, double crossRate, double fallbackRate, int activeNodes) {
            this.localAllocations = localAllocations;
            this.crossNodeAllocations = crossNodeAllocations;
            this.fallbackAllocations = fallbackAllocations;
            this.localRate = localRate;
            this.crossRate = crossRate;
            this.fallbackRate = fallbackRate;
            this.activeNodes = activeNodes;
        }

        public int getLocalAllocations() { return localAllocations; }
        public int getCrossNodeAllocations() { return crossNodeAllocations; }
        public int getFallbackAllocations() { return fallbackAllocations; }
        public double getLocalRate() { return localRate; }
        public double getCrossRate() { return crossRate; }
        public double getFallbackRate() { return fallbackRate; }
        public int getActiveNodes() { return activeNodes; }

        @Override
        public String toString() {
            return String.format("NumaLocalityStats{local=%d(%.1f%%), cross=%d(%.1f%%), " +
                               "fallback=%d(%.1f%%), activeNodes=%d}",
                    localAllocations, localRate * 100,
                    crossNodeAllocations, crossRate * 100,
                    fallbackAllocations, fallbackRate * 100,
                    activeNodes);
        }
    }
}