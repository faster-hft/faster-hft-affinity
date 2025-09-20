package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.platform.PlatformProvider;
import com.faster.affinity.platform.PlatformProviderFactory;
import com.faster.affinity.topology.TopologyDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core affinity manager that orchestrates CPU affinity, NUMA, topology, and performance operations.
 * Thread-safe, production-ready implementation with proper error handling and caching.
 */
public final class AffinityManager {
    private static final Logger logger = LoggerFactory.getLogger(AffinityManager.class);

    // Maximum supported CPUs to prevent integer overflow
    private static final int MAX_SUPPORTED_CPUS = 4096;
    private static final int MAX_LONG_ARRAY_SIZE = (MAX_SUPPORTED_CPUS + 63) / 64;

    private static volatile AffinityManager instance;
    private static final ReentrantLock instanceLock = new ReentrantLock();

    private final AffinityConfig config;
    private final TopologyDetector topologyDetector;
    private final PerformanceMonitor performanceMonitor;
    private final NUMAManager numaManager;
    private final IRQManager irqManager;
    private final CPUGovernorManager cpuGovernorManager;
    private final PlatformProvider platformProvider;

    // Caching and state management
    private final ConcurrentHashMap<String, CacheEntry> operationCache;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private final AtomicReference<SystemCapabilities> systemCapabilities = new AtomicReference<>();

    // Thread-local arrays for performance
    private static final ThreadLocal<long[]> TL_MASK_ARRAY = ThreadLocal.withInitial(() -> new long[MAX_LONG_ARRAY_SIZE]);
    private static final ThreadLocal<int[]> TL_INT_ARRAY = ThreadLocal.withInitial(() -> new int[8]);

    private AffinityManager(AffinityConfig config) {
        this.config = config;
        this.operationCache = new ConcurrentHashMap<>();

        // Initialize platform-specific provider
        this.platformProvider = PlatformProviderFactory.createProvider(config);

        // Initialize sub-managers
        this.topologyDetector = new TopologyDetector(platformProvider, config);
        this.performanceMonitor = config.isPerformanceCountersEnabled() ?
                new PerformanceMonitor(platformProvider, config) : null;
        this.numaManager = config.isNumaOperationsEnabled() ?
                new NUMAManager(platformProvider, config) : null;
        this.irqManager = config.isIRQManagementEnabled() ?
                new IRQManager(platformProvider, config) : null;
        this.cpuGovernorManager = config.isGovernorControlEnabled() ?
                new CPUGovernorManager(platformProvider, config) : null;

        logger.info("AffinityManager created with config: {}", config);
    }

    public static AffinityManager getInstance() throws ConfigurationException {
        AffinityManager result = instance;
        if (result == null) {
            instanceLock.lock();
            try {
                result = instance;
                if (result == null) {
                    result = new AffinityManager(AffinityConfig.getInstance());
                    // Initialize before setting instance to prevent race conditions
                    result.initialize();
                    instance = result;
                }
            } finally {
                instanceLock.unlock();
            }
        } else if (!result.initialized.get()) {
            // Handle case where instance exists but initialization failed
            throw new ConfigurationException("getInstance", "AffinityManager instance exists but is not properly initialized");
        }
        return result;
    }

    public static AffinityManager getInstance(AffinityConfig config) throws ConfigurationException {
        instanceLock.lock();
        try {
            if (instance != null) {
                logger.warn("Replacing existing AffinityManager instance");
                try {
                    instance.shutdown();
                } catch (Exception e) {
                    logger.warn("Error shutting down existing instance: {}", e.getMessage());
                }
            }
            AffinityManager newInstance = new AffinityManager(config);
            newInstance.initialize();
            instance = newInstance;
            return newInstance;
        } finally {
            instanceLock.unlock();
        }
    }

