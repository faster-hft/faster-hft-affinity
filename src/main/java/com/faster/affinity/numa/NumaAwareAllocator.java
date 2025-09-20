package com.faster.affinity.numa;

/**
 * Interface for NUMA-aware object allocation in HFT systems.
 * Provides mechanisms to allocate objects on specific NUMA nodes
 * to minimize cross-node memory access latency.
 */
public interface NumaAwareAllocator {

    /**
     * Allocate an object on the specified NUMA node.
     *
     * @param nodeId target NUMA node ID
     * @param objectClass class of object to allocate
     * @param <T> object type
     * @return allocated object instance
     * @throws NumaAllocationException if allocation fails
     */
    <T> T allocateOnNode(int nodeId, Class<T> objectClass) throws NumaAllocationException;

    /**
     * Allocate an object on the NUMA node closest to the current thread.
     *
     * @param objectClass class of object to allocate
     * @param <T> object type
     * @return allocated object instance
     * @throws NumaAllocationException if allocation fails
     */
    <T> T allocateLocal(Class<T> objectClass) throws NumaAllocationException;

    /**
     * Get the NUMA node ID for the current thread.
     *
     * @return NUMA node ID, or -1 if not determinable
     */
    int getCurrentNodeId();

    /**
     * Get the NUMA node ID where an object is allocated.
     *
     * @param obj object to check
     * @return NUMA node ID, or -1 if not determinable
     */
    int getObjectNodeId(Object obj);

    /**
     * Check if cross-NUMA access would occur between thread and object.
     *
     * @param obj object to check
     * @return true if access would cross NUMA boundaries
     */
    boolean isCrossNumaAccess(Object obj);

    /**
     * Get allocation statistics for monitoring.
     *
     * @return allocation statistics
     */
    NumaAllocationStats getStats();

    /**
     * Exception thrown when NUMA allocation fails.
     */
    class NumaAllocationException extends Exception {
        public NumaAllocationException(String message) {
            super(message);
        }

        public NumaAllocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Statistics for NUMA allocation monitoring.
     */
    class NumaAllocationStats {
        private final long localAllocations;
        private final long crossNodeAllocations;
        private final long totalAllocations;
        private final double localityRate;

        public NumaAllocationStats(long localAllocations, long crossNodeAllocations,
                                 long totalAllocations, double localityRate) {
            this.localAllocations = localAllocations;
            this.crossNodeAllocations = crossNodeAllocations;
            this.totalAllocations = totalAllocations;
            this.localityRate = localityRate;
        }

        public long getLocalAllocations() { return localAllocations; }
        public long getCrossNodeAllocations() { return crossNodeAllocations; }
        public long getTotalAllocations() { return totalAllocations; }
        public double getLocalityRate() { return localityRate; }

        @Override
        public String toString() {
            return String.format("NumaAllocationStats{local=%d, crossNode=%d, total=%d, locality=%.2f%%}",
                    localAllocations, crossNodeAllocations, totalAllocations, localityRate * 100);
        }
    }
}