package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * PrefetchManager provides intelligent memory prefetching for HFT applications.
 * Optimizes cache behavior through strategic memory access pattern hints
 * and processor-specific prefetch instructions.
 */
public class PrefetchManager {
    private static final Logger logger = LoggerFactory.getLogger(PrefetchManager.class);

    // Prefetch types based on processor architecture
    public enum PrefetchType {
        NONFAULT(0, "Non-faulting prefetch for cache-only"),      // Don't cause page faults
        TEMPORAL_1(1, "Temporal prefetch for L1 cache"),          // Likely to be used soon
        TEMPORAL_2(2, "Temporal prefetch for L2 cache"),          // Less likely to be used soon
        NON_TEMPORAL(3, "Non-temporal prefetch, bypass cache");   // Streaming data, don't pollute cache

        private final int value;
        private final String description;

        PrefetchType(int value, String description) {
            this.value = value;
            this.description = description;
        }

        public int getValue() { return value; }
        public String getDescription() { return description; }
    }

    // Memory access patterns for adaptive prefetching
    public enum AccessPattern {
        SEQUENTIAL("Sequential access pattern"),
        RANDOM("Random access pattern"),
        STRIDED("Strided access pattern"),
        STREAMING("Streaming access pattern");

        private final String description;

        AccessPattern(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }

    // Prefetch distance strategies
    public enum PrefetchStrategy {
        CONSERVATIVE(1, "Conservative prefetch distance"),
        MODERATE(2, "Moderate prefetch distance"),
        AGGRESSIVE(4, "Aggressive prefetch distance"),
        ADAPTIVE(-1, "Adaptive prefetch distance based on access pattern");

        private final int multiplier;
        private final String description;

        PrefetchStrategy(int multiplier, String description) {
            this.multiplier = multiplier;
            this.description = description;
        }

        public int getMultiplier() { return multiplier; }
        public String getDescription() { return description; }
    }

    // Prefetch configuration for a memory region
    public static class PrefetchConfig {
        private final PrefetchType type;
        private final AccessPattern pattern;
        private final PrefetchStrategy strategy;
        private final int stride;
        private final int distance;

        public PrefetchConfig(PrefetchType type, AccessPattern pattern,
                             PrefetchStrategy strategy, int stride, int distance) {
            this.type = type;
            this.pattern = pattern;
            this.strategy = strategy;
            this.stride = stride;
            this.distance = distance;
        }

        public PrefetchType getType() { return type; }
        public AccessPattern getPattern() { return pattern; }
        public PrefetchStrategy getStrategy() { return strategy; }
        public int getStride() { return stride; }
        public int getDistance() { return distance; }

        @Override
        public String toString() {
            return String.format("PrefetchConfig{type=%s, pattern=%s, strategy=%s, stride=%d, distance=%d}",
                    type, pattern, strategy, stride, distance);
        }
    }

    // Performance statistics for prefetch operations
    public static class PrefetchStats {
        private final long totalPrefetches;
        private final long successfulPrefetches;
        private final long averageLatencyNs;
        private final double hitRate;

        public PrefetchStats(long totalPrefetches, long successfulPrefetches,
                           long averageLatencyNs, double hitRate) {
            this.totalPrefetches = totalPrefetches;
            this.successfulPrefetches = successfulPrefetches;
            this.averageLatencyNs = averageLatencyNs;
            this.hitRate = hitRate;
        }

        public long getTotalPrefetches() { return totalPrefetches; }
        public long getSuccessfulPrefetches() { return successfulPrefetches; }
        public long getAverageLatencyNs() { return averageLatencyNs; }
        public double getHitRate() { return hitRate; }

        @Override
        public String toString() {
            return String.format("PrefetchStats{total=%d, successful=%d, avgLatency=%dns, hitRate=%.2f%%}",
                    totalPrefetches, successfulPrefetches, averageLatencyNs, hitRate * 100);
        }
    }

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);

    // Performance tracking
    private final Map<String, PrefetchStats> regionStats = new ConcurrentHashMap<>();
    private final AtomicBoolean statsEnabled = new AtomicBoolean(true);

    // Cache line information
    private long cacheLineSize = 64; // Default cache line size
    private int l1CacheSize = 32 * 1024; // 32KB default
    private int l2CacheSize = 256 * 1024; // 256KB default

