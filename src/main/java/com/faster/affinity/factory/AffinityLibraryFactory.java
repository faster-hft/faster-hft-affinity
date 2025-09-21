package com.faster.affinity.factory;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory class for creating and managing instances of the Faster Thread Affinity Library.
 * Provides a centralized entry point for library creation with support for both default
 * and custom configurations optimized for high-frequency trading (HFT) applications.
 *
 * <p>This factory supports multiple instantiation patterns:
 * <ul>
 *   <li><strong>Default Instance</strong> - Thread-safe singleton for simple use cases</li>
 *   <li><strong>Custom Configuration</strong> - Tailored instances for specific HFT requirements</li>
 *   <li><strong>Test Mode Support</strong> - Special configurations for unit and integration testing</li>
 * </ul>
 *
 * <h3>Usage Examples</h3>
 *
 * <h4>Quick Start (Default Configuration)</h4>
 * <pre>{@code
 * // Get default instance with reasonable defaults
 * AffinityLibrary library = AffinityLibraryFactory.getDefault();
 *
 * // Basic thread affinity
 * BitSet cpuMask = new BitSet();
 * cpuMask.set(0, 2); // CPUs 0-1
 * library.setCurrentThreadAffinity(cpuMask);
 * }</pre>
 *
 * <h4>HFT Production Configuration</h4>
 * <pre>{@code
 * // Create high-performance configuration for trading
 * AffinityConfig config = new AffinityConfig.Builder()
 *     .enableCaching(true)                    // Enable operation caching
 *     .enableThreadLocalCaching(true)         // Thread-local optimization
 *     .enableNumaOperations(true)             // NUMA awareness
 *     .enablePerformanceCounters(true)        // Real-time monitoring
 *     .enableGovernorControl(true)            // CPU frequency control
 *     .enableIRQManagement(true)              // Interrupt isolation
 *     .operationTimeoutMs(1000)               // Fast timeouts
 *     .maxRetryAttempts(1)                    // Minimal retries for speed
 *     .developerMode(false)                   // Production settings
 *     .build();
 *
 * AffinityLibrary library = AffinityLibraryFactory.create(config);
 * }</pre>
 *
 * <h4>Test Configuration</h4>
 * <pre>{@code
 * // Configuration for unit tests with relaxed constraints
 * AffinityConfig testConfig = new AffinityConfig.Builder()
 *     .testMode(true)                         // Disable rate limiting
 *     .enableCaching(false)                   // Disable caching for deterministic tests
 *     .developerMode(true)                    // Enable debugging features
 *     .build();
 *
 * AffinityLibrary library = AffinityLibraryFactory.create(testConfig);
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> This factory is fully thread-safe. Multiple threads can
 * safely call factory methods concurrently. The default instance uses the initialization-on-demand
 * holder pattern to eliminate race conditions.
 *
 * <p><strong>Resource Management:</strong> The factory automatically manages library lifecycles,
 * including proper cleanup of replaced instances and shutdown hook registration.
 *
 * <p><strong>Platform Validation:</strong> Use {@link #validatePlatform()} to verify system
 * compatibility before creating library instances in production environments.
 *
 * @author Amar Mond
 * @since 1.0.0
 * @version 1.0.0
 * @see AffinityLibrary
 * @see com.faster.affinity.config.AffinityConfig
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
     * Creates a new affinity library instance using the default configuration.
     *
     * <p>The default configuration provides reasonable settings for most applications
     * with caching enabled, moderate timeouts, and standard retry behavior. This is
     * suitable for development and non-critical production workloads.
     *
     * <p>For HFT applications requiring maximum performance, use {@link #create(AffinityConfig)}
     * with a custom configuration optimized for your specific requirements.
     *
     * @return A new AffinityLibrary instance with default configuration
     * @throws ConfigurationException if library creation fails due to system constraints
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    public static AffinityLibrary create() {
        return create(AffinityConfig.getInstance());
    }

    /**
     * Creates a new affinity library instance with the specified custom configuration.
     *
     * <p>This is the recommended method for HFT applications requiring specific performance
     * characteristics. The custom configuration allows fine-tuning of caching behavior,
     * timeout values, NUMA settings, and other performance-critical parameters.
     *
     * <p><strong>HFT Example:</strong>
     * <pre>{@code
     * AffinityConfig hftConfig = new AffinityConfig.Builder()
     *     .enableCaching(true)
     *     .enableThreadLocalCaching(true)
     *     .enableNumaOperations(true)
     *     .operationTimeoutMs(500)        // Fast timeouts for HFT
     *     .maxRetryAttempts(1)            // Minimal retries
     *     .developerMode(false)           // Production mode
     *     .build();
     *
     * AffinityLibrary library = AffinityLibraryFactory.create(hftConfig);
     * }</pre>
     *
     * @param config The configuration object specifying library behavior and performance settings
     * @return A new AffinityLibrary instance configured according to the provided settings
     * @throws ConfigurationException if the configuration is invalid or library creation fails
     * @throws IllegalArgumentException if config is null
     *
     * @since 1.0.0
     * @author Amar Mond
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
     *
     * <p>This method provides a convenient way to access a shared library instance without
     * the overhead of creating multiple instances. The singleton uses the initialization-on-demand
     * holder pattern, which eliminates race conditions and ensures exactly one instance is created.
     *
     * <p><strong>Use Cases:</strong>
     * <ul>
     *   <li>Simple applications not requiring custom configuration</li>
     *   <li>Shared components needing consistent library access</li>
     *   <li>Development and testing scenarios</li>
     * </ul>
     *
     * <p><strong>HFT Note:</strong> For production HFT applications, consider using
     * {@link #create(AffinityConfig)} with custom configuration for optimal performance.
     *
     * @return The default AffinityLibrary singleton instance
     * @throws ExceptionInInitializerError if default instance creation fails
     *
     * @since 1.0.0
     * @author Amar Mond
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
     * Validates that the current platform supports the affinity library and returns
     * detailed capability information.
     *
     * <p>This method should be called before creating library instances in production
     * environments to ensure all required features are available. It checks for:
     * <ul>
     *   <li>Operating system compatibility (Linux/Windows)</li>
     *   <li>Required native libraries and system calls</li>
     *   <li>Hardware feature availability (NUMA, performance counters)</li>
     *   <li>Privilege requirements for advanced features</li>
     * </ul>
     *
     * <p><strong>Production Example:</strong>
     * <pre>{@code
     * SystemValidationResult validation = AffinityLibraryFactory.validatePlatform();
     * if (!validation.isSupported()) {
     *     logger.error("Platform not supported: {}", validation.getErrorMessage());
     *     // Fallback to alternative implementation
     * } else {
     *     AffinityLibrary library = AffinityLibraryFactory.create(config);
     * }
     * }</pre>
     *
     * @return SystemValidationResult containing platform support information and capabilities
     *
     * @since 1.0.0
     * @author Amar Mond
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