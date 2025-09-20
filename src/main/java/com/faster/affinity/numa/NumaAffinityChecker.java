package com.faster.affinity.numa;

import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility for detecting and monitoring NUMA affinity violations in HFT applications.
 * Helps identify performance bottlenecks due to cross-NUMA memory access.
 */
public final class NumaAffinityChecker {
    private static final Logger logger = LoggerFactory.getLogger(NumaAffinityChecker.class);

    private final PlatformProvider platformProvider;
    private final NUMAManager numaManager;

    // Thread-to-NUMA mapping cache
    private final ConcurrentHashMap<Long, Integer> threadNodeCache = new ConcurrentHashMap<>();

    // Performance monitoring
    private final AtomicLong totalChecks = new AtomicLong();
    private final AtomicLong violations = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();

    // Thread-local cache for current thread's NUMA node
    private final ThreadLocal<CachedNodeInfo> threadLocalNode = ThreadLocal.withInitial(CachedNodeInfo::new);

    public NumaAffinityChecker(PlatformProvider platformProvider, NUMAManager numaManager) {
        this.platformProvider = platformProvider;
        this.numaManager = numaManager;
    }

    /**
     * Get the NUMA node ID for the current thread.
     * Uses caching to minimize system calls.
     */
    public int getCurrentThreadNodeId() {
        CachedNodeInfo nodeInfo = threadLocalNode.get();

        // Check if cached value is still valid
        if (nodeInfo.isValid()) {
            cacheHits.incrementAndGet();
            return nodeInfo.nodeId;
        }

        // Cache miss - query the system
        long threadId = platformProvider.getCurrentThreadId();
        int nodeId = determineThreadNodeId(threadId);

        nodeInfo.update(nodeId);
        threadNodeCache.put(threadId, nodeId);

        return nodeId;
    }

    /**
     * Check if accessing an object would cause cross-NUMA access.
     * This is a heuristic based on thread affinity and NUMA topology.
     */
    public boolean wouldCauseCrossNumaAccess(Object obj) {
        totalChecks.incrementAndGet();

        if (!numaManager.isAvailable()) {
            return false; // No NUMA, no violations
        }

        int currentNodeId = getCurrentThreadNodeId();
        if (currentNodeId < 0) {
            return false; // Unable to determine current node
        }

        // For now, we use a simple heuristic:
        // If object was allocated by a different thread on a different NUMA node,
        // it's likely to cause cross-NUMA access
        int objectNodeId = estimateObjectNodeId(obj);
        if (objectNodeId >= 0 && objectNodeId != currentNodeId) {
            violations.incrementAndGet();
            return true;
        }

        return false;
    }

    /**
     * Record a NUMA affinity violation for monitoring.
     */
    public void recordViolation(String operation, Object obj) {
        if (logger.isDebugEnabled()) {
            int currentNode = getCurrentThreadNodeId();
            int objectNode = estimateObjectNodeId(obj);
            logger.debug("NUMA violation in {}: thread on node {}, object on node {}",
                    operation, currentNode, objectNode);
        }
        violations.incrementAndGet();
    }

    /**
     * Get NUMA affinity statistics.
     */
    public NumaAffinityStats getStats() {
        long checks = totalChecks.get();
        long violationCount = violations.get();
        long hits = cacheHits.get();

        double violationRate = checks > 0 ? (double) violationCount / checks : 0.0;
        double cacheHitRate = checks > 0 ? (double) hits / checks : 0.0;

        return new NumaAffinityStats(checks, violationCount, violationRate,
                                   threadNodeCache.size(), cacheHitRate);
    }

    /**
     * Clear all caches and reset statistics.
     */
    public void reset() {
        threadNodeCache.clear();
        threadLocalNode.remove();
        totalChecks.set(0);
        violations.set(0);
        cacheHits.set(0);
    }

