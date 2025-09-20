package com.faster.affinity.pool;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Thread-local object pool for zero-contention, high-performance pooling.
 * Each thread maintains its own pool, eliminating synchronization overhead.
 */
public class ThreadLocalObjectPool<T> implements ObjectPool<T> {

    private final Supplier<T> objectFactory;
    private final int maxPoolSize;
    private final ThreadLocal<LocalPool<T>> localPool;

    // Global statistics (thread-safe)
    private final AtomicInteger totalAcquisitions = new AtomicInteger();
    private final AtomicInteger totalReleases = new AtomicInteger();
    private final AtomicInteger totalCreations = new AtomicInteger();

    public ThreadLocalObjectPool(Supplier<T> objectFactory, int maxPoolSize) {
        this.objectFactory = objectFactory;
        this.maxPoolSize = maxPoolSize;
        this.localPool = ThreadLocal.withInitial(() -> new LocalPool<>(maxPoolSize));
    }

    @Override
    public T acquire() {
        totalAcquisitions.incrementAndGet();
        LocalPool<T> pool = localPool.get();
        T obj = pool.acquire();
        if (obj == null) {
            // Pool miss - create new object
            obj = objectFactory.get();
            totalCreations.incrementAndGet();
        }
        return obj;
    }

    @Override
    public void release(T obj) {
        if (obj != null) {
            totalReleases.incrementAndGet();
            LocalPool<T> pool = localPool.get();
            pool.release(obj);
        }
    }

    @Override
    public PoolStats getStats() {
        int acquisitions = totalAcquisitions.get();
        int releases = totalReleases.get();
        int creations = totalCreations.get();

        // Calculate aggregate stats from all thread-local pools
        int totalPoolSize = 0;
        int totalInUse = Math.max(0, acquisitions - releases);

        // Hit rate: (acquisitions - creations) / acquisitions
        double hitRate = acquisitions > 0 ? (double)(acquisitions - creations) / acquisitions : 0.0;

        return new PoolStats(acquisitions, releases, totalPoolSize, maxPoolSize, totalInUse, hitRate);
    }

    @Override
    public void clear() {
        localPool.remove();
    }

    @Override
    public boolean isHealthy() {
        return objectFactory != null && maxPoolSize > 0;
    }

    /**
     * Thread-local pool implementation using ArrayDeque for optimal performance.
     */
    private static class LocalPool<T> {
        private final ArrayDeque<T> objects;
        private final int maxSize;

        LocalPool(int maxSize) {
            this.maxSize = maxSize;
            this.objects = new ArrayDeque<>(Math.min(maxSize, 16));
        }

        T acquire() {
            return objects.pollFirst();
        }

        void release(T obj) {
            if (objects.size() < maxSize) {
                objects.offerFirst(obj);
            }
            // If pool is full, discard the object (let GC handle it)
        }
    }
}