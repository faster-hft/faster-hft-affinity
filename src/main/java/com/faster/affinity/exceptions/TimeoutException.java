package com.faster.affinity.exceptions;

/**
 * Exception for timeout scenarios
 */
public class TimeoutException extends AffinityException {
    public TimeoutException(String operation, long timeoutMs) {
        super(ErrorCodes.ERROR_TIMEOUT, operation,
                String.format("Operation timed out after %dms", timeoutMs));
        addContext("timeout_ms", timeoutMs);
    }
}