    public PrefetchManager(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        try {
            logger.info("Initializing PrefetchManager...");

            // Check if memory prefetching is enabled in configuration
            if (!config.isMemoryPrefetchingEnabled()) {
                logger.info("Memory prefetching disabled in configuration");
                initialized.set(true);
                return;
            }

            // Detect cache topology for optimal prefetch distances
            detectCacheTopology();

            // Check platform capabilities for prefetch support
            if (config.isAutoDetectPrefetchCapabilities()) {
                detectPrefetchCapabilities();
            }

            available.set(true);
            initialized.set(true);
            logger.info("PrefetchManager initialized successfully with cache line size: {} bytes", cacheLineSize);

        } catch (Exception e) {
            throw new ConfigurationException("initialize", "Failed to initialize PrefetchManager: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    /**
     * Prefetch a single memory address
     */
    public OperationResult<Void> prefetchAddress(long address, PrefetchType type) {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("prefetchAddress",
                        "Memory prefetching not available"));
            }

            validateParameter(address, "address");
            validateParameter(type, "type");

            if (address == 0) {
                return OperationResult.failure(new InvalidParameterException("prefetchAddress", "address", "null pointer"));
            }

            int result = platformProvider.prefetchMemory(address, type.getValue());
            if (result != 0) {
                throw createExceptionForErrorCode(result, "prefetchAddress");
            }

            logger.trace("Prefetched address 0x{} with type {}", Long.toHexString(address), type);
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    /**
     * Prefetch a memory range with specified configuration
     */
    public OperationResult<Void> prefetchRange(long startAddress, long endAddress, PrefetchConfig config) {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("prefetchRange",
                        "Memory prefetching not available"));
            }

            validateParameter(startAddress, "startAddress");
            validateParameter(endAddress, "endAddress");
            validateParameter(config, "config");

            if (startAddress >= endAddress) {
                return OperationResult.failure(new InvalidParameterException("prefetchRange",
                        "address range", "start >= end"));
            }

            // Calculate optimal stride based on access pattern and cache line size
            int stride = calculateOptimalStride(config);

            int result = platformProvider.prefetchMemoryRange(startAddress, endAddress,
                    config.getType().getValue(), stride);
            if (result != 0) {
                throw createExceptionForErrorCode(result, "prefetchRange");
            }

            logger.debug("Prefetched range 0x{} to 0x{} with config: {}",
                    Long.toHexString(startAddress), Long.toHexString(endAddress), config);
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    /**
     * Create an optimized prefetch configuration for HFT workloads
     */
    public PrefetchConfig createHFTConfig(AccessPattern pattern) {
        PrefetchType type;
        switch (pattern) {
            case SEQUENTIAL:
                type = PrefetchType.TEMPORAL_1;  // Cache friendly for sequential access
                break;
            case STREAMING:
                type = PrefetchType.NON_TEMPORAL; // Don't pollute cache for streaming
                break;
            case STRIDED:
                type = PrefetchType.TEMPORAL_2;     // Medium cache priority for strided access
                break;
            case RANDOM:
                type = PrefetchType.NONFAULT;        // Conservative for random access
                break;
            default:
                throw new IllegalArgumentException("Unknown access pattern: " + pattern);
        }

        PrefetchStrategy strategy;
        switch (pattern) {
            case SEQUENTIAL:
            case STREAMING:
                strategy = PrefetchStrategy.AGGRESSIVE;
                break;
            case STRIDED:
                strategy = PrefetchStrategy.MODERATE;
                break;
            case RANDOM:
                strategy = PrefetchStrategy.CONSERVATIVE;
                break;
            default:
                throw new IllegalArgumentException("Unknown access pattern: " + pattern);
        }

        int stride = (int) cacheLineSize; // Default to cache line aligned access
        int distance = calculatePrefetchDistance(strategy, pattern);

        return new PrefetchConfig(type, pattern, strategy, stride, distance);
    }

    /**
     * Prefetch data structures commonly used in HFT applications
     */
    public OperationResult<Void> prefetchDataStructure(long baseAddress, int elementSize,
                                                      int elementCount, AccessPattern pattern) {
        try {
            if (elementCount <= 0 || elementSize <= 0) {
                return OperationResult.failure(new InvalidParameterException("prefetchDataStructure",
                        "element parameters", "invalid size or count"));
            }

            PrefetchConfig config = createHFTConfig(pattern);
            long endAddress = baseAddress + ((long) elementSize * elementCount);

            return prefetchRange(baseAddress, endAddress, config);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("prefetchDataStructure", e.getMessage()));
        }
    }

