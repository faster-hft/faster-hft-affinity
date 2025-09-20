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
            return new AffinityLibraryImpl(config);
        } catch (Exception e) {
            logger.error("Failed to create affinity library: {}", e.getMessage(), e);
            throw new ConfigurationException("create", "Failed to create affinity library: " + e.getMessage());
        }
    }

    /**
     * Gets the default singleton instance, creating it if necessary.
     */
    public static AffinityLibrary getDefault() {
        AffinityLibrary instance = defaultInstance.get();
        if (instance == null) {
            // Lock-free initialization using AtomicReference compareAndSet
            AffinityLibrary newInstance = create();
            if (defaultInstance.compareAndSet(null, newInstance)) {
                logger.info("Created default affinity library instance");
                return newInstance;
            } else {
                // Another thread won the race, use their instance
                try {
                    newInstance.shutdown();
                } catch (Exception e) {
                    logger.debug("Error cleaning up unused instance: {}", e.getMessage());
                }
                return defaultInstance.get();
            }
        }
        return instance;
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