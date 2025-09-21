package com.faster.affinity.performance;

import com.faster.affinity.cache.HotPathCache;
import com.faster.affinity.pool.ObjectPoolManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Performance profiler specifically designed for HFT optimization monitoring.
 * Tracks latency, throughput, and efficiency metrics for hot path operations.
 * FIXED: Manual cache line padding replaces @Contended for module compatibility.
 */
public final class HFTPerformanceProfiler {
    // Logger removed - not used in performance-critical code

    // CACHE LINE PADDING: Prevent false sharing with manual 64-byte alignment
    // Operation counters - isolated to their own cache lines
    private volatile long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7; // 64 bytes
    private final LongAdder hotPathOperations = new LongAdder();
    private volatile long pad8, pad9, pad10, pad11, pad12, pad13, pad14, pad15; // 64 bytes
    private final LongAdder fallbackOperations = new LongAdder();
    private volatile long pad16, pad17, pad18, pad19, pad20, pad21, pad22, pad23; // 64 bytes
    private final LongAdder cacheHits = new LongAdder();
    private volatile long pad24, pad25, pad26, pad27, pad28, pad29, pad30, pad31; // 64 bytes
    private final LongAdder cacheMisses = new LongAdder();

    // Latency tracking (in nanoseconds) - separate cache line groups
    private volatile long pad32, pad33, pad34, pad35, pad36, pad37, pad38, pad39; // 64 bytes
    private final AtomicLong totalHotPathLatency = new AtomicLong();
    private volatile long pad40, pad41, pad42, pad43, pad44, pad45, pad46, pad47; // 64 bytes
    private final AtomicLong totalFallbackLatency = new AtomicLong();
    private volatile long pad48, pad49, pad50, pad51, pad52, pad53, pad54, pad55; // 64 bytes
    private final AtomicLong minHotPathLatency = new AtomicLong(Long.MAX_VALUE);
    private volatile long pad56, pad57, pad58, pad59, pad60, pad61, pad62, pad63; // 64 bytes
    private final AtomicLong maxHotPathLatency = new AtomicLong();

    // GC pressure indicators
    private final LongAdder objectsPooled = new LongAdder();
    private final LongAdder objectsAllocated = new LongAdder();

    // NUMA locality tracking
    private final LongAdder numaLocalAllocations = new LongAdder();
    private final LongAdder numaCrossNodeAllocations = new LongAdder();

    private volatile boolean enabled = true;

    public HFTPerformanceProfiler() {
        // Simplified constructor - no dependencies needed
    }

    /**
     * Record a hot path operation with timing.
     */
    public void recordHotPathOperation(long latencyNs) {
        if (!enabled) return;

        hotPathOperations.increment();
        totalHotPathLatency.addAndGet(latencyNs);

        // Update min/max latency
        updateMinLatency(latencyNs);
        updateMaxLatency(latencyNs);
    }

    /**
     * Record a fallback operation (when hot path optimization failed).
     */
    public void recordFallbackOperation(long latencyNs) {
        if (!enabled) return;

        fallbackOperations.increment();
        totalFallbackLatency.addAndGet(latencyNs);
    }

    /**
     * Record cache hit/miss for hot path cache.
     */
    public void recordCacheAccess(boolean hit) {
        if (!enabled) return;

        if (hit) {
            cacheHits.increment();
        } else {
            cacheMisses.increment();
        }
    }

    /**
     * Record object pool usage.
     */
    public void recordObjectPoolUsage(boolean fromPool) {
        if (!enabled) return;

        if (fromPool) {
            objectsPooled.increment();
        } else {
            objectsAllocated.increment();
        }
    }

    /**
     * Record NUMA allocation locality.
     */
    public void recordNumaAllocation(boolean isLocal) {
        if (!enabled) return;

        if (isLocal) {
            numaLocalAllocations.increment();
        } else {
            numaCrossNodeAllocations.increment();
        }
    }

    /**
     * Get comprehensive performance statistics.
     */
    public HFTPerformanceStats getStats() {
        long hotPathOps = hotPathOperations.sum();
        long fallbackOps = fallbackOperations.sum();
        long totalOps = hotPathOps + fallbackOps;

        double hotPathUsageRate = totalOps > 0 ? (double) hotPathOps / totalOps : 0.0;

        long avgHotPathLatency = hotPathOps > 0 ? totalHotPathLatency.get() / hotPathOps : 0;
        long avgFallbackLatency = fallbackOps > 0 ? totalFallbackLatency.get() / fallbackOps : 0;

        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        double cacheHitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;

        long pooled = objectsPooled.sum();
        long allocated = objectsAllocated.sum();
        double poolingRate = (pooled + allocated) > 0 ? (double) pooled / (pooled + allocated) : 0.0;

        long numaLocal = numaLocalAllocations.sum();
        long numaCross = numaCrossNodeAllocations.sum();
        double numaLocalityRate = (numaLocal + numaCross) > 0 ? (double) numaLocal / (numaLocal + numaCross) : 0.0;

        // Get pool manager stats
        ObjectPoolManager.PoolManagerStats poolStats = ObjectPoolManager.getStats();

        return new HFTPerformanceStats(
                hotPathOps, fallbackOps, hotPathUsageRate,
                avgHotPathLatency, avgFallbackLatency,
                minHotPathLatency.get() == Long.MAX_VALUE ? 0 : minHotPathLatency.get(),
                maxHotPathLatency.get(),
                cacheHitRate, poolingRate, numaLocalityRate,
                poolStats
        );
    }

