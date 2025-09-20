package com.faster.affinity.pool;

import com.faster.affinity.exceptions.OperationResult;

/**
 * Pooled OperationResult that can be reused to minimize allocation pressure.
 * Thread-safe and optimized for high-frequency operations.
 */
public final class PooledOperationResult<T> implements AutoCloseable {

    private T value;
    private Throwable error;
    private boolean isSuccess;
    private final ObjectPool<PooledOperationResult<T>> pool;
    private boolean returned = false;

    @SuppressWarnings("unchecked")
    public PooledOperationResult() {
        this.pool = (ObjectPool<PooledOperationResult<T>>) getStaticPool();
    }

    /**
     * Reset the result for reuse.
     */
    public void reset() {
        this.value = null;
        this.error = null;
        this.isSuccess = false;
        this.returned = false;
    }

    /**
     * Set as successful result.
     */
    public PooledOperationResult<T> setSuccess(T value) {
        this.value = value;
        this.error = null;
        this.isSuccess = true;
        return this;
    }

    /**
     * Set as failure result.
     */
    public PooledOperationResult<T> setFailure(Throwable error) {
        this.value = null;
        this.error = error;
        this.isSuccess = false;
        return this;
    }

    /**
     * Check if the operation was successful.
     */
    public boolean isSuccess() {
        return isSuccess;
    }

    /**
     * Get the result value (only valid if isSuccess() returns true).
     */
    public T getValue() {
        return value;
    }

    /**
     * Get the error (only valid if isSuccess() returns false).
     */
    public Throwable getError() {
        return error;
    }

    /**
     * Convert to a regular OperationResult.
     * This creates a new OperationResult instance, so use sparingly.
     */
    public OperationResult<T> toOperationResult() {
        if (isSuccess) {
            return OperationResult.success(value);
        } else {
            return OperationResult.failure(error);
        }
    }

    /**
     * Return the result to the pool for reuse.
     */
    @Override
    public void close() {
        if (!returned && pool != null) {
            reset();
            pool.release(this);
            returned = true;
        }
    }

    @Override
    public String toString() {
        if (returned) {
            return "[returned]";
        }
        if (isSuccess) {
            return "Success[" + value + "]";
        } else {
            return "Failure[" + error + "]";
        }
    }

    // Static pool for PooledOperationResult instances
    private static volatile ObjectPool<PooledOperationResult<?>> staticPool;

    private static ObjectPool<PooledOperationResult<?>> getStaticPool() {
        if (staticPool == null) {
            synchronized (PooledOperationResult.class) {
                if (staticPool == null) {
                    staticPool = new ThreadLocalObjectPool<>(
                            PooledOperationResult::new,
                            32 // Pool size
                    );
                    ObjectPoolManager.registerPool("pooled-operation-result", staticPool);
                }
            }
        }
        return staticPool;
    }

    /**
     * Factory method to acquire a pooled success result.
     */
    @SuppressWarnings("unchecked")
    public static <T> PooledOperationResult<T> success(T value) {
        ObjectPool<PooledOperationResult<T>> pool =
            (ObjectPool<PooledOperationResult<T>>) getStaticPool();
        PooledOperationResult<T> result = pool.acquire();
        if (result == null) {
            result = new PooledOperationResult<>();
        }
        return result.setSuccess(value);
    }

    /**
     * Factory method to acquire a pooled failure result.
     */
    @SuppressWarnings("unchecked")
    public static <T> PooledOperationResult<T> failure(Throwable error) {
        ObjectPool<PooledOperationResult<T>> pool =
            (ObjectPool<PooledOperationResult<T>>) getStaticPool();
        PooledOperationResult<T> result = pool.acquire();
        if (result == null) {
            result = new PooledOperationResult<>();
        }
        return result.setFailure(error);
    }

    /**
     * Factory method to acquire a pooled result (uninitialized).
     */
    @SuppressWarnings("unchecked")
    public static <T> PooledOperationResult<T> acquire() {
        ObjectPool<PooledOperationResult<T>> pool =
            (ObjectPool<PooledOperationResult<T>>) getStaticPool();
        PooledOperationResult<T> result = pool.acquire();
        if (result == null) {
            result = new PooledOperationResult<>();
        } else {
            result.reset();
        }
        return result;
    }
}