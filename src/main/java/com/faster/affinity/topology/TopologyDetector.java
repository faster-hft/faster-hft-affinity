package com.faster.affinity.topology;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.exceptions.UnsupportedOperationException;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.faster.affinity.exceptions.ErrorCodes.*;

/**
 * Hardware topology detection and analysis.
 * Discovers CPU hierarchy, cache structure, and NUMA topology.
 */
public final class TopologyDetector {
    private static final Logger logger = LoggerFactory.getLogger(TopologyDetector.class);

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicReference<SystemTopology> topology = new AtomicReference<>();
    private final ConcurrentHashMap<String, Object> topologyCache = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    public TopologyDetector(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        if (initialized) {
            return;
        }

        try {
            logger.info("Detecting system topology...");
            SystemTopology detectedTopology = detectTopology();
            topology.set(detectedTopology);
            initialized = true;

            logger.info("System topology detected: {}", detectedTopology);
            if (config.isDeveloperMode()) {
                logDetailedTopology(detectedTopology);
            }

        } catch (Exception e) {
            throw new ConfigurationException("initialize", "Failed to detect system topology: " + e.getMessage());
        }
    }

    public SystemTopology getTopology() {
        if (!initialized) {
            throw new IllegalStateException("TopologyDetector not initialized");
        }
        return topology.get();
    }

    public int getCpuCount() {
        return getFromCacheOrDetect("cpu_count", () -> platformProvider.getCpuCount());
    }

    public int getSocketCount() {
        return getFromCacheOrDetect("socket_count", () -> platformProvider.getSocketCount());
    }

    public int getCoresPerSocket() {
        return getFromCacheOrDetect("cores_per_socket", () -> platformProvider.getCoresPerSocket());
    }

    public int getNumaNodeCount() {
        return getFromCacheOrDetect("numa_node_count", () -> platformProvider.getNumaNodeCount());
    }

    public int getMaxCacheLevel() {
        return getFromCacheOrDetect("max_cache_level", () -> platformProvider.getMaxCacheLevel());
    }

    public long getCacheLineSize() {
        return getFromCacheOrDetect("cache_line_size", () -> platformProvider.getCacheLineSize());
    }

    public OperationResult<BitSet> getCacheLevelCores(int coreId, int cacheLevel) {
        try {
            validateCoreId(coreId);
            validateCacheLevel(cacheLevel);

            String cacheKey = String.format("cache_l%d_core%d", cacheLevel, coreId);
            return OperationResult.success(getFromCacheOrDetect(cacheKey, () -> {
                long[] maskArray = new long[16];
                int result = platformProvider.getCacheLevelCores(coreId, cacheLevel, maskArray, getCpuCount());

                if (result != ErrorCodes.SUCCESS) {
                    throw createExceptionForErrorCode(result, "getCacheLevelCores");
                }

                return AffinityManager.longArrayToBitSet(maskArray, getCpuCount());
            }));

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        } catch (RuntimeException e) {
            return OperationResult.failure(new OperationFailedException("getCacheLevelCores", e.getMessage()));
        }
    }

