package com.faster.affinity.performance;

import com.sun.jna.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Linux performance event counter wrapper.
 * Manages a file descriptor for reading hardware/software performance counters.
 */
public class PerfEventCounter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PerfEventCounter.class);

    // Modern cleaner for resource management (Java 9+)
    private static final Cleaner cleaner = Cleaner.create();

    // Track all active counters for shutdown hook
    private static final Set<CleanupAction> activeCounters = ConcurrentHashMap.newKeySet();

    // Native interface for system calls
    private interface NativeLib extends Library {
        NativeLib INSTANCE = Native.load("c", NativeLib.class);

        long read(int fd, Pointer buffer, long count);
        int close(int fd);
        int ioctl(int fd, int request, Object... args);
    }

    // ioctl commands for perf events
    private static final int PERF_EVENT_IOC_ENABLE = 0x2400;
    private static final int PERF_EVENT_IOC_DISABLE = 0x2401;
    private static final int PERF_EVENT_IOC_RESET = 0x2403;

    private final int fd;
    private final String description;
    private final AtomicLong lastValue = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final long creationTime;

    // Cleaner registration for automatic cleanup
    private final Cleaner.Cleanable cleanable;

    /**
     * Creates a new performance counter from a file descriptor.
     *
     * @param fd File descriptor from perf_event_open
     * @param description Human-readable description for logging
     */
    public PerfEventCounter(int fd, String description) {
        if (fd < 0) {
            throw new IllegalArgumentException("Invalid file descriptor: " + fd);
        }
        this.fd = fd;
        this.description = description;
        this.creationTime = System.currentTimeMillis();

        // Register cleaner for automatic resource cleanup
        CleanupAction cleanupAction = new CleanupAction(fd, description, closed);
        this.cleanable = cleaner.register(this, cleanupAction);
        activeCounters.add(cleanupAction);

        // Register shutdown hook on first instance creation
        registerShutdownHookIfNeeded();

        // Enable the counter immediately
        enable();
    }

    public PerfEventCounter(int fd) {
        this(fd, "perf_counter_fd_" + fd);
    }

    /**
     * Reads the current counter value.
     *
     * @return Current counter value, or last known value if read fails
     */
    public long read() {
        if (closed.get() || fd < 0) {
            return lastValue.get();
        }

        try {
            Memory buffer = new Memory(8);
            long bytesRead = NativeLib.INSTANCE.read(fd, buffer, 8);

            if (bytesRead == 8) {
                long value = buffer.getLong(0);
                lastValue.set(value);
                return value;
            }

            logger.debug("Incomplete read from {}: {} bytes", description, bytesRead);
            return lastValue.get();

        } catch (Exception e) {
            logger.debug("Failed to read {}: {}", description, e.getMessage());
            return lastValue.get();
        }
    }

    /**
     * Enables the performance counter.
     */
    public void enable() {
        if (!closed.get() && fd >= 0) {
            try {
                NativeLib.INSTANCE.ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
            } catch (Exception e) {
                logger.debug("Failed to enable {}: {}", description, e.getMessage());
            }
        }
    }

    /**
     * Disables the performance counter.
     */
    public void disable() {
        if (!closed.get() && fd >= 0) {
            try {
                NativeLib.INSTANCE.ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
            } catch (Exception e) {
                logger.debug("Failed to disable {}: {}", description, e.getMessage());
            }
        }
    }

    /**
     * Resets the counter to zero.
     */
    public void reset() {
        if (!closed.get() && fd >= 0) {
            try {
                NativeLib.INSTANCE.ioctl(fd, PERF_EVENT_IOC_RESET, 0);
                lastValue.set(0);
            } catch (Exception e) {
                logger.debug("Failed to reset {}: {}", description, e.getMessage());
            }
        }
    }

    /**
     * Gets the last read value without performing a new read.
     */
    public long getLastValue() {
        return lastValue.get();
    }

    /**
     * Gets the age of this counter in milliseconds.
     */
    public long getAge() {
        return System.currentTimeMillis() - creationTime;
    }

    /**
     * Gets the file descriptor (for advanced use).
     */
    public int getFileDescriptor() {
        return fd;
    }

    /**
     * Checks if this counter has been closed.
     */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Clean the cleaner registration
            cleanable.clean();

            if (fd >= 0) {
                try {
                    disable(); // Disable before closing
                    NativeLib.INSTANCE.close(fd);
                    logger.debug("Closed {}", description);
                } catch (Exception e) {
                    logger.warn("Failed to close {}: {}", description, e.getMessage());
                }
            }
        }
    }

    /**
     * Cleanup action for the Cleaner API.
     * This runs when the PerfEventCounter is garbage collected.
     */
    private static class CleanupAction implements Runnable {
        private final int fd;
        private final String description;
        private final AtomicBoolean closed;

        CleanupAction(int fd, String description, AtomicBoolean closed) {
            this.fd = fd;
            this.description = description;
            this.closed = closed;
        }

        @Override
        public void run() {
            if (closed.compareAndSet(false, true) && fd >= 0) {
                try {
                    // Disable before closing
                    NativeLib.INSTANCE.ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
                    NativeLib.INSTANCE.close(fd);
                    logger.debug("Cleaner closed {}", description);
                } catch (Exception e) {
                    logger.warn("Cleaner failed to close {}: {}", description, e.getMessage());
                }
            }
            // Remove from active counters
            activeCounters.remove(this);
        }
    }

    /**
     * Registers a shutdown hook to ensure all file descriptors are closed on JVM shutdown.
     * This provides an additional safety net beyond the cleaner.
     */
    private static volatile boolean shutdownHookRegistered = false;

    private static void registerShutdownHookIfNeeded() {
        if (!shutdownHookRegistered) {
            synchronized (PerfEventCounter.class) {
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        logger.debug("Shutdown hook cleaning up {} remaining PerfEventCounters", activeCounters.size());
                        for (CleanupAction action : activeCounters) {
                            action.run();
                        }
                        activeCounters.clear();
                    }, "PerfEventCounter-Cleanup"));
                    shutdownHookRegistered = true;
                    logger.debug("Registered PerfEventCounter shutdown hook");
                }
            }
        }
    }

    @Override
    public String toString() {
        return String.format("PerfEventCounter{fd=%d, description='%s', lastValue=%d, closed=%s}",
                fd, description, lastValue.get(), closed.get());
    }
}