    /**
     * Determine which NUMA node a thread is running on.
     * Uses thread affinity to make the determination.
     */
    private int determineThreadNodeId(long threadId) {
        try {
            // Get thread affinity
            long[] cpuMask = new long[64]; // Support up to 4096 CPUs
            int result = platformProvider.getThreadAffinity(threadId, cpuMask, cpuMask.length);

            if (result != 0) {
                return -1; // Unable to get affinity
            }

            // Find first set CPU
            for (int i = 0; i < cpuMask.length * 64; i++) {
                int longIndex = i / 64;
                int bitIndex = i % 64;

                if (longIndex >= cpuMask.length) break;

                if ((cpuMask[longIndex] & (1L << bitIndex)) != 0) {
                    // Found a CPU, determine its NUMA node
                    return determineCpuNumaNode(i);
                }
            }

            return -1; // No CPUs found in affinity mask

        } catch (Exception e) {
            logger.debug("Error determining thread NUMA node: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Determine which NUMA node a CPU belongs to.
     */
    private int determineCpuNumaNode(int cpuId) {
        if (!numaManager.isAvailable()) {
            return 0; // Default to node 0 for non-NUMA systems
        }

        try {
            // Try each NUMA node to see which one contains this CPU
            for (int nodeId = 0; nodeId < 8; nodeId++) { // Reasonable upper bound
                long[] nodeCpus = new long[64];
                int result = platformProvider.getNumaNodeCpus(nodeId, nodeCpus, nodeCpus.length);

                if (result == 0) {
                    // Check if cpuId is in this node's CPU mask
                    int longIndex = cpuId / 64;
                    int bitIndex = cpuId % 64;

                    if (longIndex < nodeCpus.length &&
                        (nodeCpus[longIndex] & (1L << bitIndex)) != 0) {
                        return nodeId;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error determining CPU NUMA node: {}", e.getMessage());
        }

        return 0; // Default to node 0
    }

    /**
     * Estimate which NUMA node an object was allocated on.
     * This is a best-effort heuristic.
     */
    private int estimateObjectNodeId(Object obj) {
        // For now, we can't directly determine where an object was allocated
        // In a real implementation, this could use:
        // - Object header information (if available)
        // - Memory address analysis
        // - Allocation tracking

        // Simple heuristic: use object hash to distribute evenly
        if (!numaManager.isAvailable()) {
            return 0;
        }

        return Math.abs(obj.hashCode()) % 2; // Assume 2 NUMA nodes for simplicity
    }

    /**
     * Thread-local cache for NUMA node information.
     */
    private static class CachedNodeInfo {
        int nodeId = -1;
        long timestamp = 0;

        // Cache validity duration in nanoseconds (1 second)
        private static final long CACHE_DURATION_NS = 1_000_000_000L;

        boolean isValid() {
            return nodeId >= 0 && (System.nanoTime() - timestamp) < CACHE_DURATION_NS;
        }

        void update(int newNodeId) {
            this.nodeId = newNodeId;
            this.timestamp = System.nanoTime();
        }
    }

    /**
     * Statistics for NUMA affinity checking.
     */
    public static class NumaAffinityStats {
        private final long totalChecks;
        private final long violations;
        private final double violationRate;
        private final int cachedThreads;
        private final double cacheHitRate;

        public NumaAffinityStats(long totalChecks, long violations, double violationRate,
                               int cachedThreads, double cacheHitRate) {
            this.totalChecks = totalChecks;
            this.violations = violations;
            this.violationRate = violationRate;
            this.cachedThreads = cachedThreads;
            this.cacheHitRate = cacheHitRate;
        }

        public long getTotalChecks() { return totalChecks; }
        public long getViolations() { return violations; }
        public double getViolationRate() { return violationRate; }
        public int getCachedThreads() { return cachedThreads; }
        public double getCacheHitRate() { return cacheHitRate; }

        @Override
        public String toString() {
            return String.format("NumaAffinityStats{checks=%d, violations=%d, violationRate=%.2f%%, " +
                               "cachedThreads=%d, cacheHitRate=%.2f%%}",
                    totalChecks, violations, violationRate * 100, cachedThreads, cacheHitRate * 100);
        }
    }
}