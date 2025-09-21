package com.faster.affinity.factory;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory and facade for the complete affinity library.
 * Provides a single entry point for all CPU affinity, NUMA, topology, and performance operations.
 */
public final class AffinityLibraryFactory {
    private static final Logger logger = LoggerFactory.getLogger(AffinityLibraryFactory.class);

    private static final AtomicReference<AffinityLibrary> defaultInstance = new AtomicReference<>();
    private static final AtomicBoolean factoryInitialized = new AtomicBoolean(false);

    private AffinityLibraryFactory() {
        // Prevent instantiation
    }

    // Thread-safe singleton holder pattern for critical initialization
    private static class DefaultInstanceHolder {
        private static final AffinityLibrary INSTANCE = createInstanceSafely();

        private static AffinityLibrary createInstanceSafely() {
            try {
                return new AffinityLibraryImpl(AffinityConfig.getInstance());
            } catch (Exception e) {
                logger.error("Failed to create default affinity library: {}", e.getMessage(), e);
                throw new ExceptionInInitializerError("Failed to create default affinity library: " + e.getMessage());
            }
        }
    }

    /**
     * Creates a new affinity library instance with default configuration.
     */
    public static AffinityLibrary create() {
        return create(AffinityConfig.getInstance());
    }

    /**
     * Creates a new affinity library instance with custom configuration.
     */
    public static AffinityLibrary create(AffinityConfig config) {
        try {
            // If in test mode and ThreadLocal shutdown was initiated, reset it for test isolation
            if (config.isTestMode()) {
                com.faster.affinity.utils.ThreadLocalManager.resetForTesting();
            }
            return new AffinityLibraryImpl(config);
        } catch (Exception e) {
            logger.error("Failed to create affinity library: {}", e.getMessage(), e);
            throw new ConfigurationException("create", "Failed to create affinity library: " + e.getMessage());
        }
    }

    /**
     * Gets the default singleton instance using thread-safe initialization-on-demand holder pattern.
     * This eliminates all race conditions and ensures exactly one instance is created.
     */
    public static AffinityLibrary getDefault() {
        // Check if a custom instance has been set
        AffinityLibrary customInstance = defaultInstance.get();
        if (customInstance != null) {
            return customInstance;
        }

        // Use thread-safe singleton holder pattern - guaranteed single instance, no race conditions
        return DefaultInstanceHolder.INSTANCE;
    }

    /**
     * Sets a custom default instance.
     */
    public static void setDefault(AffinityLibrary instance) {
        // Lock-free atomic swap
        AffinityLibrary old = defaultInstance.getAndSet(instance);
        if (old != null && old != instance) {
            try {
                old.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down old default instance: {}", e.getMessage());
            }
        }
        logger.info("Set custom default affinity library instance");
    }

    /**
     * Validates that the platform supports the affinity library.
     */
    public static SystemValidationResult validatePlatform() {
        return SystemValidationResult.validate();
    }

    /**
     * Shuts down the default instance if it exists.
     */
    public static void shutdownDefault() {
        // Lock-free atomic swap
        AffinityLibrary instance = defaultInstance.getAndSet(null);
        if (instance != null) {
            try {
                instance.shutdown();
                logger.info("Shut down default affinity library instance");
            } catch (Exception e) {
                logger.error("Error shutting down default instance: {}", e.getMessage(), e);
            }
        }
    }
}