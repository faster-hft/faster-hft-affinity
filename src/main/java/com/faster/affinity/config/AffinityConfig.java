package com.faster.affinity.config;

import java.util.Properties;
import java.io.InputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration management for the affinity library.
 * Handles runtime configuration, feature toggles, and system-specific settings.
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

    // Configuration instance
    private static volatile AffinityConfig instance;
    private static final Object lock = new Object();

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

        if (developerMode) {
            logger.info("AffinityConfig initialized in developer mode: {}", this);
        }
    }

    public static AffinityConfig getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = loadFromProperties();
                }
            }
        }
        return instance;
    }

    public static void setInstance(AffinityConfig config) {
        synchronized (lock) {
            instance = config;
            logger.info("AffinityConfig instance updated: {}", config);
        }
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
                        .strictErrorHandling(getBooleanProperty(props, "affinity.error.strict", false));

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

        public AffinityConfig build() {
            AffinityConfig config = new AffinityConfig(this);
            config.validateConfiguration();
            return config;
        }
    }
}