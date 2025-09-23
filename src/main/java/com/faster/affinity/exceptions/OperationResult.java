package com.faster.affinity.exceptions;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Enhanced result wrapper for operations that can fail.
 *
 * <p>Designed for zero-allocation hot paths with pre-allocated singleton instances
 * and functional composition capabilities for HFT environments. This class provides
 * a type-safe way to handle success and failure cases without throwing exceptions
 * in performance-critical code paths.</p>
 *
 * <h2>Design Principles:</h2>
 * <ul>
 *   <li><b>Zero Allocation:</b> Pre-allocated singleton instances for common failures</li>
 *   <li><b>Type Safety:</b> Compile-time guarantees for success/failure handling</li>
 *   <li><b>Performance:</b> No exception throwing in hot paths</li>
 *   <li><b>Composability:</b> Functional programming patterns for result chaining</li>
 * </ul>
 *
 * <h2>Usage Examples:</h2>
 * <pre>{@code
 * // Basic usage
 * OperationResult<BitSet> result = affinity.getCurrentThreadAffinity();
 * if (result.isSuccess()) {
 *     BitSet cpuMask = result.getData();
 *     System.out.println("CPU mask: " + cpuMask);
 * } else {
 *     System.err.println("Error: " + result.getError().getMessage());
 * }
 *
 * // Functional composition
 * OperationResult<String> description = result
 *     .map(BitSet::toString)
 *     .mapError(error -> "Failed to get affinity: " + error.getMessage());
 *
 * // Chaining operations
 * OperationResult<Void> chainResult = result
 *     .flatMap(cpuMask -> affinity.setThreadAffinity(threadId, cpuMask))
 *     .orElse(() -> OperationResult.success(null));
 * }</pre>
 *
 * <h2>Pre-allocated Common Failures:</h2>
 * <p>For performance-critical operations, common failure cases use pre-allocated
 * singleton instances to avoid object allocation:</p>
 * <ul>
 *   <li>Cache expired/closed</li>
 *   <li>Invalid parameters</li>
 *   <li>System call failures</li>
 *   <li>Permission denied</li>
 * </ul>
 *
 * @param <T> the type of the successful result value
 * @author Amar Mond
 * @version 1.1.0
 * @since 1.0.0
 * @see AffinityException
 * @see ErrorCodes
 */
public final class OperationResult<T> {
    private final boolean success;
    private final T value;
    private final AffinityException error;
    private final int errorCode;
    private final String errorContext;

