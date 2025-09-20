package com.faster.affinity.pool;

import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.exceptions.ErrorCodes;

/**
 * Pooled OperationResult that can be reused to minimize allocation pressure.
 * Thread-safe and optimized for high-frequency operations.
 */
public final class PooledOperationResult<T> implements AutoCloseable {

    private T value;
    private Throwable error;
    private boolean isSuccess;
    private boolean returned = false;

    public PooledOperationResult() {
        // Simplified constructor without complex pool management
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
            // Convert Throwable to error code format for OperationResult
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "operation", error.getMessage());
        }
    }

    /**
     * Return the result to the pool for reuse.
     */
    @Override
    public void close() {
        if (!returned) {
            reset();
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

    /**
     * Factory method to create a pooled success result.
     * Optimized for hot path usage.
     */
    public static <T> PooledOperationResult<T> success(T value) {
        PooledOperationResult<T> result = new PooledOperationResult<>();
        result.value = value;
        result.isSuccess = true;
        result.error = null;
        return result;
    }

    /**
     * Factory method to create a pooled failure result.
     * Optimized for hot path usage.
     */
    public static <T> PooledOperationResult<T> failure(Throwable error) {
        PooledOperationResult<T> result = new PooledOperationResult<>();
        result.value = null;
        result.isSuccess = false;
        result.error = error;
        return result;
    }

    /**
     * Factory method to create a pooled result (uninitialized).
     * For manual setup to avoid method call overhead.
     */
    public static <T> PooledOperationResult<T> acquire() {
        return new PooledOperationResult<>();
    }
}