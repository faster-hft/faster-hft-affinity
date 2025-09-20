package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.exceptions.UnsupportedOperationException;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.faster.affinity.exceptions.ErrorCodes.*;


/**
 * Production-ready NUMA memory management with node affinity,
 * memory allocation, and topology analysis.
 * Fixed version without fake default values.
 */
public final class NUMAManager {
    private static final Logger logger = LoggerFactory.getLogger(NUMAManager.class);

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // NUMA node information cache
    private final ConcurrentHashMap<Integer, NumaNodeInfo> nodeInfoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, NumaAllocation> allocations = new ConcurrentHashMap<>();

    // NUMA topology
    private volatile NumaTopology topology;

    public NUMAManager(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        try {
            logger.info("Initializing NUMAManager...");

            // Check if NUMA is actually available
            if (!isAvailable()) {
                // Don't throw exception, just log and mark as unavailable
                logger.info("NUMA not available on this system, using single node configuration");
                topology = new NumaTopology(1, Collections.singletonList(0));

                // Create a default single node info
                NumaNodeInfo singleNode = new NumaNodeInfo(0, new BitSet(),
                        new NumaNodeMemoryInfo(-1, -1));
                nodeInfoCache.put(0, singleNode);

                initialized.set(true);
                return;
            }

            // Discover NUMA topology
            topology = discoverNumaTopology();

            // Cache node information
            for (int nodeId = 0; nodeId < topology.getNodeCount(); nodeId++) {
                try {
                    NumaNodeInfo nodeInfo = gatherNodeInfo(nodeId);
                    nodeInfoCache.put(nodeId, nodeInfo);
                } catch (Exception e) {
                    logger.warn("Failed to gather info for NUMA node {}: {}", nodeId, e.getMessage());
                    // Continue with other nodes
                }
            }

            initialized.set(true);
            logger.info("NUMAManager initialized with {} nodes", topology.getNodeCount());

        } catch (Exception e) {
            // Still mark as initialized with single node to prevent blocking
            topology = new NumaTopology(1, Collections.singletonList(0));
            NumaNodeInfo singleNode = new NumaNodeInfo(0, new BitSet(),
                    new NumaNodeMemoryInfo(-1, -1));
            nodeInfoCache.put(0, singleNode);
            initialized.set(true);

            logger.warn("NUMAManager initialization encountered issues, using single node configuration: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            int nodeCount = platformProvider.getNumaNodeCount();
            // NUMA is available if we can detect any nodes (including single node systems)
            return nodeCount >= 1;
        } catch (Exception e) {
            logger.debug("NUMA not available: {}", e.getMessage());
            return false;
        }
    }

    // NUMA Node Information
    public NumaTopology getTopology() {
        checkInitialized();
        return topology;
    }

    public int getNodeCount() {
        checkInitialized();
        return topology.getNodeCount();
    }

    public OperationResult<BitSet> getNodeCpus(int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, return all CPUs
            if (!isAvailable() && nodeId == 0) {
                BitSet allCpus = new BitSet();
                int cpuCount = platformProvider.getCpuCount();
                for (int i = 0; i < cpuCount; i++) {
                    allCpus.set(i);
                }
                return OperationResult.success(allCpus);
            }

            validateNodeId(nodeId);

            NumaNodeInfo nodeInfo = nodeInfoCache.get(nodeId);
            if (nodeInfo != null && config.isCachingEnabled()) {
                return OperationResult.success(nodeInfo.getCpuMask());
            }

            long[] maskArray = new long[16];
            int result = platformProvider.getNumaNodeCpus(nodeId, maskArray, platformProvider.getCpuCount());

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getNodeCpus");
            }

            BitSet cpuMask = AffinityManager.longArrayToBitSet(maskArray, platformProvider.getCpuCount());
            return OperationResult.success(cpuMask);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<NumaNodeMemoryInfo> getNodeMemoryInfo(int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, return unknown memory info
            if (!isAvailable() && nodeId == 0) {
                return OperationResult.success(new NumaNodeMemoryInfo(-1, -1));
            }

            validateNodeId(nodeId);

            NumaNodeInfo nodeInfo = nodeInfoCache.get(nodeId);
            if (nodeInfo != null && config.isCachingEnabled()) {
                return OperationResult.success(nodeInfo.getMemoryInfo());
            }

            long[] memoryInfo = new long[2]; // [total, free]
            int result = platformProvider.getNumaNodeMemoryInfo(nodeId, memoryInfo);

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getNodeMemoryInfo");
            }

            // Check if we got valid data
            if (memoryInfo[0] < 0 && memoryInfo[1] >= 0) {
                // Total not available but free is - partial info
                logger.debug("NUMA node {} total memory not available, only free memory", nodeId);
            }

            NumaNodeMemoryInfo info = new NumaNodeMemoryInfo(memoryInfo[0], memoryInfo[1]);
            return OperationResult.success(info);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Double> getNodeUtilization(int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, return 0
            if (!isAvailable() && nodeId == 0) {
                return OperationResult.success(0.0);
            }

            validateNodeId(nodeId);

            double utilization = platformProvider.getNumaNodeUtilization(nodeId);
            if (utilization < 0) {
                throw new PerformanceMonitorException("getNodeUtilization", "numa_utilization");
            }

            return OperationResult.success(utilization);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Long> getNodeDistance(int fromNode, int toNode) {
        try {
            checkInitialized();

            // For single-node systems, distance is always 10 (local)
            if (!isAvailable() && fromNode == 0 && toNode == 0) {
                return OperationResult.success(10L);
            }

            validateNodeId(fromNode);
            validateNodeId(toNode);

            long distance = platformProvider.getNumaNodeDistance(fromNode, toNode);

            // Check if platform actually provides distances
            if (distance < 0) {
                // Platform doesn't support NUMA distances
                return OperationResult.failure(
                        new UnsupportedOperationException(
                                "getNodeDistance",
                                "NUMA distances not available on this platform"
                        )
                );
            }

            return OperationResult.success(distance);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // NUMA Affinity Operations

    public OperationResult<Void> setThreadNumaAffinity(long threadId, int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, this is a no-op
            if (!isAvailable() && nodeId == 0) {
                return OperationResult.success(null);
            }

            validateNodeId(nodeId);
            validateThreadId(threadId);

            int result = platformProvider.setNumaAffinity(threadId, nodeId);
            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "setThreadNumaAffinity");
            }

            logger.debug("Set thread {} NUMA affinity to node {}", threadId, nodeId);
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Void> setThreadNodeCoreAffinity(long threadId, int nodeId, int coreId) {
        try {
            checkInitialized();

            // For single-node systems, just validate core ID
            if (!isAvailable() && nodeId == 0) {
                if (coreId >= 0 && coreId < platformProvider.getCpuCount()) {
                    return OperationResult.success(null);
                } else {
                    return OperationResult.failure(
                            new InvalidParameterException("setThreadNodeCoreAffinity", "coreId", coreId));
                }
            }

            validateNodeId(nodeId);
            validateThreadId(threadId);

            // Validate that the core belongs to the specified node
            OperationResult<BitSet> nodeCpusResult = getNodeCpus(nodeId);
            if (!nodeCpusResult.isSuccess()) {
                return OperationResult.failure(nodeCpusResult.getError());
            }

            if (!nodeCpusResult.getValue().get(coreId)) {
                return OperationResult.failure(new InvalidParameterException("setThreadNodeCoreAffinity",
                        String.format("Core %d does not belong to NUMA node %d", coreId, nodeId)));
            }

            int result = platformProvider.setNumaNodeCoreAffinity(threadId, nodeId, coreId);
            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "setThreadNodeCoreAffinity");
            }

            logger.debug("Set thread {} affinity to node {} core {}", threadId, nodeId, coreId);
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // NUMA Memory Operations

    public OperationResult<Long> allocateMemory(int nodeId, long size) {
        try {
            checkInitialized();

            // For single-node systems, regular allocation
            if (!isAvailable() && nodeId == 0) {
                // Can't do NUMA allocation, return 0 to indicate failure
                return OperationResult.failure(
                        new UnsupportedOperationException("allocateMemory",
                                "NUMA memory allocation not available on single-node system"));
            }

            validateNodeId(nodeId);
            validateSize(size);

            long address = platformProvider.allocateNumaMemory(nodeId, size);
            if (address == 0) {
                throw new SystemCallException("allocateMemory", "numa_alloc", null)
                        .addContext("node_id", nodeId)
                        .addContext("size", size);
            }

            // Track the allocation
            NumaAllocation allocation = new NumaAllocation(address, size, nodeId, System.currentTimeMillis());
            allocations.put(address, allocation);

            logger.debug("Allocated {} bytes on NUMA node {} at address 0x{}",
                    size, nodeId, Long.toHexString(address));

            return OperationResult.success(address);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Void> freeMemory(long address) {
        try {
            checkInitialized();
            validateAddress(address);

            NumaAllocation allocation = allocations.get(address);
            if (allocation == null) {
                return OperationResult.failure(new InvalidParameterException("freeMemory",
                        "address", "0x" + Long.toHexString(address)));
            }

            int result = platformProvider.freeNumaMemory(address, allocation.getSize());
            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "freeMemory");
            }

            allocations.remove(address);
            logger.debug("Freed memory at address 0x{} ({} bytes)",
                    Long.toHexString(address), allocation.getSize());

            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Void> bindMemoryRange(long address, long size, int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, this is unsupported
            if (!isAvailable()) {
                return OperationResult.failure(
                        new UnsupportedOperationException("bindMemoryRange",
                                "Memory binding not available on single-node system"));
            }

            validateAddress(address);
            validateSize(size);
            validateNodeId(nodeId);

            int result = platformProvider.bindMemoryRange(address, size, nodeId);

            if (result == ERROR_NOT_SUPPORTED) {
                return OperationResult.failure(
                        new UnsupportedOperationException(
                                "bindMemoryRange",
                                "Memory binding not supported on this platform"
                        )
                );
            }

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "bindMemoryRange");
            }

            logger.debug("Bound memory range 0x{} ({} bytes) to NUMA node {}",
                    Long.toHexString(address), size, nodeId);

            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Void> setMemoryPolicy(int nodeId) {
        try {
            checkInitialized();

            // For single-node systems, this is a no-op
            if (!isAvailable() && nodeId == 0) {
                return OperationResult.success(null);
            }

            validateNodeId(nodeId);

            int result = platformProvider.setNumaMemoryPolicy(nodeId);

            if (result == ERROR_NOT_SUPPORTED) {
                return OperationResult.failure(
                        new UnsupportedOperationException(
                                "setMemoryPolicy",
                                "NUMA memory policy not supported on this platform"
                        )
                );
            }

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "setMemoryPolicy");
            }

            logger.debug("Set NUMA memory policy to prefer node {}", nodeId);
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // Analysis and Optimization

    public OperationResult<Integer> findOptimalNodeForThread(long threadId) {
        try {
            checkInitialized();
            validateThreadId(threadId);

            // For single-node systems, always return node 0
            if (!isAvailable()) {
                return OperationResult.success(0);
            }

            // Simple heuristic: find node with lowest utilization
            int bestNode = 0;
            double lowestUtilization = Double.MAX_VALUE;

            for (int nodeId = 0; nodeId < getNodeCount(); nodeId++) {
                OperationResult<Double> utilizationResult = getNodeUtilization(nodeId);
                if (utilizationResult.isSuccess()) {
                    double utilization = utilizationResult.getValue();
                    if (utilization < lowestUtilization) {
                        lowestUtilization = utilization;
                        bestNode = nodeId;
                    }
                }
            }

            return OperationResult.success(bestNode);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<List<Integer>> getNodesWithAvailableMemory(long requiredBytes) {
        checkInitialized();

        List<Integer> availableNodes = new ArrayList<>();

        for (int nodeId = 0; nodeId < getNodeCount(); nodeId++) {
            OperationResult<NumaNodeMemoryInfo> memoryResult = getNodeMemoryInfo(nodeId);
            if (memoryResult.isSuccess()) {
                NumaNodeMemoryInfo info = memoryResult.getValue();
                // Only check if we have valid free memory info
                if (info.getFreeBytes() >= 0 && info.getFreeBytes() >= requiredBytes) {
                    availableNodes.add(nodeId);
                }
            }
        }

        return OperationResult.success(availableNodes);
    }

    public OperationResult<NumaDistanceMatrix> getDistanceMatrix() {
        checkInitialized();

        // For single-node systems, return a simple 1x1 matrix
        if (!isAvailable()) {
            long[][] distances = {{10}}; // Local distance
            return OperationResult.success(new NumaDistanceMatrix(distances));
        }

        int nodeCount = getNodeCount();
        long[][] distances = new long[nodeCount][nodeCount];
        boolean hasValidDistances = false;

        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                OperationResult<Long> result = getNodeDistance(i, j);
                if (result.isSuccess()) {
                    distances[i][j] = result.getValue();
                    hasValidDistances = true;
                } else {
                    distances[i][j] = -1; // Not available
                }
            }
        }

        if (!hasValidDistances) {
            return OperationResult.failure(
                    new UnsupportedOperationException(
                            "getDistanceMatrix",
                            "NUMA distances not available on this platform"
                    )
            );
        }

        return OperationResult.success(new NumaDistanceMatrix(distances));
    }

    public List<NumaAllocation> getAllocations() {
        return new ArrayList<>(allocations.values());
    }

    public long getTotalAllocatedBytes() {
        return allocations.values().stream()
                .mapToLong(NumaAllocation::getSize)
                .sum();
    }

    // Private methods

    private NumaTopology discoverNumaTopology() {
        int nodeCount = platformProvider.getNumaNodeCount();
        if (nodeCount <= 1) {
            // Single node system
            return new NumaTopology(1, Collections.singletonList(0));
        }

        List<Integer> nodeIds = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodeIds.add(i);
        }

        return new NumaTopology(nodeCount, Collections.unmodifiableList(nodeIds));
    }

    private OperationResult<BitSet> getNodeCpusDirect(int nodeId) {
        try {
            // For single-node systems, return all CPUs
            if (!isAvailable() && nodeId == 0) {
                BitSet allCpus = new BitSet();
                int cpuCount = platformProvider.getCpuCount();
                for (int i = 0; i < cpuCount; i++) {
                    allCpus.set(i);
                }
                return OperationResult.success(allCpus);
            }

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getNodeCpusDirect",
                    "NUMA operations not available"));
            }

            long[] maskArray = new long[16];
            int result = platformProvider.getNumaNodeCpus(nodeId, maskArray, platformProvider.getCpuCount());

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getNodeCpusDirect");
            }

            BitSet cpuMask = AffinityManager.longArrayToBitSet(maskArray, platformProvider.getCpuCount());
            return OperationResult.success(cpuMask);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getNodeCpusDirect", "numa_node_cpus", e));
        }
    }

    private OperationResult<NumaNodeMemoryInfo> getNodeMemoryInfoDirect(int nodeId) {
        try {
            // For single-node systems, return unknown memory info
            if (!isAvailable() && nodeId == 0) {
                return OperationResult.success(new NumaNodeMemoryInfo(-1, -1));
            }

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getNodeMemoryInfoDirect",
                    "NUMA operations not available"));
            }

            long[] memoryInfo = new long[2]; // [total, free]
            int result = platformProvider.getNumaNodeMemoryInfo(nodeId, memoryInfo);

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getNodeMemoryInfoDirect");
            }

            // Check if we got valid data
            if (memoryInfo[0] < 0 && memoryInfo[1] >= 0) {
                memoryInfo[0] = memoryInfo[1]; // Use free as total if total is invalid
            }

            NumaNodeMemoryInfo nodeMemInfo = new NumaNodeMemoryInfo(memoryInfo[0], memoryInfo[1]);
            return OperationResult.success(nodeMemInfo);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getNodeMemoryInfoDirect", "numa_node_memory", e));
        }
    }

    private NumaNodeInfo gatherNodeInfo(int nodeId) {
        BitSet cpuMask = new BitSet();
        NumaNodeMemoryInfo memoryInfo = new NumaNodeMemoryInfo(-1, -1);

        try {
            OperationResult<BitSet> cpusResult = getNodeCpusDirect(nodeId);
            if (cpusResult.isSuccess()) {
                cpuMask = cpusResult.getValue();
            }
        } catch (Exception e) {
            logger.debug("Failed to get CPUs for node {}: {}", nodeId, e.getMessage());
        }

        try {
            OperationResult<NumaNodeMemoryInfo> memoryResult = getNodeMemoryInfoDirect(nodeId);
            if (memoryResult.isSuccess()) {
                memoryInfo = memoryResult.getValue();
            }
        } catch (Exception e) {
            logger.debug("Failed to get memory info for node {}: {}", nodeId, e.getMessage());
        }

        return new NumaNodeInfo(nodeId, cpuMask, memoryInfo);
    }

    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("NUMAManager not initialized");
        }
    }

    private void validateNodeId(int nodeId) throws InvalidParameterException {
        if (nodeId < 0 || nodeId >= getNodeCount()) {
            throw new InvalidParameterException("validateNodeId", "nodeId", nodeId);
        }
    }

    private void validateThreadId(long threadId) throws InvalidParameterException {
        if (threadId <= 0) {
            throw new InvalidParameterException("validateThreadId", "threadId", threadId);
        }
    }

    private void validateSize(long size) throws InvalidParameterException {
        if (size <= 0) {
            throw new InvalidParameterException("validateSize", "size", size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("validateSize", "size too large", size);
        }
    }

    private void validateAddress(long address) throws InvalidParameterException {
        if (address == 0) {
            throw new InvalidParameterException("validateAddress", "address", "null pointer");
        }
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation) {
        switch (errorCode) {
            case ERROR_NOT_SUPPORTED:
                return new UnsupportedOperationException(operation, "NUMA operation not supported");
            case ERROR_PERMISSION_DENIED:
                return new PermissionDeniedException(operation, "NUMA operations require elevated privileges", null);
            case ERROR_INSUFFICIENT_MEMORY:
                return new SystemCallException(operation, "numa_alloc", null);
            default:
                return new NumaException(operation, getErrorDescription(errorCode));
        }
    }

    public void shutdown() {
        logger.info("Shutting down NUMAManager...");

        // Free any remaining allocations
        if (!allocations.isEmpty()) {
            logger.warn("Freeing {} remaining NUMA allocations", allocations.size());
            allocations.keySet().forEach(address -> {
                try {
                    freeMemory(address);
                } catch (Exception e) {
                    logger.error("Failed to free allocation at 0x{}: {}",
                            Long.toHexString(address), e.getMessage());
                }
            });
        }

        nodeInfoCache.clear();
        allocations.clear();
        initialized.set(false);

        logger.info("NUMAManager shutdown complete");
    }

    // Data classes (rest remains the same as before)

    public static class NumaTopology {
        private final int nodeCount;
        private final List<Integer> nodeIds;

        public NumaTopology(int nodeCount, List<Integer> nodeIds) {
            this.nodeCount = nodeCount;
            this.nodeIds = nodeIds;
        }

        public int getNodeCount() { return nodeCount; }
        public List<Integer> getNodeIds() { return nodeIds; }

        @Override
        public String toString() {
            return String.format("NumaTopology{nodeCount=%d, nodes=%s}", nodeCount, nodeIds);
        }
    }

    public static class NumaNodeInfo {
        private final int nodeId;
        private final BitSet cpuMask;
        private final NumaNodeMemoryInfo memoryInfo;

        public NumaNodeInfo(int nodeId, BitSet cpuMask, NumaNodeMemoryInfo memoryInfo) {
            this.nodeId = nodeId;
            this.cpuMask = (BitSet) cpuMask.clone();
            this.memoryInfo = memoryInfo;
        }

        public int getNodeId() { return nodeId; }
        public BitSet getCpuMask() { return (BitSet) cpuMask.clone(); }
        public NumaNodeMemoryInfo getMemoryInfo() { return memoryInfo; }
        public int getCpuCount() { return cpuMask.cardinality(); }

        @Override
        public String toString() {
            return String.format("NumaNodeInfo{node=%d, cpus=%d, memory=%s}",
                    nodeId, getCpuCount(), memoryInfo);
        }
    }

    public static class NumaNodeMemoryInfo {
        private final long totalBytes;
        private final long freeBytes;

        public NumaNodeMemoryInfo(long totalBytes, long freeBytes) {
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
        }

        public long getTotalBytes() { return totalBytes; }
        public long getFreeBytes() { return freeBytes; }
        public long getUsedBytes() {
            if (totalBytes < 0 || freeBytes < 0) {
                return -1; // Unknown
            }
            return totalBytes - freeBytes;
        }
        public double getUtilization() {
            if (totalBytes <= 0 || freeBytes < 0) {
                return -1; // Unknown
            }
            return (double) getUsedBytes() / totalBytes;
        }

        @Override
        public String toString() {
            if (totalBytes < 0 && freeBytes >= 0) {
                return String.format("Memory{total=unknown, free=%s}", formatBytes(freeBytes));
            } else if (totalBytes >= 0 && freeBytes < 0) {
                return String.format("Memory{total=%s, free=unknown}", formatBytes(totalBytes));
            } else if (totalBytes < 0 && freeBytes < 0) {
                return "Memory{unknown}";
            } else {
                return String.format("Memory{total=%s, free=%s, used=%.1f%%}",
                        formatBytes(totalBytes), formatBytes(freeBytes), getUtilization() * 100);
            }
        }

        private String formatBytes(long bytes) {
            if (bytes < 0) return "unknown";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static class NumaAllocation {
        private final long address;
        private final long size;
        private final int nodeId;
        private final long timestamp;

        public NumaAllocation(long address, long size, int nodeId, long timestamp) {
            this.address = address;
            this.size = size;
            this.nodeId = nodeId;
            this.timestamp = timestamp;
        }

        public long getAddress() { return address; }
        public long getSize() { return size; }
        public int getNodeId() { return nodeId; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("NumaAllocation{addr=0x%x, size=%d, node=%d, age=%dms}",
                    address, size, nodeId, System.currentTimeMillis() - timestamp);
        }
    }

    public static class NumaDistanceMatrix {
        private final long[][] distances;
        private final int nodeCount;

        public NumaDistanceMatrix(long[][] distances) {
            this.distances = distances.clone();
            this.nodeCount = distances.length;
        }

        public long getDistance(int fromNode, int toNode) {
            if (fromNode < 0 || fromNode >= nodeCount || toNode < 0 || toNode >= nodeCount) {
                return -1;
            }
            return distances[fromNode][toNode];
        }

        public int getNodeCount() { return nodeCount; }

        public boolean hasValidDistances() {
            for (int i = 0; i < nodeCount; i++) {
                for (int j = 0; j < nodeCount; j++) {
                    if (distances[i][j] >= 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        public List<Integer> getClosestNodes(int fromNode, int maxResults) {
            if (fromNode < 0 || fromNode >= nodeCount) {
                return Collections.emptyList();
            }

            List<NodeDistance> nodeDistances = new ArrayList<>();
            for (int toNode = 0; toNode < nodeCount; toNode++) {
                if (toNode != fromNode && distances[fromNode][toNode] >= 0) {
                    nodeDistances.add(new NodeDistance(toNode, distances[fromNode][toNode]));
                }
            }

            nodeDistances.sort(Comparator.comparing(NodeDistance::getDistance));

            return nodeDistances.stream()
                    .limit(maxResults)
                    .map(NodeDistance::getNodeId)
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("NumaDistanceMatrix{\n");
            for (int i = 0; i < nodeCount; i++) {
                sb.append("  Node ").append(i).append(": ");
                for (int j = 0; j < nodeCount; j++) {
                    if (distances[i][j] >= 0) {
                        sb.append(String.format("%3d ", distances[i][j]));
                    } else {
                        sb.append("  - ");
                    }
                }
                sb.append("\n");
            }
            sb.append("}");
            return sb.toString();
        }

        private static class NodeDistance {
            private final int nodeId;
            private final long distance;

            public NodeDistance(int nodeId, long distance) {
                this.nodeId = nodeId;
                this.distance = distance;
            }

            public int getNodeId() { return nodeId; }
            public long getDistance() { return distance; }
        }
    }
}