    public OperationResult<Long> getCacheSize(int cacheLevel) {
        try {
            validateCacheLevel(cacheLevel);

            String cacheKey = "cache_size_l" + cacheLevel;
            long size = getFromCacheOrDetect(cacheKey, () -> platformProvider.getCacheSize(cacheLevel));

            return OperationResult.success(size);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<BitSet> getNumaNodeCpus(int nodeId) {
        try {
            validateNumaNode(nodeId);

            String cacheKey = "numa_cpus_" + nodeId;
            return OperationResult.success(getFromCacheOrDetect(cacheKey, () -> {
                long[] maskArray = new long[16];
                int result = platformProvider.getNumaNodeCpus(nodeId, maskArray, getCpuCount());

                if (result != ErrorCodes.SUCCESS) {
                    throw createExceptionForErrorCode(result, "getNumaNodeCpus");
                }

                return AffinityManager.longArrayToBitSet(maskArray, getCpuCount());
            }));

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public boolean isHyperThreadedCore(int coreId) {
        try {
            validateCoreId(coreId);
            String cacheKey = "hyperthread_" + coreId;
            Integer result = getFromCacheOrDetect(cacheKey, () -> platformProvider.isHyperThreadedCore(coreId));
            return result == 1;
        } catch (Exception e) {
            logger.debug("Failed to detect hyperthreading for core {}: {}", coreId, e.getMessage());
            return false;
        }
    }

    public OperationResult<CoreInfo> getCoreInfo(int coreId) {
        try {
            validateCoreId(coreId);

            CoreInfo.Builder builder = new CoreInfo.Builder(coreId);

            // Determine socket
            int socketId = coreId / getCoresPerSocket();
            builder.socketId(socketId);

            // Check hyperthreading
            builder.hyperThreaded(isHyperThreadedCore(coreId));

            // Get cache information
            for (int level = 1; level <= getMaxCacheLevel(); level++) {
                OperationResult<BitSet> cacheResult = getCacheLevelCores(coreId, level);
                if (cacheResult.isSuccess()) {
                    builder.addCacheLevel(level, cacheResult.getValue());
                }

                OperationResult<Long> sizeResult = getCacheSize(level);
                if (sizeResult.isSuccess()) {
                    builder.addCacheSize(level, sizeResult.getValue());
                }
            }

            // Determine NUMA node
            int numaNode = findNumaNodeForCore(coreId);
            builder.numaNodeId(numaNode);

            return OperationResult.success(builder.build());

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<List<CoreInfo>> getAllCoreInfo() {
        try {
            List<CoreInfo> coreInfos = new ArrayList<>();
            int cpuCount = getCpuCount();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                OperationResult<CoreInfo> result = getCoreInfo(coreId);
                if (result.isSuccess()) {
                    coreInfos.add(result.getValue());
                } else {
                    logger.warn("Failed to get info for core {}: {}", coreId, result.getError().getMessage());
                }
            }

            return OperationResult.success(Collections.unmodifiableList(coreInfos));

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getAllCoreInfo", "topology_detection", e));
        }
    }

    private SystemTopology detectTopology() throws AffinityException {
        SystemTopology.Builder builder = new SystemTopology.Builder();

        // Basic CPU information
        int cpuCount = platformProvider.getCpuCount();
        int socketCount = platformProvider.getSocketCount();
        int coresPerSocket = platformProvider.getCoresPerSocket();

        builder.cpuCount(cpuCount)
                .socketCount(socketCount)
                .coresPerSocket(coresPerSocket);

        // Cache information
        int maxCacheLevel = platformProvider.getMaxCacheLevel();
        long cacheLineSize = platformProvider.getCacheLineSize();

        builder.maxCacheLevel(maxCacheLevel)
                .cacheLineSize(cacheLineSize);

        Map<Integer, Long> cacheSizes = new HashMap<>();
        for (int level = 1; level <= maxCacheLevel; level++) {
            long size = platformProvider.getCacheSize(level);
            if (size > 0) {
                cacheSizes.put(level, size);
            }
        }
        builder.cacheSizes(cacheSizes);

        // NUMA information
        int numaNodeCount = platformProvider.getNumaNodeCount();
        builder.numaNodeCount(numaNodeCount);

        // Hyperthreading detection
        boolean hasHyperthreading = false;
        for (int core = 0; core < Math.min(cpuCount, 8); core++) {
            if (platformProvider.isHyperThreadedCore(core) == 1) {
                hasHyperthreading = true;
                break;
            }
        }
        builder.hyperthreadingEnabled(hasHyperthreading);

        return builder.build();
    }

    private int findNumaNodeForCore(int coreId) {
        int numaNodeCount = getNumaNodeCount();

        for (int nodeId = 0; nodeId < numaNodeCount; nodeId++) {
            OperationResult<BitSet> result = getNumaNodeCpus(nodeId);
            if (result.isSuccess() && result.getValue().get(coreId)) {
                return nodeId;
            }
        }

        // Default to node 0 if not found
        return 0;
    }

    @SuppressWarnings("unchecked")
    private <T> T getFromCacheOrDetect(String key, TopologyOperation<T> operation) {
        if (!config.isCachingEnabled()) {
            try {
                return operation.execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CacheEntry entry = (CacheEntry) topologyCache.get(key);
        if (entry != null && !entry.isExpired(config.getCacheExpiryMs())) {
            return (T) entry.value;
        }

        try {
            T value = operation.execute();
            topologyCache.put(key, new CacheEntry(value, System.currentTimeMillis()));
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void validateCoreId(int coreId) throws InvalidParameterException {
        if (coreId < 0 || coreId >= getCpuCount()) {
            throw new InvalidParameterException("validateCoreId", "coreId", coreId);
        }
    }

    private void validateCacheLevel(int cacheLevel) throws InvalidParameterException {
        if (cacheLevel < 1 || cacheLevel > getMaxCacheLevel()) {
            throw new InvalidParameterException("validateCacheLevel", "cacheLevel", cacheLevel);
        }
    }

    private void validateNumaNode(int nodeId) throws NumaException {
        if (nodeId < 0 || nodeId >= getNumaNodeCount()) {
            throw new NumaException("validateNumaNode", nodeId);
        }
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation) {
        switch (errorCode) {
            case ERROR_NOT_SUPPORTED:
                return new UnsupportedOperationException(operation);
            case ERROR_INVALID_PARAMETER:
                return new InvalidParameterException(operation, "parameter", "Invalid parameters");
            case ERROR_HARDWARE_NOT_AVAILABLE:
                return new HardwareException(operation, "Hardware not available");
            default:
                return new SystemCallException(operation, "platform_call", errorCode);
        }
    }

    private void logDetailedTopology(SystemTopology topology) {
        logger.debug("=== Detailed System Topology ===");
        logger.debug("CPUs: {}, Sockets: {}, Cores per socket: {}",
                topology.getCpuCount(), topology.getSocketCount(), topology.getCoresPerSocket());
        logger.debug("Hyperthreading: {}", topology.isHyperthreadingEnabled());
        logger.debug("Max cache level: {}, Cache line size: {} bytes",
                topology.getMaxCacheLevel(), topology.getCacheLineSize());

        topology.getCacheSizes().forEach((level, size) ->
                logger.debug("L{} cache size: {} bytes", level, size));

        logger.debug("NUMA nodes: {}", topology.getNumaNodeCount());
    }

    @FunctionalInterface
    private interface TopologyOperation<T> {
        T execute() throws Exception;
    }

    private static class CacheEntry {
        final Object value;
        final long timestamp;

        CacheEntry(Object value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        boolean isExpired(long maxAge) {
            return System.currentTimeMillis() - timestamp > maxAge;
        }
    }

    // Data classes

    public static class SystemTopology {
        private final int cpuCount;
        private final int socketCount;
        private final int coresPerSocket;
        private final int maxCacheLevel;
        private final long cacheLineSize;
        private final Map<Integer, Long> cacheSizes;
        private final int numaNodeCount;
        private final boolean hyperthreadingEnabled;

        private SystemTopology(Builder builder) {
            this.cpuCount = builder.cpuCount;
            this.socketCount = builder.socketCount;
            this.coresPerSocket = builder.coresPerSocket;
            this.maxCacheLevel = builder.maxCacheLevel;
            this.cacheLineSize = builder.cacheLineSize;
            this.cacheSizes = Collections.unmodifiableMap(new HashMap<>(builder.cacheSizes));
            this.numaNodeCount = builder.numaNodeCount;
            this.hyperthreadingEnabled = builder.hyperthreadingEnabled;
        }

        // Getters
        public int getCpuCount() { return cpuCount; }
        public int getSocketCount() { return socketCount; }
        public int getCoresPerSocket() { return coresPerSocket; }
        public int getMaxCacheLevel() { return maxCacheLevel; }
        public long getCacheLineSize() { return cacheLineSize; }
        public Map<Integer, Long> getCacheSizes() { return cacheSizes; }
        public int getNumaNodeCount() { return numaNodeCount; }
        public boolean isHyperthreadingEnabled() { return hyperthreadingEnabled; }

        @Override
        public String toString() {
            return String.format("SystemTopology{cpus=%d, sockets=%d, cores/socket=%d, L%d cache, %s HT, %d NUMA nodes}",
                    cpuCount, socketCount, coresPerSocket, maxCacheLevel,
                    hyperthreadingEnabled ? "with" : "without", numaNodeCount);
        }

        public static class Builder {
            private int cpuCount;
            private int socketCount;
            private int coresPerSocket;
            private int maxCacheLevel;
            private long cacheLineSize;
            private Map<Integer, Long> cacheSizes = new HashMap<>();
            private int numaNodeCount;
            private boolean hyperthreadingEnabled;

            public Builder cpuCount(int cpuCount) { this.cpuCount = cpuCount; return this; }
            public Builder socketCount(int socketCount) { this.socketCount = socketCount; return this; }
            public Builder coresPerSocket(int coresPerSocket) { this.coresPerSocket = coresPerSocket; return this; }
            public Builder maxCacheLevel(int maxCacheLevel) { this.maxCacheLevel = maxCacheLevel; return this; }
            public Builder cacheLineSize(long cacheLineSize) { this.cacheLineSize = cacheLineSize; return this; }
            public Builder cacheSizes(Map<Integer, Long> cacheSizes) { this.cacheSizes = cacheSizes; return this; }
            public Builder numaNodeCount(int numaNodeCount) { this.numaNodeCount = numaNodeCount; return this; }
            public Builder hyperthreadingEnabled(boolean enabled) { this.hyperthreadingEnabled = enabled; return this; }

            public SystemTopology build() {
                return new SystemTopology(this);
            }
        }
    }

    public static class CoreInfo {
        private final int coreId;
        private final int socketId;
        private final int numaNodeId;
        private final boolean hyperThreaded;
        private final Map<Integer, BitSet> cacheLevels;
        private final Map<Integer, Long> cacheSizes;

        private CoreInfo(Builder builder) {
            this.coreId = builder.coreId;
            this.socketId = builder.socketId;
            this.numaNodeId = builder.numaNodeId;
            this.hyperThreaded = builder.hyperThreaded;
            this.cacheLevels = Collections.unmodifiableMap(new HashMap<>(builder.cacheLevels));
            this.cacheSizes = Collections.unmodifiableMap(new HashMap<>(builder.cacheSizes));
        }

        // Getters
        public int getCoreId() { return coreId; }
        public int getSocketId() { return socketId; }
        public int getNumaNodeId() { return numaNodeId; }
        public boolean isHyperThreaded() { return hyperThreaded; }
        public Map<Integer, BitSet> getCacheLevels() { return cacheLevels; }
        public Map<Integer, Long> getCacheSizes() { return cacheSizes; }

        public BitSet getCacheLevelCores(int level) {
            return cacheLevels.get(level);
        }

        public Long getCacheSize(int level) {
            return cacheSizes.get(level);
        }

        @Override
        public String toString() {
            return String.format("CoreInfo{id=%d, socket=%d, numa=%d, HT=%s, caches=%s}",
                    coreId, socketId, numaNodeId, hyperThreaded, cacheLevels.keySet());
        }

        public static class Builder {
            private final int coreId;
            private int socketId;
            private int numaNodeId;
            private boolean hyperThreaded;
            private Map<Integer, BitSet> cacheLevels = new HashMap<>();
            private Map<Integer, Long> cacheSizes = new HashMap<>();

            public Builder(int coreId) { this.coreId = coreId; }

            public Builder socketId(int socketId) { this.socketId = socketId; return this; }
            public Builder numaNodeId(int numaNodeId) { this.numaNodeId = numaNodeId; return this; }
            public Builder hyperThreaded(boolean hyperThreaded) { this.hyperThreaded = hyperThreaded; return this; }
            public Builder addCacheLevel(int level, BitSet cores) { this.cacheLevels.put(level, cores); return this; }
            public Builder addCacheSize(int level, long size) { this.cacheSizes.put(level, size); return this; }

            public CoreInfo build() {
                return new CoreInfo(this);
            }
        }
    }
}