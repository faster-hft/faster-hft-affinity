package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.platform.PlatformProvider;
import com.faster.affinity.platform.PlatformProviderFactory;
import com.faster.affinity.topology.TopologyDetector;
import com.faster.affinity.cache.HotPathCache;
import com.faster.affinity.cache.BoundedCache;
import com.faster.affinity.pool.ObjectPoolManager;
import com.faster.affinity.numa.NumaAffinityChecker;
import com.faster.affinity.performance.HFTPerformanceProfiler;
import com.faster.affinity.annotations.HotPath;
import com.faster.affinity.annotations.ColdPath;
import com.faster.affinity.security.RateLimiter;
import com.faster.affinity.security.AuditLogger;
import com.faster.affinity.utils.ThreadLocalManager;
import com.faster.affinity.transaction.TransactionManager;
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

    /**
     * Lightweight exception for transaction control flow that doesn't generate stack traces.
     */
    private static class TransactionOperationException extends RuntimeException {
        private final int errorCode;

        public TransactionOperationException(String message, int errorCode) {
            super(message, null, false, false); // No stack trace for performance
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }
    private static final Logger logger = LoggerFactory.getLogger(AffinityManager.class);

    // Maximum supported CPUs to prevent integer overflow
    private static final int MAX_SUPPORTED_CPUS = 4096;
    private static final int MAX_LONG_ARRAY_SIZE = (MAX_SUPPORTED_CPUS + 63) / 64;

    // Thread-local empty BitSet to avoid race conditions while preventing allocations
    // MEMORY LEAK FIX: Use ThreadLocalManager for proper cleanup in thread pools
    private static final ThreadLocalManager.ManagedThreadLocal<BitSet> THREAD_LOCAL_EMPTY_BITSET =
        ThreadLocalManager.create("affinity-empty-bitset", () -> new BitSet(), BitSet::clear);

    private static final AtomicReference<AffinityManager> instanceRef = new AtomicReference<>();
    private static final Object initializationLock = new Object();

    private final AffinityConfig config;
    private final TopologyDetector topologyDetector;
    private final PerformanceMonitor performanceMonitor;
    private final NUMAManager numaManager;
    private final IRQManager irqManager;
    private final CPUGovernorManager cpuGovernorManager;
    private final HugepageManager hugepageManager;
    private final PrefetchManager prefetchManager;
    private final PlatformProvider platformProvider;

    // Bounded caching for memory safety
    private final BoundedCache<String, Object> operationCache;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private final AtomicReference<SystemCapabilities> systemCapabilities = new AtomicReference<>();

    // High-performance caching infrastructure
    private final HotPathCache hotPathCache = new HotPathCache();

    // Lock-free operations for hot paths
    private volatile LockFreeAffinityOperations lockFreeOps;

    // HFT Performance profiler
    private volatile HFTPerformanceProfiler hftProfiler;

    // Rate limiter for DoS protection
    private final RateLimiter rateLimiter;

    // Managed thread-local arrays for performance with automatic cleanup
    private static final ThreadLocalManager.ManagedThreadLocal<long[]> TL_MASK_ARRAY =
        ThreadLocalManager.create("affinity-mask-array", () -> new long[MAX_LONG_ARRAY_SIZE]);
    private static final ThreadLocalManager.ManagedThreadLocal<int[]> TL_INT_ARRAY =
        ThreadLocalManager.create("affinity-int-array", () -> new int[8]);

    private AffinityManager(AffinityConfig config) {
        this.config = config;

        // Initialize bounded cache with configuration-based sizing
        int cacheSize = config.isRateLimitingEnabled() ?
            (int) Math.min(config.getMaxOperationsPerSecond() * 2, 5000) : 1000;
        this.operationCache = new BoundedCache<>(cacheSize, config.getCacheExpiryMs());

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
        this.hugepageManager = config.isHugepageManagementEnabled() ?
                new HugepageManager(platformProvider, config) : null;
        this.prefetchManager = config.isMemoryPrefetchingEnabled() ?
                new PrefetchManager(platformProvider, config) : null;

        // Initialize rate limiter for DoS protection
        this.rateLimiter = new RateLimiter(
            config.getMaxOperationsPerSecond(),
            config.getMaxBurstOperations(),
            1000 // 1 second window
        );

        logger.info("AffinityManager created with config: {} and rate limiting enabled", config);
    }

    public static AffinityManager getInstance() throws ConfigurationException {
        AffinityManager result = instanceRef.get();
        if (result == null) {
            synchronized (initializationLock) {
                // Double-checked locking pattern
                result = instanceRef.get();
                if (result == null) {
                    logger.debug("Creating new AffinityManager instance");
                    AffinityManager newInstance = new AffinityManager(AffinityConfig.getInstance());

                    try {
                        newInstance.initialize();
                        // Only set the reference after successful initialization
                        instanceRef.set(newInstance);
                        result = newInstance;
                        logger.info("AffinityManager instance created and initialized successfully");
                    } catch (Exception e) {
                        // Clean up failed instance
                        try {
                            newInstance.shutdown();
                        } catch (Exception cleanupException) {
                            logger.debug("Error during cleanup of failed instance: {}", cleanupException.getMessage());
                        }
                        throw new ConfigurationException("getInstance", "Failed to initialize AffinityManager: " + e.getMessage(), e);
                    }
                }
            }
        }

        // Verify instance is properly initialized
        if (!result.initialized.get()) {
            throw new ConfigurationException("getInstance", "AffinityManager instance exists but is not properly initialized");
        }

        return result;
    }

    public static AffinityManager getInstance(AffinityConfig config) throws ConfigurationException {
        if (config == null) {
            throw new ConfigurationException("getInstance", "Configuration cannot be null");
        }

        synchronized (initializationLock) {
            AffinityManager oldInstance = instanceRef.get();
            if (oldInstance != null) {
                logger.warn("Replacing existing AffinityManager instance with new configuration");
                try {
                    oldInstance.shutdown();
                } catch (Exception e) {
                    logger.warn("Error shutting down existing instance during replacement: {}", e.getMessage());
                }
            }

            logger.debug("Creating new AffinityManager instance with custom configuration");
            AffinityManager newInstance = new AffinityManager(config);

            try {
                newInstance.initialize();
                instanceRef.set(newInstance);
                logger.info("AffinityManager instance replaced and initialized successfully");
                return newInstance;
            } catch (Exception e) {
                // Clean up failed instance
                try {
                    newInstance.shutdown();
                } catch (Exception cleanupException) {
                    logger.debug("Error during cleanup of failed replacement instance: {}", cleanupException.getMessage());
                }
                throw new ConfigurationException("getInstance", "Failed to initialize AffinityManager with custom config: " + e.getMessage(), e);
            }
        }
    }

    private void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        if (!initializing.compareAndSet(false, true)) {
            // Another thread is initializing, use lock-free waiting with timeout
            long startTime = System.nanoTime();
            long timeoutNanos = 5_000_000_000L; // 5 seconds timeout

            while (initializing.get() && !initialized.get()) {
                if (System.nanoTime() - startTime > timeoutNanos) {
                    throw new ConfigurationException("initialize", "Initialization timeout waiting for another thread");
                }
                // Use pause instruction instead of sleep for CPU efficiency
                Thread.onSpinWait();
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
            if (hugepageManager != null) {
                hugepageManager.initialize();
            }
            if (prefetchManager != null) {
                prefetchManager.initialize();
            }

            // Initialize HFT performance profiler
            hftProfiler = new HFTPerformanceProfiler();

            // Initialize lock-free operations for hot paths
            lockFreeOps = new LockFreeAffinityOperations(platformProvider, hotPathCache, hftProfiler);

            // Enable NUMA-aware pooling if NUMA is available
            if (numaManager != null && numaManager.isAvailable()) {
                NumaAffinityChecker numaChecker = new NumaAffinityChecker(platformProvider, numaManager);
                ObjectPoolManager.enableNumaAwareness(numaChecker);
            }

            initialized.set(true);

            // Audit log successful system initialization
            AuditLogger.logSystemInitialization("AffinityManager", "1.0.0",
                config.toString(), true);

            logger.info("AffinityManager initialized successfully: {}", caps);

        } catch (Exception e) {
            // Audit log failed system initialization
            AuditLogger.logSystemInitialization("AffinityManager", "1.0.0",
                "INIT_FAILED: " + e.getMessage(), false);

            logger.error("Failed to initialize AffinityManager", e);
            throw new ConfigurationException("initialize", "Failed to initialize AffinityManager: " + e.getMessage());
        } finally {
            initializing.set(false);
        }
    }

    /**
     * Check if the AffinityManager is properly initialized
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Get HFT performance statistics for monitoring and optimization.
     */
    public HFTPerformanceProfiler.HFTPerformanceStats getHFTPerformanceStats() {
        if (hftProfiler != null) {
            return hftProfiler.getStats();
        }
        return null;
    }

    /**
     * Reset HFT performance statistics.
     */
    public void resetHFTPerformanceStats() {
        if (hftProfiler != null) {
            hftProfiler.reset();
        }
    }

    /**
     * Enable or disable HFT performance monitoring.
     */
    public void setHFTPerformanceMonitoringEnabled(boolean enabled) {
        if (hftProfiler != null) {
            hftProfiler.setEnabled(enabled);
        }
    }

    // Core CPU Affinity Operations

    /**
     * High-performance version of getCurrentThreadAffinity for hot paths.
     * Uses lock-free caching and pre-allocated objects.
     */
    @HotPath("Primary hot path for thread affinity queries")
    public OperationResult<BitSet> getCurrentThreadAffinityFast() {
        // Profiling disabled for maximum performance - use compile-time flag if needed
        if (lockFreeOps != null) {
            return lockFreeOps.getCurrentThreadAffinityFast();
        }

        // Fallback to standard implementation
        return getThreadAffinity(platformProvider.getCurrentThreadId());
    }

    /**
     * High-performance version of setCurrentThreadAffinity for hot paths.
     * Uses lock-free caching and pre-allocated objects.
     */
    @HotPath("Primary hot path for thread affinity updates")
    public OperationResult<Void> setCurrentThreadAffinityFast(BitSet cpuMask) {
        if (lockFreeOps != null) {
            return lockFreeOps.setCurrentThreadAffinityFast(cpuMask);
        }
        // Fallback to standard implementation
        return setThreadAffinity(platformProvider.getCurrentThreadId(), cpuMask);
    }

    /**
     * Bulk affinity operations for multiple threads (hot path optimized).
     */
    @HotPath("Bulk operations for multiple thread affinity updates")
    public int setBulkThreadAffinityFast(long[] threadIds, BitSet cpuMask) {
        if (lockFreeOps != null) {
            return lockFreeOps.setBulkThreadAffinity(threadIds, cpuMask);
        }
        // Fallback implementation
        int successCount = 0;
        for (long threadId : threadIds) {
            OperationResult<Void> result = setThreadAffinity(threadId, cpuMask);
            if (result.isSuccess()) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * Transactional bulk affinity operations with rollback on failure.
     * Ensures all-or-nothing semantics for critical multi-thread operations.
     */
    public OperationResult<Integer> setBulkThreadAffinityTransactional(long[] threadIds, BitSet cpuMask) {
        if (threadIds == null || threadIds.length == 0) {
            return OperationResult.invalidParameterFailure();
        }

        if (cpuMask == null || cpuMask.isEmpty()) {
            return OperationResult.invalidParameterFailure();
        }

        try {
            Integer result = TransactionManager.executeTransaction("setBulkThreadAffinityTransactional", context -> {
                int successCount = 0;

                for (long threadId : threadIds) {
                    // Get current affinity for rollback
                    OperationResult<BitSet> affinityResult = getThreadAffinity(threadId);
                    BitSet currentAffinity = affinityResult.isSuccess() ? affinityResult.getValue() : null;

                    // Execute the affinity change with rollback action
                    context.executeWithRollback(
                        "setThreadAffinity_" + threadId,
                        () -> {
                            OperationResult<Void> setResult = setThreadAffinity(threadId, cpuMask);
                            if (!setResult.isSuccess()) {
                                // Use a lightweight exception for transaction control flow
                                throw new TransactionOperationException("Failed to set affinity for thread " + threadId,
                                    setResult.getErrorCode());
                            }
                        },
                        () -> {
                            // Rollback: restore original affinity
                            if (currentAffinity != null) {
                                try {
                                    setThreadAffinity(threadId, currentAffinity);
                                    logger.debug("Rolled back affinity for thread {}", threadId);
                                } catch (Exception rollbackError) {
                                    logger.error("Failed to rollback affinity for thread {}: {}",
                                        threadId, rollbackError.getMessage());
                                }
                            }
                        }
                    );

                    successCount++;
                }

                AuditLogger.logAffinityOperation(AuditLogger.AuditEventType.AFFINITY_SET,
                    "setBulkThreadAffinityTransactional", -1, cpuMask.toString(),
                    "Successfully set affinity for " + successCount + " threads");

                return successCount;
            });
            return OperationResult.success(result);

        } catch (TransactionManager.TransactionException e) {
            String errorMsg = "Transactional bulk affinity operation failed: " + e.getMessage();
            logger.error(errorMsg, e);

            AuditLogger.logSecurityViolation(AuditLogger.AuditEventType.AFFINITY_SET,
                "setBulkThreadAffinityTransactional", errorMsg,
                "Transaction ID: " + e.getTransactionId());

            return OperationResult.failure(ErrorCodes.ERROR_TRANSACTION_ROLLBACK_FAILED, "setBulkThreadAffinityTransactional", errorMsg);
        } catch (Exception e) {
            String errorMsg = "Unexpected error in transactional bulk affinity operation: " + e.getMessage();
            logger.error(errorMsg, e);
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "setBulkThreadAffinityTransactional", errorMsg);
        }
    }

    /**
     * Transactionally configure a complete HFT environment for a thread.
     * Sets up affinity, NUMA, CPU governor, and hugepages with rollback capability.
     */
    public OperationResult<Void> configureHFTEnvironmentTransactional(long threadId, BitSet cpuMask,
                                                                     CPUGovernorManager.GovernorMode governor) {
        if (threadId <= 0) {
            return OperationResult.failure(new InvalidParameterException("configureHFTEnvironmentTransactional",
                "threadId", "Thread ID must be positive"));
        }

        if (cpuMask == null || cpuMask.isEmpty()) {
            return OperationResult.failure(new InvalidParameterException("configureHFTEnvironmentTransactional",
                "cpuMask", "CPU mask cannot be null or empty"));
        }

        try {
            return TransactionManager.executeTransaction("configureHFTEnvironmentTransactional", context -> {
                // Step 1: Get current state for rollback
                OperationResult<BitSet> currentAffinityResult = getThreadAffinity(threadId);
                BitSet currentAffinity = currentAffinityResult.isSuccess() ? currentAffinityResult.getValue() : null;

                // Step 2: Set thread affinity with rollback
                context.executeWithRollback(
                    "setThreadAffinity",
                    () -> {
                        OperationResult<Void> affinityResult = setThreadAffinity(threadId, cpuMask);
                        if (!affinityResult.isSuccess()) {
                            throw new RuntimeException("Failed to set thread affinity: " + affinityResult.getError().getMessage());
                        }
                    },
                    () -> {
                        if (currentAffinity != null) {
                            try {
                                setThreadAffinity(threadId, currentAffinity);
                                logger.debug("Rolled back thread affinity for thread {}", threadId);
                            } catch (Exception e) {
                                logger.error("Failed to rollback thread affinity: {}", e.getMessage());
                            }
                        }
                    }
                );

                // Step 3: Configure CPU governor for affected cores
                if (cpuGovernorManager != null && governor != null) {
                    for (int coreId = cpuMask.nextSetBit(0); coreId >= 0; coreId = cpuMask.nextSetBit(coreId + 1)) {
                        final int finalCoreId = coreId;

                        // Get current governor for rollback
                        OperationResult<CPUGovernorManager.GovernorMode> currentGovernorResult =
                            cpuGovernorManager.getCurrentGovernor(coreId);
                        CPUGovernorManager.GovernorMode currentGovernor =
                            currentGovernorResult.isSuccess() ? currentGovernorResult.getValue() : null;

                        context.executeWithRollback(
                            "setGovernor_" + coreId,
                            () -> {
                                OperationResult<Void> govResult = cpuGovernorManager.setGovernor(finalCoreId, governor);
                                if (!govResult.isSuccess()) {
                                    throw new RuntimeException("Failed to set CPU governor for core " + finalCoreId +
                                        ": " + govResult.getError().getMessage());
                                }
                            },
                            () -> {
                                if (currentGovernor != null) {
                                    try {
                                        cpuGovernorManager.setGovernor(finalCoreId, currentGovernor);
                                        logger.debug("Rolled back CPU governor for core {}", finalCoreId);
                                    } catch (Exception e) {
                                        logger.error("Failed to rollback CPU governor for core {}: {}", finalCoreId, e.getMessage());
                                    }
                                }
                            }
                        );
                    }
                }

                // Step 4: Configure hugepages if available
                if (hugepageManager != null) {
                    context.executeWithRollback(
                        "configureHugepages",
                        () -> {
                            OperationResult<Void> hugepageResult = hugepageManager.configureForHFT();
                            if (!hugepageResult.isSuccess()) {
                                logger.warn("Failed to configure hugepages: {}", hugepageResult.getError().getMessage());
                                // Don't fail the entire transaction for hugepage configuration
                            }
                        },
                        () -> {
                            // Hugepage rollback is complex and system-wide, so we just log
                            logger.info("Hugepage configuration would need manual rollback");
                        }
                    );
                }

                AuditLogger.logAffinityOperation(AuditLogger.AuditEventType.AFFINITY_SET,
                    "configureHFTEnvironmentTransactional", threadId, cpuMask.toString(),
                    "Successfully configured HFT environment");

                return null; // Void return
            });

        } catch (TransactionManager.TransactionException e) {
            String errorMsg = "HFT environment configuration failed: " + e.getMessage();
            logger.error(errorMsg, e);

            AuditLogger.logSecurityViolation(AuditLogger.AuditEventType.SYSTEM_INITIALIZATION,
                "configureHFTEnvironmentTransactional", errorMsg,
                "Transaction ID: " + e.getTransactionId() + ", Thread: " + threadId);

            return OperationResult.failure(new OperationFailedException("configureHFTEnvironmentTransactional", errorMsg, e));

        } catch (Exception e) {
            String errorMsg = "Unexpected error in HFT environment configuration: " + e.getMessage();
            logger.error(errorMsg, e);
            return OperationResult.failure(new OperationFailedException("configureHFTEnvironmentTransactional", errorMsg, e));
        }
    }

    @HotPath("Standard thread affinity setting operation")
    public OperationResult<Void> setThreadAffinity(long threadId, BitSet cpuMask) {
        return executeWithRetry("setThreadAffinity", () -> {
            // Rate limiting check for DoS protection
            if (config.isRateLimitingEnabled() && !rateLimiter.tryAcquire()) {
                AuditLogger.logRateLimitViolation("setThreadAffinity", threadId,
                    "Max operations per second: " + config.getMaxOperationsPerSecond());
                throw new SecurityException("Rate limit exceeded for setThreadAffinity operation. Thread: " +
                                          Thread.currentThread().getId());
            }

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
                operationCache.put(key, cpuMask.clone());
            }

            // Audit log successful affinity operation
            AuditLogger.logAffinityOperation(AuditLogger.AuditEventType.AFFINITY_SET,
                "setThreadAffinity", threadId, cpuMask.toString(),
                "Successfully set thread affinity to " + cpuMask.cardinality() + " CPUs");

            logger.debug("Successfully set thread {} affinity to {}", threadId, cpuMask);
            return null;
        });
    }

    @HotPath("Standard thread affinity query operation")
    public OperationResult<BitSet> getThreadAffinity(long threadId) {
        return executeWithRetry("getThreadAffinity", () -> {
            // Rate limiting check for DoS protection
            if (config.isRateLimitingEnabled() && !rateLimiter.tryAcquire()) {
                AuditLogger.logRateLimitViolation("getThreadAffinity", threadId,
                    "Max operations per second: " + config.getMaxOperationsPerSecond());
                throw new SecurityException("Rate limit exceeded for getThreadAffinity operation. Thread: " +
                                          Thread.currentThread().getId());
            }

            validateParameters("getThreadAffinity", threadId);

            // Check cache first
            if (config.isCachingEnabled()) {
                String key = "thread_affinity_" + threadId;
                Object cached = operationCache.get(key);
                if (cached != null) {
                    logger.debug("Returning cached thread affinity for {}", threadId);
                    return (BitSet) ((BitSet) cached).clone();
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
                operationCache.put(key, cpuMask.clone());
            }

            // Audit log successful affinity query
            AuditLogger.logAffinityOperation(AuditLogger.AuditEventType.AFFINITY_GET,
                "getThreadAffinity", threadId, cpuMask.toString(),
                "Successfully retrieved thread affinity: " + cpuMask.cardinality() + " CPUs");

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

    public HugepageManager getHugepageManager() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (hugepageManager == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getHugepageManager",
                    "Hugepage management is disabled in configuration");
        }
        return hugepageManager;
    }

    public PrefetchManager getPrefetchManager() throws com.faster.affinity.exceptions.UnsupportedOperationException {
        if (prefetchManager == null) {
            throw new com.faster.affinity.exceptions.UnsupportedOperationException("getPrefetchManager",
                    "Memory prefetching is disabled in configuration");
        }
        return prefetchManager;
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

    // Hugepage Control operations (TLB miss reduction)

    public OperationResult<HugepageManager.HugepageMode> getCurrentHugepageMode() {
        if (hugepageManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getCurrentHugepageMode",
                    "Hugepage management is disabled in configuration"));
        }
        return hugepageManager.getCurrentMode();
    }

    public OperationResult<Void> setHugepageMode(HugepageManager.HugepageMode mode) {
        if (hugepageManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setHugepageMode",
                    "Hugepage management is disabled in configuration"));
        }
        return hugepageManager.setMode(mode);
    }

    public OperationResult<HugepageManager.HugepageInfo> getHugepageInfo() {
        if (hugepageManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getHugepageInfo",
                    "Hugepage management is disabled in configuration"));
        }
        return hugepageManager.getHugepageInfo();
    }

    public OperationResult<Void> configureHugepagesForHFT() {
        if (hugepageManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("configureHugepagesForHFT",
                    "Hugepage management is disabled in configuration"));
        }
        return hugepageManager.configureForHFT();
    }

    public OperationResult<Void> restoreOriginalHugepageSettings() {
        if (hugepageManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("restoreOriginalHugepageSettings",
                    "Hugepage management is disabled in configuration"));
        }
        return hugepageManager.restoreOriginalSettings();
    }

    // Memory Prefetching operations (cache optimization)

    public OperationResult<Void> prefetchAddress(long address, PrefetchManager.PrefetchType type) {
        if (prefetchManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("prefetchAddress",
                    "Memory prefetching is disabled in configuration"));
        }
        return prefetchManager.prefetchAddress(address, type);
    }

    public OperationResult<Void> prefetchDataStructure(long baseAddress, int elementSize,
                                                      int elementCount, PrefetchManager.AccessPattern pattern) {
        if (prefetchManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("prefetchDataStructure",
                    "Memory prefetching is disabled in configuration"));
        }
        return prefetchManager.prefetchDataStructure(baseAddress, elementSize, elementCount, pattern);
    }

    public OperationResult<Boolean> isOptimallyAligned(long address, int accessSize) {
        if (prefetchManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("isOptimallyAligned",
                    "Memory prefetching is disabled in configuration"));
        }
        return prefetchManager.isOptimallyAligned(address, accessSize);
    }

    public OperationResult<Long> alignAddressForPerformance(long address, int accessSize) {
        if (prefetchManager == null) {
            return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("alignAddressForPerformance",
                    "Memory prefetching is disabled in configuration"));
        }
        return prefetchManager.alignAddressForPerformance(address, accessSize);
    }

    public PrefetchManager.PrefetchConfig createHFTPrefetchConfig(PrefetchManager.AccessPattern pattern) {
        if (prefetchManager == null) {
            throw new IllegalStateException("Memory prefetching is disabled in configuration");
        }
        return prefetchManager.createHFTConfig(pattern);
    }

    // Validation and error handling

    @ColdPath("Parameter validation - infrequent operation")
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

                    // Lock-free exponential backoff using spin-wait
                    long backoffNanos = Math.min(100_000 * (1L << (attempts - 1)), 1_000_000); // Convert to nanos
                    long endTime = System.nanoTime() + backoffNanos;

                    // Active wait for better latency in HFT scenarios
                    while (System.nanoTime() < endTime) {
                        Thread.onSpinWait(); // CPU pause instruction
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

    @ColdPath("Error handling - infrequent operation")
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
            // Return thread-local empty BitSet to avoid race conditions
            return THREAD_LOCAL_EMPTY_BITSET.get();
        }

        // Protect against integer overflow
        int safeMaxBits = Math.min(maxBits, MAX_SUPPORTED_CPUS);

        // Try to use pooled BitSet instead of allocating
        // Note: The returned BitSet will be owned by the caller
        BitSet bitSet;
        try {
            bitSet = ObjectPoolManager.getBitSetPool().acquire();
            if (bitSet != null) {
                bitSet.clear();
                // Note: We don't return this to pool since caller will own the BitSet
            } else {
                bitSet = new BitSet(safeMaxBits);
            }
        } catch (Exception ignored) {
            // Pool unavailable, fall back to allocation
            bitSet = new BitSet(safeMaxBits);
        }
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

        if (hugepageManager != null) {
            try {
                hugepageManager.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down hugepage manager: {}", e.getMessage());
            }
        }

        if (prefetchManager != null) {
            try {
                prefetchManager.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down prefetch manager: {}", e.getMessage());
            }
        }

        // Clean up ThreadLocal instances to prevent memory leaks
        try {
            ThreadLocalManager.cleanupAll();
            logger.debug("ThreadLocal cleanup completed during shutdown");
        } catch (Exception e) {
            logger.warn("Error during ThreadLocal cleanup: {}", e.getMessage());
        }

        operationCache.clear();
        initialized.set(false);
        initializing.set(false);

        logger.info("AffinityManager shutdown complete");
    }

    /**
     * Get rate limiting statistics for monitoring and debugging.
     */
    public RateLimiter.RateLimiterStats getRateLimitingStats() {
        return rateLimiter.getStats();
    }

    /**
     * Get audit logging statistics for monitoring and debugging.
     */
    public AuditLogger.AuditStats getAuditStats() {
        return AuditLogger.getAuditStats();
    }

    /**
     * Get ThreadLocal usage statistics for memory leak monitoring.
     */
    public ThreadLocalManager.ThreadLocalStats getThreadLocalStats() {
        return ThreadLocalManager.getStats();
    }

    /**
     * Check for ThreadLocal memory leaks and log warnings.
     */
    public void checkForThreadLocalLeaks() {
        ThreadLocalManager.checkForLeaks();
    }

    /**
     * Clean up ThreadLocal values for the current thread.
     * Should be called when threads are returned to pools.
     */
    public static void cleanupCurrentThread() {
        ThreadLocalManager.cleanupCurrentThread();
    }

    /**
     * Get cache statistics for monitoring and performance tuning.
     */
    public BoundedCache.CacheStats getCacheStats() {
        return operationCache.getStats();
    }

    /**
     * Check cache health and log warnings for potential issues.
     */
    public void checkCacheHealth() {
        operationCache.checkHealth();
    }

    // Helper classes

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