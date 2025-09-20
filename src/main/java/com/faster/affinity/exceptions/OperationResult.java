package com.faster.affinity.exceptions;

/**
 * Result wrapper for operations that can fail
 */
public class OperationResult<T> {
    private final boolean success;
    private final T value;
    private final AffinityException error;
    private final int errorCode;

    private OperationResult(boolean success, T value, AffinityException error, int errorCode) {
        this.success = success;
        this.value = value;
        this.error = error;
        this.errorCode = errorCode;
    }

    public static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(true, value, null, ErrorCodes.SUCCESS);
    }

    public static <T> OperationResult<T> failure(AffinityException error) {
        return new OperationResult<>(false, null, error, error.getErrorCode());
    }

    public static <T> OperationResult<T> failure(int errorCode, String operation, String message) {
        return failure(new GenericAffinityException(errorCode, operation, message));
    }

    public boolean isSuccess() { return success; }
    public T getValue() { return value; }
    public AffinityException getError() { return error; }
    public int getErrorCode() { return errorCode; }

    public T getOrThrow() throws AffinityException {
        if (!success) {
            throw error;
        }
        return value;
    }

    public T getOrDefault(T defaultValue) {
        return success ? value : defaultValue;
    }

    /**
     * Generic affinity exception for cases not covered by specific types
     */
    private static class GenericAffinityException extends AffinityException {
        public GenericAffinityException(int errorCode, String operation, String message) {
            super(errorCode, operation, message);
        }
    }
}