    /**
     * Check if a memory address is properly aligned for optimal cache performance
     */
    public OperationResult<Boolean> isOptimallyAligned(long address, int accessSize) {
        try {
            checkInitialized();

            // Check platform-level alignment
            boolean platformAligned = platformProvider.isMemoryAligned(address, accessSize);

            // Check cache line alignment for optimal performance
            boolean cacheAligned = (address % cacheLineSize) == 0;

            // Consider optimal if both platform and cache aligned
            boolean optimal = platformAligned && (cacheAligned || accessSize < cacheLineSize);

            return OperationResult.success(optimal);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("isOptimallyAligned", e.getMessage()));
        }
    }

    /**
     * Align a memory address for optimal cache performance
     */
    public OperationResult<Long> alignAddressForPerformance(long address, int accessSize) {
        try {
            checkInitialized();

            // Use platform provider for basic alignment
            long platformAligned = platformProvider.alignMemoryAddress(address, accessSize);

            // Further align to cache line boundary if beneficial
            if (accessSize >= cacheLineSize) {
                long cacheAligned = (platformAligned + cacheLineSize - 1) & ~(cacheLineSize - 1);
                return OperationResult.success(cacheAligned);
            }

            return OperationResult.success(platformAligned);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("alignAddressForPerformance", e.getMessage()));
        }
    }

    /**
     * Get performance statistics for prefetch operations
     */
    public OperationResult<PrefetchStats> getStatistics(String regionName) {
        try {
            checkInitialized();

            if (!statsEnabled.get()) {
                return OperationResult.failure(new OperationFailedException("getStatistics",
                        "Statistics collection is disabled"));
            }

            PrefetchStats stats = regionStats.get(regionName);
            if (stats == null) {
                // Return empty stats for unknown regions
                stats = new PrefetchStats(0, 0, 0, 0.0);
            }

            return OperationResult.success(stats);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("getStatistics", e.getMessage()));
        }
    }

    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            logger.info("Shutting down PrefetchManager...");

            // Clear statistics
            regionStats.clear();

            logger.info("PrefetchManager shutdown complete");

        } catch (Exception e) {
            logger.error("Error during PrefetchManager shutdown", e);
        } finally {
            initialized.set(false);
            available.set(false);
        }
    }

    // Private helper methods

    private void checkInitialized() throws IllegalStateException {
        if (!initialized.get()) {
            throw new IllegalStateException("PrefetchManager not initialized");
        }
    }

    private void validateParameter(Object param, String paramName) throws InvalidParameterException {
        if (!config.isParameterValidationEnabled()) {
            return;
        }

        if (param == null) {
            throw new InvalidParameterException("validate", paramName, "null");
        }
    }

    private void detectCacheTopology() {
        try {
            // Get cache information from platform
            long detectedCacheLineSize = platformProvider.getCacheLineSize();
            if (detectedCacheLineSize > 0) {
                cacheLineSize = detectedCacheLineSize;
            }

            // Estimate cache sizes (this could be enhanced with more detailed topology detection)
            l1CacheSize = 32 * 1024; // 32KB typical L1
            l2CacheSize = 256 * 1024; // 256KB typical L2

            logger.debug("Detected cache topology: L1={}KB, L2={}KB, line={}B",
                    l1CacheSize / 1024, l2CacheSize / 1024, cacheLineSize);

        } catch (Exception e) {
            logger.debug("Failed to detect cache topology, using defaults: {}", e.getMessage());
        }
    }

    private void detectPrefetchCapabilities() {
        try {
            // Test prefetch capability with a safe operation
            String[] features = platformProvider.getSupportedFeatures();
            boolean hasHardwarePrefetch = false;

            for (String feature : features) {
                if (feature.toLowerCase().contains("prefetch")) {
                    hasHardwarePrefetch = true;
                    break;
                }
            }

            if (!hasHardwarePrefetch) {
                logger.info("Hardware prefetch instructions not detected, using software hints");
            }

        } catch (Exception e) {
            logger.debug("Failed to detect prefetch capabilities: {}", e.getMessage());
        }
    }

    private int calculateOptimalStride(PrefetchConfig config) {
        int baseStride = config.getStride();

        // Align stride to cache line boundaries for optimal performance
        if (baseStride < cacheLineSize) {
            return (int) cacheLineSize;
        }

        // Round up to next cache line boundary
        return (int) ((baseStride + cacheLineSize - 1) & ~(cacheLineSize - 1));
    }

    private int calculatePrefetchDistance(PrefetchStrategy strategy, AccessPattern pattern) {
        int baseDistance = strategy.getMultiplier();

        if (strategy == PrefetchStrategy.ADAPTIVE) {
            // Adaptive distance based on access pattern
            switch (pattern) {
                case SEQUENTIAL:
                    baseDistance = 4;   // Aggressive for sequential
                    break;
                case STREAMING:
                    baseDistance = 8;    // Very aggressive for streaming
                    break;
                case STRIDED:
                    baseDistance = 2;      // Moderate for strided
                    break;
                case RANDOM:
                    baseDistance = 1;       // Conservative for random
                    break;
                default:
                    baseDistance = 1;
                    break;
            }
        }

        return Math.max(1, baseDistance * (int) (cacheLineSize / 8)); // Scale by cache line size
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation) {
        switch (errorCode) {
            case -1:
                return new InvalidParameterException(operation, "parameter", "Invalid parameter");
            case -2:
                return new SystemCallException(operation, "system_call", -1);
            case -3:
                return new com.faster.affinity.exceptions.UnsupportedOperationException(operation, "Operation not supported");
            default:
                return new OperationFailedException(operation, "Unknown error code: " + errorCode);
        }
    }
}