    private void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        if (!initializing.compareAndSet(false, true)) {
            // Another thread is initializing, wait for it
            while (initializing.get() && !initialized.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConfigurationException("initialize", "Initialization interrupted");
                }
            }
            if (!initialized.get()) {
                throw new ConfigurationException("initialize", "Initialization failed in another thread");
            }
            return;
        }

        try {
            logger.info("Initializing AffinityManager...");

            // Validate system capabilities
            SystemCapabilities caps = detectSystemCapabilities();
            systemCapabilities.set(caps);

            // Initialize sub-components
            topologyDetector.initialize();
            if (performanceMonitor != null) {
                performanceMonitor.initialize();
            }
            if (numaManager != null) {
                numaManager.initialize();
            }
            if (irqManager != null) {
                irqManager.initialize();
            }
            if (cpuGovernorManager != null) {
                cpuGovernorManager.initialize();
            }

            initialized.set(true);
            logger.info("AffinityManager initialized successfully: {}", caps);

        } catch (Exception e) {
            logger.error("Failed to initialize AffinityManager", e);
            throw new ConfigurationException("initialize", "Failed to initialize AffinityManager: " + e.getMessage());
        } finally {
            initializing.set(false);
        }
    }

    // Core CPU Affinity Operations

    public OperationResult<Void> setThreadAffinity(long threadId, BitSet cpuMask) {
        return executeWithRetry("setThreadAffinity", () -> {
            validateParameters("setThreadAffinity", threadId, cpuMask);

            if (cpuMask.length() > MAX_SUPPORTED_CPUS) {
                throw new InvalidParameterException("setThreadAffinity", "cpuMask",
                        "CPU mask size exceeds maximum supported CPUs: " + cpuMask.length() + " > " + MAX_SUPPORTED_CPUS);
            }

            long[] maskArray = bitSetToLongArray(cpuMask);
            if (maskArray.length > MAX_LONG_ARRAY_SIZE) {
                throw new InvalidParameterException("setThreadAffinity", "cpuMask",
                        "Converted mask array too large: " + maskArray.length);
            }

            int result = platformProvider.setThreadAffinity(threadId, maskArray, maskArray.length);

            if (result != ErrorCodes.SUCCESS) {
                throw createExceptionForErrorCode(result, "setThreadAffinity",
                        new ErrorContext().add("thread_id", threadId).add("cpu_mask", cpuMask).build());
            }

            // Cache the successful operation
            if (config.isCachingEnabled()) {
                String key = "thread_affinity_" + threadId;
                operationCache.put(key, new CacheEntry(cpuMask.clone(), System.currentTimeMillis()));
            }

            logger.info("Successfully set thread {} affinity to {}", threadId, cpuMask);
            return null;
        });
    }

    public OperationResult<BitSet> getThreadAffinity(long threadId) {
        return executeWithRetry("getThreadAffinity", () -> {
            validateParameters("getThreadAffinity", threadId);

            // Check cache first
            if (config.isCachingEnabled()) {
                String key = "thread_affinity_" + threadId;
                CacheEntry cached = operationCache.get(key);
                if (cached != null && !cached.isExpired(config.getCacheExpiryMs())) {
                    logger.debug("Returning cached thread affinity for {}", threadId);
                    return (BitSet) ((BitSet) cached.getValue()).clone();
                }
            }

            long[] maskArray = TL_MASK_ARRAY.get();
            // Clear array before use
            java.util.Arrays.fill(maskArray, 0);

            int result = platformProvider.getThreadAffinity(threadId, maskArray, maskArray.length);

            if (result != ErrorCodes.SUCCESS) {
                throw createExceptionForErrorCode(result, "getThreadAffinity",
                        new ErrorContext().add("thread_id", threadId).build());
            }

            BitSet cpuMask = longArrayToBitSet(maskArray, topologyDetector.getCpuCount());

            // Cache the result
            if (config.isCachingEnabled()) {
                String key = "thread_affinity_" + threadId;
                operationCache.put(key, new CacheEntry(cpuMask.clone(), System.currentTimeMillis()));
            }

            logger.debug("Retrieved thread {} affinity: {}", threadId, cpuMask);
            return cpuMask;
        });
    }

    public OperationResult<Void> setProcessAffinity(int processId, BitSet cpuMask) {
        return executeWithRetry("setProcessAffinity", () -> {
            validateParameters("setProcessAffinity", processId, cpuMask);

            if (cpuMask.length() > MAX_SUPPORTED_CPUS) {
                throw new InvalidParameterException("setProcessAffinity", "cpuMask",
                        "CPU mask size exceeds maximum supported CPUs: " + cpuMask.length());
            }

            long[] maskArray = bitSetToLongArray(cpuMask);
            int result = platformProvider.setProcessAffinity(processId, maskArray, maskArray.length);

            if (result != ErrorCodes.SUCCESS) {
                throw createExceptionForErrorCode(result, "setProcessAffinity",
                        new ErrorContext().add("process_id", processId).add("cpu_mask", cpuMask).build());
            }

            logger.debug("Successfully set process {} affinity to {}", processId, cpuMask);
            return null;
        });
    }

    public OperationResult<BitSet> getProcessAffinity(int processId) {
        return executeWithRetry("getProcessAffinity", () -> {
            validateParameters("getProcessAffinity", processId);

            long[] maskArray = TL_MASK_ARRAY.get();
            java.util.Arrays.fill(maskArray, 0);

            int result = platformProvider.getProcessAffinity(processId, maskArray, maskArray.length);

            if (result != ErrorCodes.SUCCESS) {
                throw createExceptionForErrorCode(result, "getProcessAffinity",
                        new ErrorContext().add("process_id", processId).build());
            }

            BitSet cpuMask = longArrayToBitSet(maskArray, topologyDetector.getCpuCount());
            logger.debug("Retrieved process {} affinity: {}", processId, cpuMask);
            return cpuMask;
        });
    }

    // System Information

    public long getCurrentThreadId() {
        return platformProvider.getCurrentThreadId();
    }

    public int getCurrentProcessId() {
        return platformProvider.getCurrentProcessId();
    }

    public SystemCapabilities getSystemCapabilities() {
        SystemCapabilities caps = systemCapabilities.get();
        if (caps == null) {
            throw new IllegalStateException("System capabilities not initialized");
        }
        return caps;
    }

    public TopologyDetector getTopologyDetector() {
        return topologyDetector;
    }

    public PerformanceMonitor getPerformanceMonitor() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (performanceMonitor == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getPerformanceMonitor",
                    "Performance monitoring is disabled in configuration");
        }
        return performanceMonitor;
    }

    public NUMAManager getNUMAManager() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (numaManager == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getNUMAManager",
                    "NUMA operations are disabled in configuration");
        }
        return numaManager;
    }

    public IRQManager getIRQManager() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (irqManager == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getIRQManager",
                    "IRQ management is disabled in configuration");
        }
        return irqManager;
    }

    public CPUGovernorManager getCPUGovernorManager() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (cpuGovernorManager == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getCPUGovernorManager",
                    "CPU governor control is disabled in configuration");
        }
        return cpuGovernorManager;
    }

    // CPU Governor Control operations (HFT performance optimization)

    public OperationResult<CPUGovernorManager.GovernorMode> getCurrentGovernor(int coreId) {
        if (cpuGovernorManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getCurrentGovernor",
                    "CPU governor control is disabled in configuration"));
        }
        return cpuGovernorManager.getCurrentGovernor(coreId);
    }

    public OperationResult<Void> setGovernor(int coreId, CPUGovernorManager.GovernorMode governor) {
        if (cpuGovernorManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setGovernor",
                    "CPU governor control is disabled in configuration"));
        }
        return cpuGovernorManager.setGovernor(coreId, governor);
    }

    public OperationResult<Void> setAllCoresGovernor(CPUGovernorManager.GovernorMode governor) {
        if (cpuGovernorManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setAllCoresGovernor",
                    "CPU governor control is disabled in configuration"));
        }
        return cpuGovernorManager.setAllCoresGovernor(governor);
    }

    public OperationResult<CPUGovernorManager.GovernorStatus> getGovernorStatus() {
        if (cpuGovernorManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getGovernorStatus",
                    "CPU governor control is disabled in configuration"));
        }
        return cpuGovernorManager.getGovernorStatus();
    }

    public OperationResult<Void> restoreOriginalGovernors() {
        if (cpuGovernorManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("restoreOriginalGovernors",
                    "CPU governor control is disabled in configuration"));
        }
        return cpuGovernorManager.restoreOriginalGovernors();
    }

    // Validation and error handling

    private void validateParameters(String operation, Object... params) throws InvalidParameterException {
        if (!config.isParameterValidationEnabled()) {
            return;
        }

        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param == null) {
                throw new InvalidParameterException(operation, "param_" + i, null);
            }

            // Type-specific validation
            if (param instanceof Long) {
                long value = (Long) param;
                if (value < 0) {
                    throw new InvalidParameterException(operation, "thread_id", value);
                }
            } else if (param instanceof Integer) {
                int value = (Integer) param;
                if (value < 0) {
                    throw new InvalidParameterException(operation, "process_id", value);
                }
            } else if (param instanceof BitSet) {
                BitSet bitSet = (BitSet) param;
                if (bitSet.isEmpty()) {
                    throw new InvalidParameterException(operation, "cpu_mask", "empty");
                }
                if (bitSet.length() > MAX_SUPPORTED_CPUS) {
                    throw new InvalidParameterException(operation, "cpu_mask",
                            "mask size exceeds supported maximum: " + bitSet.length() + " > " + MAX_SUPPORTED_CPUS);
                }
                if (bitSet.length() > topologyDetector.getCpuCount()) {
                    throw new InvalidParameterException(operation, "cpu_mask",
                            "mask size exceeds available CPUs: " + bitSet.length() + " > " + topologyDetector.getCpuCount());
                }
            }
        }
    }

    private <T> OperationResult<T> executeWithRetry(String operation, Operation<T> op) {
        int attempts = 0;
        int maxRetries = config.getMaxRetryAttempts();
        long timeout = config.getOperationTimeoutMs();

        while (attempts <= maxRetries) {
            try {
                long startTime = System.currentTimeMillis();
                T result = op.execute();

                long duration = System.currentTimeMillis() - startTime;
                if (duration > timeout) {
                    logger.warn("Operation {} took {}ms (timeout: {}ms)", operation, duration, timeout);
                }

                return OperationResult.success(result);

            } catch (AffinityException e) {
                attempts++;

                if (attempts > maxRetries || ErrorUtils.isFatalError(e.getErrorCode())) {
                    ErrorUtils.logError(e);
                    return OperationResult.failure(e);
                }

                if (ErrorUtils.isRetryableError(e.getErrorCode())) {
                    logger.debug("Retrying operation {} (attempt {}/{}): {}",
                            operation, attempts, maxRetries + 1, e.getMessage());

                    // Exponential backoff with jitter
                    try {
                        long backoffTime = Math.min(100 * (1L << (attempts - 1)), 1000);
                        Thread.sleep(backoffTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return OperationResult.failure(new TimeoutException(operation, timeout));
                    }
                } else {
                    ErrorUtils.logError(e);
                    return OperationResult.failure(e);
                }
            } catch (Exception e) {
                ErrorUtils.logError(operation, e);
                return OperationResult.failure(new SystemCallException(operation, "unknown", e));
            }
        }

        return OperationResult.failure(new TimeoutException(operation, timeout));
    }

    private SystemCapabilities detectSystemCapabilities() throws ConfigurationException {
        try {
            boolean numaAvailable = numaManager != null && numaManager.isAvailable();
            boolean perfCountersAvailable = performanceMonitor != null && performanceMonitor.isAvailable();
            boolean realtimeSupported = config.isRealtimeFeaturesEnabled() &&
                    platformProvider.isRealtimeSupported();

            return new SystemCapabilities(
                    platformProvider.getPlatformInfo(),
                    topologyDetector.getCpuCount(),
                    numaAvailable,
                    perfCountersAvailable,
                    realtimeSupported,
                    platformProvider.getSupportedFeatures()
            );
        } catch (Exception e) {
            throw new ConfigurationException("detectSystemCapabilities",
                    "Failed to detect system capabilities: " + e.getMessage());
        }
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation, Map<String, Object> context) {
        String description = ErrorCodes.getErrorDescription(errorCode);

        switch (errorCode) {
            case ErrorCodes.ERROR_INVALID_PARAMETER:
                InvalidParameterException ipe = new InvalidParameterException(operation, description);
                context.forEach(ipe::addContext);
                return ipe;

            case ErrorCodes.ERROR_PERMISSION_DENIED:
                PermissionDeniedException pde = new PermissionDeniedException(operation, description, null);
                context.forEach(pde::addContext);
                return pde;

            case ErrorCodes.ERROR_NOT_SUPPORTED:
                com.faster.affinity.exceptions.UnsupportedOperationException uoe = new com.faster.affinity.exceptions.UnsupportedOperationException(operation, description);
                context.forEach(uoe::addContext);
                return uoe;

            case ErrorCodes.ERROR_TIMEOUT:
                TimeoutException te = new TimeoutException(operation, config.getOperationTimeoutMs());
                context.forEach(te::addContext);
                return te;

            default:
                SystemCallException sce = new SystemCallException(operation, "platform_call", null);
                context.forEach(sce::addContext);
                return sce;
        }
    }

    // Utility methods

    public static BitSet longArrayToBitSet(long[] array, int maxBits) {
        if (array == null || maxBits <= 0) {
            return new BitSet();
        }

        // Protect against integer overflow
        int safeMaxBits = Math.min(maxBits, MAX_SUPPORTED_CPUS);
        BitSet bitSet = new BitSet(safeMaxBits);
        int arrayLength = Math.min(array.length, (safeMaxBits + 63) / 64);

        for (int i = 0; i < arrayLength; i++) {
            long word = array[i];
            if (word == 0) continue;

            int baseOffset = i * 64;
            for (int bit = 0; bit < 64 && (baseOffset + bit) < safeMaxBits; bit++) {
                if ((word & (1L << bit)) != 0) {
                    bitSet.set(baseOffset + bit);
                }
            }
        }
        return bitSet;
    }

    public static long[] bitSetToLongArray(BitSet bitSet) {
        if (bitSet == null || bitSet.isEmpty()) {
            return new long[1];
        }

        int maxBit = bitSet.length();
        if (maxBit > MAX_SUPPORTED_CPUS) {
            throw new IllegalArgumentException("BitSet size exceeds maximum supported CPUs: " + maxBit + " > " + MAX_SUPPORTED_CPUS);
        }

        int arrayLength = Math.min((maxBit + 63) / 64, MAX_LONG_ARRAY_SIZE);

        long[] tlArray = TL_MASK_ARRAY.get();
        long[] array;

        if (tlArray.length >= arrayLength) {
            array = tlArray;
            // Clear the array
            java.util.Arrays.fill(array, 0, arrayLength, 0L);
        } else {
            array = new long[arrayLength];
        }

        // Set bits
        for (int i = bitSet.nextSetBit(0); i >= 0 && i < maxBit; i = bitSet.nextSetBit(i + 1)) {
            int wordIndex = i / 64;
            if (wordIndex < arrayLength) {
                array[wordIndex] |= (1L << (i % 64));
            }
        }

        if (array == tlArray) {
            long[] result = new long[arrayLength];
            System.arraycopy(array, 0, result, 0, arrayLength);
            return result;
        }

        return array;
    }

    // Cleanup

    public void shutdown() {
        logger.info("Shutting down AffinityManager...");

        if (performanceMonitor != null) {
            try {
                performanceMonitor.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down performance monitor: {}", e.getMessage());
            }
        }

        if (numaManager != null) {
            try {
                numaManager.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down NUMA manager: {}", e.getMessage());
            }
        }

        if (irqManager != null) {
            try {
                irqManager.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down IRQ manager: {}", e.getMessage());
            }
        }

        if (cpuGovernorManager != null) {
            try {
                cpuGovernorManager.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down CPU governor manager: {}", e.getMessage());
            }
        }

        operationCache.clear();
        initialized.set(false);
        initializing.set(false);

        logger.info("AffinityManager shutdown complete");
    }

    // Helper classes

    private static class CacheEntry {
        private final Object value;
        private final long timestamp;

        public CacheEntry(Object value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public Object getValue() { return value; }
        public boolean isExpired(long expiryMs) {
            return System.currentTimeMillis() - timestamp > expiryMs;
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T execute() throws AffinityException;
    }

    public static class SystemCapabilities {
        private final String platformInfo;
        private final int cpuCount;
        private final boolean numaAvailable;
        private final boolean performanceCountersAvailable;
        private final boolean realtimeSupported;
        private final String[] supportedFeatures;

        public SystemCapabilities(String platformInfo, int cpuCount, boolean numaAvailable,
                                  boolean performanceCountersAvailable, boolean realtimeSupported,
                                  String[] supportedFeatures) {
            this.platformInfo = platformInfo;
            this.cpuCount = cpuCount;
            this.numaAvailable = numaAvailable;
            this.performanceCountersAvailable = performanceCountersAvailable;
            this.realtimeSupported = realtimeSupported;
            this.supportedFeatures = supportedFeatures != null ? supportedFeatures.clone() : new String[0];
        }

        // Getters
        public String getPlatformInfo() { return platformInfo; }
        public int getCpuCount() { return cpuCount; }
        public boolean isNumaAvailable() { return numaAvailable; }
        public boolean arePerformanceCountersAvailable() { return performanceCountersAvailable; }
        public boolean isRealtimeSupported() { return realtimeSupported; }
        public String[] getSupportedFeatures() { return supportedFeatures.clone(); }

        public boolean isGovernorControlSupported() {
            for (String feature : supportedFeatures) {
                if ("cpu_governor_control".equals(feature)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format("SystemCapabilities{platform=%s, cpus=%d, numa=%s, perf=%s, rt=%s, features=%d}",
                    platformInfo, cpuCount, numaAvailable, performanceCountersAvailable,
                    realtimeSupported, supportedFeatures.length);
        }
    }
}