package com.faster.affinity.config;

import java.util.Properties;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive configuration management for the Faster Thread Affinity Library,
 * providing fine-grained control over performance characteristics, security settings,
 * and feature enablement specifically optimized for high-frequency trading (HFT) applications.
 *
 * <p>This configuration class uses the Builder pattern to provide a fluent API for
 * constructing library configurations. It supports a wide range of performance tuning
 * options, from basic caching settings to advanced HFT optimizations like IRQ isolation
 * and CPU governor control.
 *
 * <h3>Configuration Categories</h3>
 *
 * <h4>Performance Optimization</h4>
 * <ul>
 *   <li><strong>Caching</strong> - Operation result caching with configurable TTL</li>
 *   <li><strong>Thread-Local Caching</strong> - Per-thread cache optimization</li>
 *   <li><strong>Rate Limiting</strong> - DoS protection with configurable limits</li>
 *   <li><strong>Timeouts</strong> - Operation timeout configuration</li>
 * </ul>
 *
 * <h4>HFT-Specific Features</h4>
 * <ul>
 *   <li><strong>IRQ Management</strong> - Interrupt isolation for trading cores</li>
 *   <li><strong>CPU Governor Control</strong> - Frequency scaling optimization</li>
 *   <li><strong>NUMA Operations</strong> - Memory locality optimization</li>
 *   <li><strong>Hugepage Management</strong> - TLB miss reduction</li>
 * </ul>
 *
 * <h4>Development and Testing</h4>
 * <ul>
 *   <li><strong>Test Mode</strong> - Disables rate limiting for high-frequency tests</li>
 *   <li><strong>Developer Mode</strong> - Enhanced logging and debugging features</li>
 *   <li><strong>Parameter Validation</strong> - Input validation controls</li>
 * </ul>
 *
 * <h3>Usage Examples</h3>
 *
 * <h4>Production HFT Configuration</h4>
 * <pre>{@code
 * AffinityConfig hftConfig = new AffinityConfig.Builder()
 *     // Performance optimization
 *     .enableCaching(true)                    // Cache operation results
 *     .enableThreadLocalCaching(true)         // Thread-local optimization
 *     .cacheExpiryMs(10000)                   // 10 second cache TTL
 *     .operationTimeoutMs(500)                // Fast timeouts
 *     .maxRetryAttempts(1)                    // Minimal retries for speed
 *
 *     // HFT-specific features
 *     .enableNumaOperations(true)             // NUMA awareness
 *     .enablePerformanceCounters(true)        // Real-time monitoring
 *     .enableGovernorControl(true)            // CPU frequency control
 *     .enableIRQManagement(true)              // Interrupt isolation
 *     .enableHugepageManagement(true)         // Memory optimization
 *
 *     // Security and stability
 *     .maxOperationsPerSecond(10000)          // High throughput limit
 *     .maxBurstOperations(1000)               // Burst capacity
 *     .validateParameters(true)               // Input validation
 *
 *     // Production settings
 *     .developerMode(false)                   // Minimal logging
 *     .testMode(false)                        // Enable rate limiting
 *     .build();
 * }</pre>
 *
 * <h4>Development Configuration</h4>
 * <pre>{@code
 * AffinityConfig devConfig = new AffinityConfig.Builder()
 *     .developerMode(true)                    // Enhanced debugging
 *     .enableCaching(false)                   // Disable for deterministic behavior
 *     .validateParameters(true)               // Strict validation
 *     .operationTimeoutMs(5000)               // Longer timeouts for debugging
 *     .build();
 * }</pre>
 *
 * <h4>Test Configuration</h4>
 * <pre>{@code
 * AffinityConfig testConfig = new AffinityConfig.Builder()
 *     .testMode(true)                         // Disable rate limiting
 *     .enableCaching(false)                   // Deterministic behavior
 *     .developerMode(true)                    // Debug information
 *     .validateParameters(false)              // Skip validation for speed
 *     .maxRetryAttempts(0)                    // No retries in tests
 *     .build();
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> AffinityConfig instances are immutable once created.
 * The Builder class is not thread-safe and should be used by a single thread.
 *
 * <p><strong>Performance Impact:</strong> Configuration choices directly impact library
 * performance. For HFT applications, enable caching and disable extensive validation
 * for optimal latency characteristics.
 *
 * @author Amar Mond
 * @since 1.0.0
 * @version 1.0.0
 * @see Builder
 */
public final class AffinityConfig {
    private static final Logger logger = LoggerFactory.getLogger(AffinityConfig.class);

    // Default configuration values
    private static final boolean DEFAULT_ENABLE_PERFORMANCE_COUNTERS = true;
    private static final boolean DEFAULT_ENABLE_REALTIME_FEATURES = false;
    private static final boolean DEFAULT_ENABLE_NUMA_OPERATIONS = true;
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_OPERATION_TIMEOUT_MS = 1000;
    private static final boolean DEFAULT_VALIDATE_PARAMETERS = true;
    private static final boolean DEFAULT_ENABLE_CACHING = true;
    private static final long DEFAULT_CACHE_EXPIRY_MS = 5000;
    private static final boolean DEFAULT_DEVELOPER_MODE = false;

    // IRQ management defaults
    private static final boolean DEFAULT_ENABLE_IRQ_MANAGEMENT = true;
    private static final boolean DEFAULT_STRICT_IRQ_ISOLATION = false;
    private static final boolean DEFAULT_RESTORE_IRQ_AFFINITIES_ON_SHUTDOWN = true;
    private static final long DEFAULT_IRQ_SCAN_INTERVAL_MS = 30000; // 30 seconds

    // CPU Governor control defaults (HFT performance optimization)
    private static final boolean DEFAULT_ENABLE_GOVERNOR_CONTROL = true;
    private static final boolean DEFAULT_AUTO_SET_PERFORMANCE_GOVERNOR = false;
    private static final boolean DEFAULT_RESTORE_GOVERNORS_ON_SHUTDOWN = true;

    // Transparent Hugepage control defaults (TLB miss reduction)
    private static final boolean DEFAULT_ENABLE_HUGEPAGE_MANAGEMENT = true;

    // Rate limiting defaults (DoS protection)
    private static final long DEFAULT_MAX_OPERATIONS_PER_SECOND = 1000; // 1000 ops/sec per thread
    private static final long DEFAULT_MAX_BURST_OPERATIONS = 100;       // 100 burst operations
    private static final boolean DEFAULT_ENABLE_RATE_LIMITING = true;

    // Test mode configuration (disables rate limiting for high-frequency tests)
    private static final boolean DEFAULT_TEST_MODE = false;
    private static final boolean DEFAULT_AUTO_CONFIGURE_HUGEPAGES = false;
    private static final boolean DEFAULT_RESTORE_HUGEPAGE_SETTINGS_ON_SHUTDOWN = true;

    // Memory prefetching defaults (cache optimization)
    private static final boolean DEFAULT_ENABLE_MEMORY_PREFETCHING = true;
    private static final boolean DEFAULT_AUTO_DETECT_PREFETCH_CAPABILITIES = true;

    // Configuration instance
    private static final AtomicReference<AffinityConfig> instanceRef = new AtomicReference<>();

    // Configuration fields
    private final boolean enablePerformanceCounters;
    private final boolean enableRealtimeFeatures;
    private final boolean enableNumaOperations;
    private final int maxRetryAttempts;
    private final long operationTimeoutMs;
    private final boolean validateParameters;
    private final boolean enableCaching;
    private final long cacheExpiryMs;
    private final boolean developerMode;
    private final String logLevel;
    private final boolean enableThreadLocalCaching;
    private final int performanceCounterUpdateIntervalMs;
    private final boolean strictErrorHandling;

    // IRQ management fields
    private final boolean enableIRQManagement;
    private final boolean strictIRQIsolation;
    private final boolean restoreIRQAffinitiesOnShutdown;
    private final long irqScanIntervalMs;

    // CPU Governor control fields (HFT performance optimization)
    private final boolean enableGovernorControl;
    private final boolean autoSetPerformanceGovernor;
    private final boolean restoreGovernorsOnShutdown;

    // Transparent Hugepage control fields (TLB miss reduction)
    private final boolean enableHugepageManagement;
    private final boolean autoConfigureHugepages;
    private final boolean restoreHugepageSettingsOnShutdown;

    // Memory prefetching fields (cache optimization)
    private final boolean enableMemoryPrefetching;
    private final boolean autoDetectPrefetchCapabilities;

    // Rate limiting fields (DoS protection)
    private final boolean enableRateLimiting;
    private final long maxOperationsPerSecond;
    private final long maxBurstOperations;

    // Test mode fields (disables rate limiting for high-frequency tests)
    private final boolean testMode;

    private AffinityConfig(Builder builder) {
        this.enablePerformanceCounters = builder.enablePerformanceCounters;
        this.enableRealtimeFeatures = builder.enableRealtimeFeatures;
        this.enableNumaOperations = builder.enableNumaOperations;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.operationTimeoutMs = builder.operationTimeoutMs;
        this.validateParameters = builder.validateParameters;
        this.enableCaching = builder.enableCaching;
        this.cacheExpiryMs = builder.cacheExpiryMs;
        this.developerMode = builder.developerMode;
        this.logLevel = builder.logLevel;
        this.enableThreadLocalCaching = builder.enableThreadLocalCaching;
        this.performanceCounterUpdateIntervalMs = builder.performanceCounterUpdateIntervalMs;
        this.strictErrorHandling = builder.strictErrorHandling;

        // IRQ management initialization
        this.enableIRQManagement = builder.enableIRQManagement;
        this.strictIRQIsolation = builder.strictIRQIsolation;
        this.restoreIRQAffinitiesOnShutdown = builder.restoreIRQAffinitiesOnShutdown;
        this.irqScanIntervalMs = builder.irqScanIntervalMs;

        // HFT Performance optimization initialization
        this.enableGovernorControl = builder.enableGovernorControl;
        this.autoSetPerformanceGovernor = builder.autoSetPerformanceGovernor;
        this.restoreGovernorsOnShutdown = builder.restoreGovernorsOnShutdown;
        this.enableHugepageManagement = builder.enableHugepageManagement;
        this.autoConfigureHugepages = builder.autoConfigureHugepages;
        this.restoreHugepageSettingsOnShutdown = builder.restoreHugepageSettingsOnShutdown;
        this.enableMemoryPrefetching = builder.enableMemoryPrefetching;
        this.autoDetectPrefetchCapabilities = builder.autoDetectPrefetchCapabilities;

        // Rate limiting initialization
        this.enableRateLimiting = builder.enableRateLimiting;
        this.maxOperationsPerSecond = builder.maxOperationsPerSecond;
        this.maxBurstOperations = builder.maxBurstOperations;

        // Test mode initialization (disables rate limiting for high-frequency tests)
        this.testMode = builder.testMode;

        if (developerMode) {
            logger.info("AffinityConfig initialized in developer mode: {}", this);
        }
    }

    public static AffinityConfig getInstance() {
        AffinityConfig config = instanceRef.get();
        if (config == null) {
            // Lock-free initialization
            AffinityConfig newConfig = loadFromProperties();
            if (instanceRef.compareAndSet(null, newConfig)) {
                return newConfig;
            } else {
                // Another thread won the race
                return instanceRef.get();
            }
        }
        return config;
    }

    public static void setInstance(AffinityConfig config) {
        // Lock-free atomic update
        instanceRef.set(config);
        logger.info("AffinityConfig instance updated: {}", config);
    }

    /**
     * Create a new builder for custom configuration
     */
    public static Builder builder() {
        return new Builder();
    }

    private static AffinityConfig loadFromProperties() {
        Builder builder = new Builder();

        try (InputStream input = AffinityConfig.class.getResourceAsStream("/affinity.properties")) {
            if (input != null) {
                Properties props = new Properties();
                props.load(input);

                builder.enablePerformanceCounters(getBooleanProperty(props, "affinity.performance.counters.enabled", DEFAULT_ENABLE_PERFORMANCE_COUNTERS))
                        .enableRealtimeFeatures(getBooleanProperty(props, "affinity.realtime.enabled", DEFAULT_ENABLE_REALTIME_FEATURES))
                        .enableNumaOperations(getBooleanProperty(props, "affinity.numa.enabled", DEFAULT_ENABLE_NUMA_OPERATIONS))
                        .maxRetryAttempts(getIntProperty(props, "affinity.retry.max_attempts", DEFAULT_MAX_RETRY_ATTEMPTS))
                        .operationTimeoutMs(getLongProperty(props, "affinity.timeout.operation_ms", DEFAULT_OPERATION_TIMEOUT_MS))
                        .validateParameters(getBooleanProperty(props, "affinity.validation.enabled", DEFAULT_VALIDATE_PARAMETERS))
                        .enableCaching(getBooleanProperty(props, "affinity.cache.enabled", DEFAULT_ENABLE_CACHING))
                        .cacheExpiryMs(getLongProperty(props, "affinity.cache.expiry_ms", DEFAULT_CACHE_EXPIRY_MS))
                        .developerMode(getBooleanProperty(props, "affinity.developer.mode", DEFAULT_DEVELOPER_MODE))
                        .logLevel(props.getProperty("affinity.log.level", "INFO"))
                        .enableThreadLocalCaching(getBooleanProperty(props, "affinity.cache.thread_local", true))
                        .performanceCounterUpdateIntervalMs(getIntProperty(props, "affinity.performance.update_interval_ms", 100))
                        .strictErrorHandling(getBooleanProperty(props, "affinity.error.strict", false))

                        // IRQ management properties
                        .enableIRQManagement(getBooleanProperty(props, "affinity.irq.enabled", DEFAULT_ENABLE_IRQ_MANAGEMENT))
                        .strictIRQIsolation(getBooleanProperty(props, "affinity.irq.strict_isolation", DEFAULT_STRICT_IRQ_ISOLATION))
                        .restoreIRQAffinitiesOnShutdown(getBooleanProperty(props, "affinity.irq.restore_on_shutdown", DEFAULT_RESTORE_IRQ_AFFINITIES_ON_SHUTDOWN))
                        .irqScanIntervalMs(getLongProperty(props, "affinity.irq.scan_interval_ms", DEFAULT_IRQ_SCAN_INTERVAL_MS))

                        // HFT Performance optimization properties
                        .enableGovernorControl(getBooleanProperty(props, "affinity.hft.governor.enabled", DEFAULT_ENABLE_GOVERNOR_CONTROL))
                        .autoSetPerformanceGovernor(getBooleanProperty(props, "affinity.hft.governor.auto_performance", DEFAULT_AUTO_SET_PERFORMANCE_GOVERNOR))
                        .restoreGovernorsOnShutdown(getBooleanProperty(props, "affinity.hft.governor.restore_on_shutdown", DEFAULT_RESTORE_GOVERNORS_ON_SHUTDOWN))
                        .enableHugepageManagement(getBooleanProperty(props, "affinity.hft.hugepage.enabled", DEFAULT_ENABLE_HUGEPAGE_MANAGEMENT))
                        .autoConfigureHugepages(getBooleanProperty(props, "affinity.hft.hugepage.auto_configure", DEFAULT_AUTO_CONFIGURE_HUGEPAGES))
                        .restoreHugepageSettingsOnShutdown(getBooleanProperty(props, "affinity.hft.hugepage.restore_on_shutdown", DEFAULT_RESTORE_HUGEPAGE_SETTINGS_ON_SHUTDOWN))
                        .enableMemoryPrefetching(getBooleanProperty(props, "affinity.hft.prefetch.enabled", DEFAULT_ENABLE_MEMORY_PREFETCHING))
                        .autoDetectPrefetchCapabilities(getBooleanProperty(props, "affinity.hft.prefetch.auto_detect", DEFAULT_AUTO_DETECT_PREFETCH_CAPABILITIES));

                logger.info("Configuration loaded from properties file");
            } else {
                logger.info("No properties file found, using defaults");
            }
        } catch (IOException e) {
            logger.warn("Failed to load properties file, using defaults: {}", e.getMessage());
        }

        return builder.build();
    }

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        try {
            return value != null ? Long.parseLong(value) : defaultValue;
        } catch (NumberFormatException e) {
            logger.warn("Invalid long value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    // Getters
    public boolean isPerformanceCountersEnabled() { return enablePerformanceCounters; }
    public boolean isRealtimeFeaturesEnabled() { return enableRealtimeFeatures; }
    public boolean isNumaOperationsEnabled() { return enableNumaOperations; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public long getOperationTimeoutMs() { return operationTimeoutMs; }
    public boolean isParameterValidationEnabled() { return validateParameters; }
    public boolean isCachingEnabled() { return enableCaching; }
    public long getCacheExpiryMs() { return cacheExpiryMs; }
    public boolean isDeveloperMode() { return developerMode; }
    public String getLogLevel() { return logLevel; }
    public boolean isThreadLocalCachingEnabled() { return enableThreadLocalCaching; }
    public int getPerformanceCounterUpdateIntervalMs() { return performanceCounterUpdateIntervalMs; }
    public boolean isStrictErrorHandlingEnabled() { return strictErrorHandling; }

    // IRQ management getters
    public boolean isIRQManagementEnabled() { return enableIRQManagement; }
    public boolean isStrictIRQIsolation() { return strictIRQIsolation; }
    public boolean isRestoreIRQAffinitiesOnShutdown() { return restoreIRQAffinitiesOnShutdown; }
    public long getIRQScanInterval() { return irqScanIntervalMs; }

    // HFT Performance optimization getters
    public boolean isGovernorControlEnabled() { return enableGovernorControl; }
    public boolean isAutoSetPerformanceGovernor() { return autoSetPerformanceGovernor; }
    public boolean isRestoreGovernorsOnShutdown() { return restoreGovernorsOnShutdown; }
    public boolean isHugepageManagementEnabled() { return enableHugepageManagement; }
    public boolean isAutoConfigureHugepages() { return autoConfigureHugepages; }
    public boolean isRestoreHugepageSettingsOnShutdown() { return restoreHugepageSettingsOnShutdown; }
    public boolean isMemoryPrefetchingEnabled() { return enableMemoryPrefetching; }
    public boolean isAutoDetectPrefetchCapabilities() { return autoDetectPrefetchCapabilities; }

    // Rate limiting getters
    public boolean isRateLimitingEnabled() { return enableRateLimiting; }
    public long getMaxOperationsPerSecond() { return maxOperationsPerSecond; }
    public long getMaxBurstOperations() { return maxBurstOperations; }

    // Test mode getters
    public boolean isTestMode() { return testMode; }

    // Validation methods
    public void validateConfiguration() {
        if (maxRetryAttempts < 0 || maxRetryAttempts > 10) {
            throw new IllegalArgumentException("maxRetryAttempts must be between 0 and 10");
        }
        if (operationTimeoutMs < 100 || operationTimeoutMs > 30000) {
            throw new IllegalArgumentException("operationTimeoutMs must be between 100 and 30000");
        }
        if (cacheExpiryMs < 0) {
            throw new IllegalArgumentException("cacheExpiryMs must be non-negative");
        }
        if (performanceCounterUpdateIntervalMs < 10) {
            throw new IllegalArgumentException("performanceCounterUpdateIntervalMs must be at least 10ms");
        }
        if (irqScanIntervalMs < 1000) {
            throw new IllegalArgumentException("irqScanIntervalMs must be at least 1000ms");
        }

        // Rate limiting validation
        if (enableRateLimiting) {
            if (maxOperationsPerSecond <= 0 || maxOperationsPerSecond > 100000) {
                throw new IllegalArgumentException("maxOperationsPerSecond must be between 1 and 100000");
            }
            if (maxBurstOperations <= 0 || maxBurstOperations > 10000) {
                throw new IllegalArgumentException("maxBurstOperations must be between 1 and 10000");
            }
            if (maxBurstOperations > maxOperationsPerSecond) {
                throw new IllegalArgumentException("maxBurstOperations cannot exceed maxOperationsPerSecond");
            }
        }
    }

    @Override
    public String toString() {
        return String.format("AffinityConfig{perfCounters=%s, realtime=%s, numa=%s, maxRetries=%d, timeout=%dms, validation=%s, cache=%s, developer=%s}",
                enablePerformanceCounters, enableRealtimeFeatures, enableNumaOperations,
                maxRetryAttempts, operationTimeoutMs, validateParameters, enableCaching, developerMode);
    }

    public static class Builder {
        private boolean enablePerformanceCounters = DEFAULT_ENABLE_PERFORMANCE_COUNTERS;
        private boolean enableRealtimeFeatures = DEFAULT_ENABLE_REALTIME_FEATURES;
        private boolean enableNumaOperations = DEFAULT_ENABLE_NUMA_OPERATIONS;
        private int maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;
        private long operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS;
        private boolean validateParameters = DEFAULT_VALIDATE_PARAMETERS;
        private boolean enableCaching = DEFAULT_ENABLE_CACHING;
        private long cacheExpiryMs = DEFAULT_CACHE_EXPIRY_MS;
        private boolean developerMode = DEFAULT_DEVELOPER_MODE;
        private String logLevel = "INFO";
        private boolean enableThreadLocalCaching = true;
        private int performanceCounterUpdateIntervalMs = 100;
        private boolean strictErrorHandling = false;

        // IRQ management builder fields
        private boolean enableIRQManagement = DEFAULT_ENABLE_IRQ_MANAGEMENT;
        private boolean strictIRQIsolation = DEFAULT_STRICT_IRQ_ISOLATION;
        private boolean restoreIRQAffinitiesOnShutdown = DEFAULT_RESTORE_IRQ_AFFINITIES_ON_SHUTDOWN;
        private long irqScanIntervalMs = DEFAULT_IRQ_SCAN_INTERVAL_MS;

        // HFT Performance optimization builder fields
        private boolean enableGovernorControl = DEFAULT_ENABLE_GOVERNOR_CONTROL;
        private boolean autoSetPerformanceGovernor = DEFAULT_AUTO_SET_PERFORMANCE_GOVERNOR;
        private boolean restoreGovernorsOnShutdown = DEFAULT_RESTORE_GOVERNORS_ON_SHUTDOWN;
        private boolean enableHugepageManagement = DEFAULT_ENABLE_HUGEPAGE_MANAGEMENT;
        private boolean autoConfigureHugepages = DEFAULT_AUTO_CONFIGURE_HUGEPAGES;
        private boolean restoreHugepageSettingsOnShutdown = DEFAULT_RESTORE_HUGEPAGE_SETTINGS_ON_SHUTDOWN;
        private boolean enableMemoryPrefetching = DEFAULT_ENABLE_MEMORY_PREFETCHING;
        private boolean autoDetectPrefetchCapabilities = DEFAULT_AUTO_DETECT_PREFETCH_CAPABILITIES;

        // Rate limiting builder fields
        private boolean enableRateLimiting = DEFAULT_ENABLE_RATE_LIMITING;
        private long maxOperationsPerSecond = DEFAULT_MAX_OPERATIONS_PER_SECOND;
        private long maxBurstOperations = DEFAULT_MAX_BURST_OPERATIONS;

        // Test mode builder fields
        private boolean testMode = DEFAULT_TEST_MODE;

        public Builder enablePerformanceCounters(boolean enable) {
            this.enablePerformanceCounters = enable;
            return this;
        }

        public Builder enableRealtimeFeatures(boolean enable) {
            this.enableRealtimeFeatures = enable;
            return this;
        }

        public Builder enableNumaOperations(boolean enable) {
            this.enableNumaOperations = enable;
            return this;
        }

        public Builder maxRetryAttempts(int maxRetries) {
            this.maxRetryAttempts = maxRetries;
            return this;
        }

        public Builder operationTimeoutMs(long timeout) {
            this.operationTimeoutMs = timeout;
            return this;
        }

        public Builder validateParameters(boolean validate) {
            this.validateParameters = validate;
            return this;
        }

        public Builder enableCaching(boolean enable) {
            this.enableCaching = enable;
            return this;
        }

        public Builder cacheExpiryMs(long expiry) {
            this.cacheExpiryMs = expiry;
            return this;
        }

        public Builder developerMode(boolean enable) {
            this.developerMode = enable;
            return this;
        }

        public Builder logLevel(String level) {
            this.logLevel = level;
            return this;
        }

        public Builder enableThreadLocalCaching(boolean enable) {
            this.enableThreadLocalCaching = enable;
            return this;
        }

        public Builder performanceCounterUpdateIntervalMs(int interval) {
            this.performanceCounterUpdateIntervalMs = interval;
            return this;
        }

        public Builder strictErrorHandling(boolean strict) {
            this.strictErrorHandling = strict;
            return this;
        }

        // IRQ management builder methods
        public Builder enableIRQManagement(boolean enable) {
            this.enableIRQManagement = enable;
            return this;
        }

        public Builder strictIRQIsolation(boolean strict) {
            this.strictIRQIsolation = strict;
            return this;
        }

        public Builder restoreIRQAffinitiesOnShutdown(boolean restore) {
            this.restoreIRQAffinitiesOnShutdown = restore;
            return this;
        }

        public Builder irqScanIntervalMs(long intervalMs) {
            this.irqScanIntervalMs = intervalMs;
            return this;
        }

        // HFT Performance optimization builder methods
        public Builder enableGovernorControl(boolean enable) {
            this.enableGovernorControl = enable;
            return this;
        }

        public Builder autoSetPerformanceGovernor(boolean autoSet) {
            this.autoSetPerformanceGovernor = autoSet;
            return this;
        }

        public Builder restoreGovernorsOnShutdown(boolean restore) {
            this.restoreGovernorsOnShutdown = restore;
            return this;
        }

        public Builder enableHugepageManagement(boolean enable) {
            this.enableHugepageManagement = enable;
            return this;
        }

        public Builder autoConfigureHugepages(boolean autoConfigure) {
            this.autoConfigureHugepages = autoConfigure;
            return this;
        }

        public Builder restoreHugepageSettingsOnShutdown(boolean restore) {
            this.restoreHugepageSettingsOnShutdown = restore;
            return this;
        }

        public Builder enableMemoryPrefetching(boolean enable) {
            this.enableMemoryPrefetching = enable;
            return this;
        }

        public Builder autoDetectPrefetchCapabilities(boolean autoDetect) {
            this.autoDetectPrefetchCapabilities = autoDetect;
            return this;
        }

        // Rate limiting builder methods
        public Builder enableRateLimiting(boolean enable) {
            this.enableRateLimiting = enable;
            return this;
        }

        public Builder maxOperationsPerSecond(long maxOps) {
            if (maxOps <= 0) {
                throw new IllegalArgumentException("Max operations per second must be positive");
            }
            this.maxOperationsPerSecond = maxOps;
            return this;
        }

        public Builder maxBurstOperations(long maxBurst) {
            if (maxBurst <= 0) {
                throw new IllegalArgumentException("Max burst operations must be positive");
            }
            this.maxBurstOperations = maxBurst;
            return this;
        }

        public Builder testMode(boolean testMode) {
            this.testMode = testMode;
            return this;
        }

        public AffinityConfig build() {
            AffinityConfig config = new AffinityConfig(this);
            config.validateConfiguration();
            return config;
        }
    }
}