    // Pre-allocated singleton instances for common error cases (zero allocation)
    private static final OperationResult<Void> CACHE_CLOSED_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_CACHE_EXPIRED, "cache", "Cache has been closed"), ErrorCodes.ERROR_CACHE_EXPIRED, "cache-closed");

    private static final OperationResult<Void> INVALID_PARAMETER_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_INVALID_PARAMETER, "validation", "Invalid parameter"), ErrorCodes.ERROR_INVALID_PARAMETER, "invalid-param");

    private static final OperationResult<Void> SYSTEM_CALL_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_SYSTEM_CALL_FAILED, "syscall", "System call failed"), ErrorCodes.ERROR_SYSTEM_CALL_FAILED, "syscall-failed");

    private static final OperationResult<Void> PERMISSION_DENIED_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_PERMISSION_DENIED, "security", "Permission denied"), ErrorCodes.ERROR_PERMISSION_DENIED, "permission-denied");

    private static final OperationResult<Void> NOT_SUPPORTED_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_NOT_SUPPORTED, "platform", "Operation not supported"), ErrorCodes.ERROR_NOT_SUPPORTED, "not-supported");

    private static final OperationResult<Void> TIMEOUT_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_TIMEOUT, "timeout", "Operation timed out"), ErrorCodes.ERROR_TIMEOUT, "timeout");

    private static final OperationResult<Void> RESOURCE_BUSY_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_RESOURCE_BUSY, "resource", "Resource is busy"), ErrorCodes.ERROR_RESOURCE_BUSY, "resource-busy");

    private static final OperationResult<Void> INSUFFICIENT_MEMORY_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_INSUFFICIENT_MEMORY, "memory", "Insufficient memory"), ErrorCodes.ERROR_INSUFFICIENT_MEMORY, "insufficient-memory");

    private static final OperationResult<Void> HARDWARE_NOT_AVAILABLE_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_HARDWARE_NOT_AVAILABLE, "hardware", "Hardware not available"), ErrorCodes.ERROR_HARDWARE_NOT_AVAILABLE, "hardware-unavailable");

    private static final OperationResult<Void> NUMA_NOT_AVAILABLE_FAILURE =
        new OperationResult<>(false, null, new GenericAffinityException(ErrorCodes.ERROR_NUMA_NOT_AVAILABLE, "numa", "NUMA not available"), ErrorCodes.ERROR_NUMA_NOT_AVAILABLE, "numa-unavailable");

    private OperationResult(boolean success, T value, AffinityException error, int errorCode) {
        this.success = success;
        this.value = value;
        this.error = error;
        this.errorCode = errorCode;
        this.errorContext = null;
    }

    private OperationResult(boolean success, T value, AffinityException error, int errorCode, String errorContext) {
        this.success = success;
        this.value = value;
        this.error = error;
        this.errorCode = errorCode;
        this.errorContext = errorContext;
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

    // Zero-allocation factory methods for common hot path failures
    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> cacheClosedFailure() {
        return (OperationResult<T>) CACHE_CLOSED_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> invalidParameterFailure() {
        return (OperationResult<T>) INVALID_PARAMETER_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> systemCallFailure() {
        return (OperationResult<T>) SYSTEM_CALL_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> permissionDeniedFailure() {
        return (OperationResult<T>) PERMISSION_DENIED_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> notSupportedFailure() {
        return (OperationResult<T>) NOT_SUPPORTED_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> timeoutFailure() {
        return (OperationResult<T>) TIMEOUT_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> resourceBusyFailure() {
        return (OperationResult<T>) RESOURCE_BUSY_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> insufficientMemoryFailure() {
        return (OperationResult<T>) INSUFFICIENT_MEMORY_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> hardwareNotAvailableFailure() {
        return (OperationResult<T>) HARDWARE_NOT_AVAILABLE_FAILURE;
    }

    @SuppressWarnings("unchecked")
    public static <T> OperationResult<T> numaNotAvailableFailure() {
        return (OperationResult<T>) NUMA_NOT_AVAILABLE_FAILURE;
    }

    public boolean isSuccess() { return success; }
    public boolean isFailure() { return !success; }
    public T getValue() { return value; }
    public AffinityException getError() { return error; }
    public int getErrorCode() { return errorCode; }
    public String getErrorContext() { return errorContext; }

    public T getOrThrow() throws AffinityException {
        if (!success) {
            throw error;
        }
        return value;
    }

    public T getOrDefault(T defaultValue) {
        return success ? value : defaultValue;
    }

    public T getOrElse(Supplier<T> defaultSupplier) {
        return success ? value : defaultSupplier.get();
    }

    // Functional composition methods for chaining operations
    public <U> OperationResult<U> map(Function<T, U> mapper) {
        if (!success) {
            return OperationResult.failure(error);
        }
        try {
            return OperationResult.success(mapper.apply(value));
        } catch (Exception e) {
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "map", "Mapping function failed: " + e.getMessage());
        }
    }

    public <U> OperationResult<U> flatMap(Function<T, OperationResult<U>> mapper) {
        if (!success) {
            return OperationResult.failure(error);
        }
        try {
            return mapper.apply(value);
        } catch (Exception e) {
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "flatMap", "FlatMap function failed: " + e.getMessage());
        }
    }

    public OperationResult<T> recover(Function<AffinityException, T> recoveryFunction) {
        if (success) {
            return this;
        }
        try {
            return OperationResult.success(recoveryFunction.apply(error));
        } catch (Exception e) {
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "recover", "Recovery function failed: " + e.getMessage());
        }
    }

    public OperationResult<T> recoverWith(Function<AffinityException, OperationResult<T>> recoveryFunction) {
        if (success) {
            return this;
        }
        try {
            return recoveryFunction.apply(error);
        } catch (Exception e) {
            return OperationResult.failure(ErrorCodes.ERROR_OPERATION_FAILED, "recoverWith", "Recovery function failed: " + e.getMessage());
        }
    }

    // Utility methods for error analysis
    public boolean isErrorType(int expectedErrorCode) {
        return !success && errorCode == expectedErrorCode;
    }

    public boolean isRecoverableError() {
        return !success && (
            errorCode == ErrorCodes.ERROR_TIMEOUT ||
            errorCode == ErrorCodes.ERROR_RESOURCE_BUSY ||
            errorCode == ErrorCodes.ERROR_CACHE_EXPIRED
        );
    }

    public boolean isCriticalError() {
        return !success && (
            errorCode == ErrorCodes.ERROR_PERMISSION_DENIED ||
            errorCode == ErrorCodes.ERROR_INSUFFICIENT_MEMORY ||
            errorCode == ErrorCodes.ERROR_HARDWARE_NOT_AVAILABLE
        );
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