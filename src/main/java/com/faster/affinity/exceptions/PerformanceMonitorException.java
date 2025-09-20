package com.faster.affinity.exceptions;

/**
 * Exception for performance monitoring errors
 */
public class PerformanceMonitorException extends AffinityException {
    public PerformanceMonitorException(String operation, String counter) {
        super(ErrorCodes.ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE, operation,
                String.format("Performance counter not available: %s", counter));
        addContext("counter", counter);
    }
}