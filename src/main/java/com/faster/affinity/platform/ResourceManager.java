package com.faster.affinity.platform;

import com.sun.jna.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resource manager for native memory and handle cleanup.
 * Prevents resource leaks by tracking and managing native resources.
 */
public final class ResourceManager {
    private static final Logger logger = LoggerFactory.getLogger(ResourceManager.class);

    // Track allocated memory for cleanup
    private static final ConcurrentHashMap<Long, AllocatedMemory> allocatedMemory = new ConcurrentHashMap<>();
    private static final AtomicLong allocationCounter = new AtomicLong(0);

    /**
     * Managed memory allocation with automatic cleanup tracking.
     */
    public static class ManagedMemory implements AutoCloseable {
        private final Memory memory;
        private final long id;
        private volatile boolean closed = false;

        private ManagedMemory(long size) {
            this.memory = new Memory(size);
            this.id = allocationCounter.incrementAndGet();

            AllocatedMemory info = new AllocatedMemory(memory, size, Thread.currentThread().getId());
            allocatedMemory.put(id, info);

            logger.debug("Allocated managed memory: id={}, size={}, thread={}", id, size, Thread.currentThread().getId());
        }

        public Memory getMemory() {
            if (closed) {
                throw new IllegalStateException("Memory has been closed");
            }
            return memory;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                AllocatedMemory info = allocatedMemory.remove(id);
                if (info != null) {
                    logger.debug("Freed managed memory: id={}, size={}", id, info.size);
                }
                // Memory is automatically garbage collected by JNA
            }
        }

        public boolean isClosed() {
            return closed;
        }

        public long getId() {
            return id;
        }
    }

    /**
     * Information about allocated memory.
     */
    private static class AllocatedMemory {
        final Memory memory;
        final long size;
        final long threadId;
        final long allocationTime;

        AllocatedMemory(Memory memory, long size, long threadId) {
            this.memory = memory;
            this.size = size;
            this.threadId = threadId;
            this.allocationTime = System.currentTimeMillis();
        }
    }

    /**
     * Allocate managed memory that will be automatically tracked.
     */
    public static ManagedMemory allocateMemory(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Memory size must be positive");
        }
        if (size > 1024 * 1024 * 1024) { // 1GB limit
            throw new IllegalArgumentException("Memory size too large: " + size);
        }

        return new ManagedMemory(size);
    }

    /**
     * Execute an operation with managed memory.
     */
    public static <T> T withManagedMemory(long size, MemoryOperation<T> operation) throws Exception {
        try (ManagedMemory managedMemory = allocateMemory(size)) {
            return operation.execute(managedMemory.getMemory());
        }
    }

    /**
     * Execute an operation with multiple managed memory blocks.
     */
    public static <T> T withManagedMemory(long[] sizes, MultiMemoryOperation<T> operation) throws Exception {
        ManagedMemory[] memories = new ManagedMemory[sizes.length];
        Memory[] rawMemories = new Memory[sizes.length];

        try {
            for (int i = 0; i < sizes.length; i++) {
                memories[i] = allocateMemory(sizes[i]);
                rawMemories[i] = memories[i].getMemory();
            }

            return operation.execute(rawMemories);

        } finally {
            for (ManagedMemory memory : memories) {
                if (memory != null) {
                    memory.close();
                }
            }
        }
    }

    /**
     * Safe resource cleanup utility.
     */
    public static void safeClose(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    logger.warn("Failed to close resource: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Safe resource cleanup for Closeable.
     */
    public static void safeClose(Closeable... resources) {
        for (Closeable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    logger.warn("Failed to close resource: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Get statistics about allocated memory.
     */
    public static MemoryStats getMemoryStats() {
        long totalSize = 0;
        int count = 0;
        long oldestAllocation = Long.MAX_VALUE;

        for (AllocatedMemory info : allocatedMemory.values()) {
            totalSize += info.size;
            count++;
            oldestAllocation = Math.min(oldestAllocation, info.allocationTime);
        }

        return new MemoryStats(count, totalSize, oldestAllocation == Long.MAX_VALUE ? 0 : oldestAllocation);
    }

    /**
     * Force cleanup of leaked memory (for emergency use).
     */
    public static void forceCleanup() {
        int cleaned = allocatedMemory.size();
        allocatedMemory.clear();
        logger.warn("Force cleaned {} leaked memory allocations", cleaned);
    }

    /**
     * Check for memory leaks and log warnings.
     */
    public static void checkForLeaks() {
        MemoryStats stats = getMemoryStats();
        if (stats.count > 0) {
            logger.warn("Potential memory leak detected: {} allocations, {} bytes total",
                       stats.count, stats.totalSize);

            // Log details of long-lived allocations
            long now = System.currentTimeMillis();
            for (AllocatedMemory info : allocatedMemory.values()) {
                long age = now - info.allocationTime;
                if (age > 60000) { // Older than 1 minute
                    logger.warn("Long-lived allocation: thread={}, size={}, age={}ms",
                               info.threadId, info.size, age);
                }
            }
        }
    }

    /**
     * Memory operation interface.
     */
    @FunctionalInterface
    public interface MemoryOperation<T> {
        T execute(Memory memory) throws Exception;
    }

    /**
     * Multi-memory operation interface.
     */
    @FunctionalInterface
    public interface MultiMemoryOperation<T> {
        T execute(Memory[] memories) throws Exception;
    }

    /**
     * Memory statistics.
     */
    public static class MemoryStats {
        public final int count;
        public final long totalSize;
        public final long oldestAllocationTime;

        MemoryStats(int count, long totalSize, long oldestAllocationTime) {
            this.count = count;
            this.totalSize = totalSize;
            this.oldestAllocationTime = oldestAllocationTime;
        }

        @Override
        public String toString() {
            return String.format("MemoryStats{count=%d, totalSize=%d, oldestAge=%dms}",
                               count, totalSize,
                               oldestAllocationTime > 0 ? System.currentTimeMillis() - oldestAllocationTime : 0);
        }
    }
}