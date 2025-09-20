package com.faster.affinity.performance;

import com.sun.jna.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linux performance event counter wrapper.
 * Manages a file descriptor for reading hardware/software performance counters.
 */
public class PerfEventCounter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PerfEventCounter.class);

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
        if (closed.compareAndSet(false, true) && fd >= 0) {
            try {
                disable(); // Disable before closing
                NativeLib.INSTANCE.close(fd);
                logger.debug("Closed {}", description);
            } catch (Exception e) {
                logger.warn("Failed to close {}: {}", description, e.getMessage());
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (!closed.get()) {
            logger.warn("PerfEventCounter {} not properly closed, cleaning up in finalizer", description);
            close();
        }
        super.finalize();
    }

    @Override
    public String toString() {
        return String.format("PerfEventCounter{fd=%d, description='%s', lastValue=%d, closed=%s}",
                fd, description, lastValue.get(), closed.get());
    }
}