    /**
     * Reset all performance counters.
     */
    public void reset() {
        hotPathOperations.reset();
        fallbackOperations.reset();
        cacheHits.reset();
        cacheMisses.reset();
        totalHotPathLatency.set(0);
        totalFallbackLatency.set(0);
        minHotPathLatency.set(Long.MAX_VALUE);
        maxHotPathLatency.set(0);
        objectsPooled.reset();
        objectsAllocated.reset();
        numaLocalAllocations.reset();
        numaCrossNodeAllocations.reset();
    }

    /**
     * Enable or disable performance tracking.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Check if performance tracking is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    private void updateMinLatency(long latency) {
        long current = minHotPathLatency.get();
        while (latency < current && !minHotPathLatency.compareAndSet(current, latency)) {
            current = minHotPathLatency.get();
        }
    }

    private void updateMaxLatency(long latency) {
        long current = maxHotPathLatency.get();
        while (latency > current && !maxHotPathLatency.compareAndSet(current, latency)) {
            current = maxHotPathLatency.get();
        }
    }

    /**
     * Comprehensive HFT performance statistics.
     */
    public static class HFTPerformanceStats {
        private final long hotPathOperations;
        private final long fallbackOperations;
        private final double hotPathUsageRate;
        private final long avgHotPathLatencyNs;
        private final long avgFallbackLatencyNs;
        private final long minHotPathLatencyNs;
        private final long maxHotPathLatencyNs;
        private final double cacheHitRate;
        private final double poolingRate;
        private final double numaLocalityRate;
        private final ObjectPoolManager.PoolManagerStats poolStats;

        public HFTPerformanceStats(long hotPathOperations, long fallbackOperations, double hotPathUsageRate,
                                 long avgHotPathLatencyNs, long avgFallbackLatencyNs,
                                 long minHotPathLatencyNs, long maxHotPathLatencyNs,
                                 double cacheHitRate, double poolingRate, double numaLocalityRate,
                                 ObjectPoolManager.PoolManagerStats poolStats) {
            this.hotPathOperations = hotPathOperations;
            this.fallbackOperations = fallbackOperations;
            this.hotPathUsageRate = hotPathUsageRate;
            this.avgHotPathLatencyNs = avgHotPathLatencyNs;
            this.avgFallbackLatencyNs = avgFallbackLatencyNs;
            this.minHotPathLatencyNs = minHotPathLatencyNs;
            this.maxHotPathLatencyNs = maxHotPathLatencyNs;
            this.cacheHitRate = cacheHitRate;
            this.poolingRate = poolingRate;
            this.numaLocalityRate = numaLocalityRate;
            this.poolStats = poolStats;
        }

        // Getters
        public long getHotPathOperations() { return hotPathOperations; }
        public long getFallbackOperations() { return fallbackOperations; }
        public double getHotPathUsageRate() { return hotPathUsageRate; }
        public long getAvgHotPathLatencyNs() { return avgHotPathLatencyNs; }
        public long getAvgFallbackLatencyNs() { return avgFallbackLatencyNs; }
        public long getMinHotPathLatencyNs() { return minHotPathLatencyNs; }
        public long getMaxHotPathLatencyNs() { return maxHotPathLatencyNs; }
        public double getCacheHitRate() { return cacheHitRate; }
        public double getPoolingRate() { return poolingRate; }
        public double getNumaLocalityRate() { return numaLocalityRate; }
        public ObjectPoolManager.PoolManagerStats getPoolStats() { return poolStats; }

        /**
         * Calculate the performance improvement factor.
         */
        public double getPerformanceImprovement() {
            if (avgFallbackLatencyNs == 0) return 1.0;
            return (double) avgFallbackLatencyNs / Math.max(1, avgHotPathLatencyNs);
        }

        /**
         * Get total operations per second (if timing data available).
         */
        public double getOperationsPerSecond() {
            long totalOps = hotPathOperations + fallbackOperations;
            if (totalOps == 0) return 0.0;

            // Estimate based on average latency
            long avgLatency = (hotPathOperations * avgHotPathLatencyNs + fallbackOperations * avgFallbackLatencyNs) / totalOps;
            if (avgLatency == 0) return 0.0;

            return 1_000_000_000.0 / avgLatency; // Convert nanoseconds to operations per second
        }

        @Override
        public String toString() {
            return String.format(
                    "HFTPerformanceStats{\n" +
                    "  Operations: hotPath=%d, fallback=%d, hotPathUsage=%.2f%%\n" +
                    "  Latency: hotPathAvg=%dns, fallbackAvg=%dns, min=%dns, max=%dns\n" +
                    "  Improvement: %.2fx faster, %.0f ops/sec\n" +
                    "  Cache: hitRate=%.2f%%\n" +
                    "  Pooling: rate=%.2f%%\n" +
                    "  NUMA: locality=%.2f%%\n" +
                    "  %s\n" +
                    "}",
                    hotPathOperations, fallbackOperations, hotPathUsageRate * 100,
                    avgHotPathLatencyNs, avgFallbackLatencyNs, minHotPathLatencyNs, maxHotPathLatencyNs,
                    getPerformanceImprovement(), getOperationsPerSecond(),
                    cacheHitRate * 100,
                    poolingRate * 100,
                    numaLocalityRate * 100,
                    poolStats
            );
        }
    }
}