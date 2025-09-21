package com.faster.affinity.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ThreadLocal memory leak prevention and management utility.
 * Provides automatic cleanup of ThreadLocal variables to prevent memory leaks
 * in long-running applications and thread pools.
 */
public final class ThreadLocalManager {
    private static final Logger logger = LoggerFactory.getLogger(ThreadLocalManager.class);

    // Track all managed ThreadLocal instances for cleanup using strong references
    private static final ConcurrentHashMap<String, ManagedThreadLocal<?>> managedThreadLocals
        = new ConcurrentHashMap<>();

    // Thread-safe cleanup coordination
    private static final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private static final Object cleanupLock = new Object();

    // Statistics for monitoring
    private static final AtomicLong totalCreated = new AtomicLong(0);
    private static final AtomicLong totalCleaned = new AtomicLong(0);

    // Shutdown hook for automatic cleanup
    private static final Thread shutdownHook = new Thread(ThreadLocalManager::cleanupAll, "ThreadLocalCleanup");

    static {
        // Register shutdown hook for automatic cleanup
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            logger.debug("ThreadLocal cleanup shutdown hook registered");
        } catch (Exception e) {
            logger.warn("Failed to register ThreadLocal cleanup shutdown hook: {}", e.getMessage());
        }
    }

    /**
     * Managed ThreadLocal that automatically tracks itself for cleanup.
     */
    public static class ManagedThreadLocal<T> extends ThreadLocal<T> {
        private final String name;
        private final ThreadLocalSupplier<T> supplier;
        private final CleanupCallback<T> cleanupCallback;
        private final AtomicLong accessCount = new AtomicLong(0);
        private volatile boolean cleaned = false;

        public ManagedThreadLocal(String name, ThreadLocalSupplier<T> supplier) {
            this(name, supplier, null);
        }

        public ManagedThreadLocal(String name, ThreadLocalSupplier<T> supplier, CleanupCallback<T> cleanupCallback) {
            if (name == null || supplier == null) {
                throw new IllegalArgumentException("Name and supplier cannot be null");
            }
            if (shutdownInitiated.get()) {
                throw new IllegalStateException("Cannot create ThreadLocal after shutdown initiated");
            }

            this.name = name;
            this.supplier = supplier;
            this.cleanupCallback = cleanupCallback;

            synchronized (cleanupLock) {
                // Register for cleanup management using strong reference
                ManagedThreadLocal<?> existing = managedThreadLocals.put(name, this);
                if (existing != null) {
                    logger.warn("Replacing existing ThreadLocal with name: {}", name);
                    // Clean up the existing one
                    try {
                        existing.cleanup();
                    } catch (Exception e) {
                        logger.debug("Error cleaning up replaced ThreadLocal: {}", e.getMessage());
                    }
                }
                totalCreated.incrementAndGet();
                logger.debug("Created managed ThreadLocal: {} (total: {})", name, totalCreated.get());
            }
        }

        @Override
        protected T initialValue() {
            if (cleaned) {
                throw new IllegalStateException("ThreadLocal has been cleaned: " + name);
            }
            accessCount.incrementAndGet();
            return supplier.get();
        }

        @Override
        public T get() {
            if (cleaned) {
                throw new IllegalStateException("ThreadLocal has been cleaned: " + name);
            }
            accessCount.incrementAndGet();
            return super.get();
        }

        @Override
        public void set(T value) {
            if (cleaned) {
                throw new IllegalStateException("ThreadLocal has been cleaned: " + name);
            }
            super.set(value);
        }

        @Override
        public void remove() {
            super.remove();
            logger.trace("ThreadLocal value removed for thread {}: {}",
                       Thread.currentThread().getId(), name);
        }

        /**
         * Mark this ThreadLocal as cleaned and remove all values.
         * Thread-safe and idempotent.
         */
        public void cleanup() {
            synchronized (cleanupLock) {
                if (!cleaned) {
                    cleaned = true;
                    try {
                        // Call cleanup callback if provided
                        if (cleanupCallback != null) {
                            T value = super.get();
                            if (value != null) {
                                try {
                                    cleanupCallback.cleanup(value);
                                } catch (Exception e) {
                                    logger.warn("Error in cleanup callback for {}: {}", name, e.getMessage());
                                }
                            }
                        }

                        this.remove();
                        // Remove from global tracking
                        managedThreadLocals.remove(name, this);
                        totalCleaned.incrementAndGet();
                        logger.debug("Cleaned ThreadLocal: {} (accessed {} times, total cleaned: {})",
                                   name, accessCount.get(), totalCleaned.get());
                    } catch (Exception e) {
                        logger.warn("Error during ThreadLocal cleanup: {}", e.getMessage());
                    }
                }
            }
        }

        /**
         * Get the name of this ThreadLocal for debugging.
         */
        public String getName() {
            return name;
        }

        /**
         * Get access count for monitoring.
         */
        public long getAccessCount() {
            return accessCount.get();
        }

        /**
         * Check if this ThreadLocal has been cleaned.
         */
        public boolean isCleaned() {
            return cleaned;
        }

    }

    /**
     * Functional interface for ThreadLocal suppliers.
     */
    @FunctionalInterface
    public interface ThreadLocalSupplier<T> {
        T get();
    }

    /**
     * Functional interface for cleanup callbacks.
     */
    @FunctionalInterface
    public interface CleanupCallback<T> {
        void cleanup(T value);
    }

    /**
     * Create a managed ThreadLocal with automatic cleanup tracking.
     */
    public static <T> ManagedThreadLocal<T> create(String name, ThreadLocalSupplier<T> supplier) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("ThreadLocal name cannot be null or empty");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("ThreadLocal supplier cannot be null");
        }

        return new ManagedThreadLocal<>(name, supplier);
    }

    /**
     * Create a managed ThreadLocal with automatic cleanup tracking and cleanup callback.
     */
    public static <T> ManagedThreadLocal<T> create(String name, ThreadLocalSupplier<T> supplier, CleanupCallback<T> cleanupCallback) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("ThreadLocal name cannot be null or empty");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("ThreadLocal supplier cannot be null");
        }

        return new ManagedThreadLocal<>(name, supplier, cleanupCallback);
    }

    /**
     * Clean up a specific ThreadLocal by name.
     */
    public static boolean cleanup(String name) {
        if (name == null) {
            return false;
        }

        synchronized (cleanupLock) {
            ManagedThreadLocal<?> threadLocal = managedThreadLocals.get(name);
            if (threadLocal != null) {
                threadLocal.cleanup();
                return true;
            }
            return false;
        }
    }

    /**
     * Clean up all managed ThreadLocal instances.
     * This is called automatically during shutdown.
     */
    public static void cleanupAll() {
        if (shutdownInitiated.compareAndSet(false, true)) {
            logger.info("Starting ThreadLocal cleanup for {} instances", managedThreadLocals.size());

            synchronized (cleanupLock) {
                int cleanedCount = 0;
                for (ManagedThreadLocal<?> threadLocal : managedThreadLocals.values()) {
                    try {
                        if (!threadLocal.isCleaned()) {
                            threadLocal.cleanup();
                            cleanedCount++;
                        }
                    } catch (Exception e) {
                        logger.warn("Error cleaning up ThreadLocal {}: {}", threadLocal.getName(), e.getMessage());
                    }
                }

                managedThreadLocals.clear();
                logger.info("ThreadLocal cleanup completed. Cleaned {} instances, total created: {}, total cleaned: {}",
                           cleanedCount, totalCreated.get(), totalCleaned.get());
            }
        }
    }

    /**
     * Force cleanup of all ThreadLocal instances (for testing/emergency use).
     */
    public static void forceCleanupAll() {
        logger.warn("Force cleanup of all ThreadLocal instances requested");
        shutdownInitiated.set(false); // Reset flag to allow cleanupAll to run
        cleanupAll();
    }

    /**
     * Get statistics about managed ThreadLocal instances.
     */
    public static ThreadLocalStats getStats() {
        synchronized (cleanupLock) {
            int activeCount = managedThreadLocals.size();
            long totalCreatedCount = totalCreated.get();
            long totalCleanedCount = totalCleaned.get();

            return new ThreadLocalStats(activeCount, totalCreatedCount, totalCleanedCount, shutdownInitiated.get());
        }
    }

    /**
     * Check if a ThreadLocal with the given name exists and is active.
     */
    public static boolean exists(String name) {
        if (name == null) {
            return false;
        }
        ManagedThreadLocal<?> threadLocal = managedThreadLocals.get(name);
        return threadLocal != null && !threadLocal.isCleaned();
    }

    /**
     * Statistics for ThreadLocal management.
     */
    public static class ThreadLocalStats {
        private final int activeCount;
        private final long totalCreated;
        private final long totalCleaned;
        private final boolean shutdownInitiated;

        public ThreadLocalStats(int activeCount, long totalCreated, long totalCleaned, boolean shutdownInitiated) {
            this.activeCount = activeCount;
            this.totalCreated = totalCreated;
            this.totalCleaned = totalCleaned;
            this.shutdownInitiated = shutdownInitiated;
        }

        public int getActiveCount() { return activeCount; }
        public long getTotalCreated() { return totalCreated; }
        public long getTotalCleaned() { return totalCleaned; }
        public boolean isShutdownInitiated() { return shutdownInitiated; }

        @Override
        public String toString() {
            return String.format("ThreadLocalStats{active=%d, created=%d, cleaned=%d, shutdown=%s}",
                                activeCount, totalCreated, totalCleaned, shutdownInitiated);
        }
    }

    // Prevent instantiation
    private ThreadLocalManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Clean up ThreadLocal instances for the current thread.
     * Should be called when threads are returned to thread pools.
     */
    public static void cleanupCurrentThread() {
        synchronized (cleanupLock) {
            int cleaned = 0;
            for (ManagedThreadLocal<?> threadLocal : managedThreadLocals.values()) {
                if (threadLocal != null && !threadLocal.isCleaned()) {
                    threadLocal.remove(); // Remove value for current thread only
                    cleaned++;
                }
            }

            if (cleaned > 0) {
                logger.debug("Cleaned {} ThreadLocal values for thread {}",
                           cleaned, Thread.currentThread().getId());
            }
        }
    }

    /**
     * Detect potential memory leaks in ThreadLocal usage.
     */
    public static void checkForLeaks() {
        ThreadLocalStats stats = getStats();

        if (stats.getActiveCount() > 100) {
            logger.warn("High number of active ThreadLocal instances detected: {} " +
                       "(potential memory leak)", stats.getActiveCount());
        }

        // Check for ThreadLocal instances with very high access counts (potential hotspots)
        synchronized (cleanupLock) {
            for (ManagedThreadLocal<?> threadLocal : managedThreadLocals.values()) {
                if (threadLocal != null && threadLocal.getAccessCount() > 100000) {
                    logger.warn("ThreadLocal with very high access count: {} ({})",
                               threadLocal.getName(), threadLocal.getAccessCount());
                }
            }
        }

        logger.debug("ThreadLocal leak check complete: {}", stats);
    }
}