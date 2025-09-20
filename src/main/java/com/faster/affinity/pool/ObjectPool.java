package com.faster.affinity.pool;

/**
 * High-performance object pool interface for HFT applications.
 * Designed for minimal allocation and maximum throughput.
 */
public interface ObjectPool<T> {

    /**
     * Acquire an object from the pool.
     * Must be paired with a corresponding release() call.
     *
     * @return pooled object instance, never null
     */
    T acquire();

    /**
     * Return an object to the pool for reuse.
     * Object must have been acquired from this pool.
     *
     * @param obj object to return to pool
     */
    void release(T obj);

    /**
     * Get current pool statistics.
     *
     * @return pool statistics
     */
    PoolStats getStats();

    /**
     * Clear the pool and release all resources.
     */
    void clear();

    /**
     * Check if the pool is healthy and functioning.
     *
     * @return true if pool is operational
     */
    boolean isHealthy();

    /**
     * Statistics for pool monitoring and optimization.
     */
    class PoolStats {
        private final int totalAcquisitions;
        private final int totalReleases;
        private final int currentPoolSize;
        private final int maxPoolSize;
        private final int objectsInUse;
        private final double hitRate;

        public PoolStats(int totalAcquisitions, int totalReleases, int currentPoolSize,
                        int maxPoolSize, int objectsInUse, double hitRate) {
            this.totalAcquisitions = totalAcquisitions;
            this.totalReleases = totalReleases;
            this.currentPoolSize = currentPoolSize;
            this.maxPoolSize = maxPoolSize;
            this.objectsInUse = objectsInUse;
            this.hitRate = hitRate;
        }

        public int getTotalAcquisitions() { return totalAcquisitions; }
        public int getTotalReleases() { return totalReleases; }
        public int getCurrentPoolSize() { return currentPoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public int getObjectsInUse() { return objectsInUse; }
        public double getHitRate() { return hitRate; }

        @Override
        public String toString() {
            return String.format("PoolStats{acquisitions=%d, releases=%d, poolSize=%d/%d, inUse=%d, hitRate=%.2f%%}",
                    totalAcquisitions, totalReleases, currentPoolSize, maxPoolSize, objectsInUse, hitRate * 100);
        }
    }
}