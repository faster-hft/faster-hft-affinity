package com.faster.affinity.exceptions;

/**
 * Exception for unsupported operations
 */
public class UnsupportedOperationException extends AffinityException {
    public UnsupportedOperationException(String operation, String reason) {
        super(ErrorCodes.ERROR_NOT_SUPPORTED, operation,
                String.format("Operation not supported: %s", reason));
        addContext("reason", reason);
    }

    public UnsupportedOperationException(String operation) {
        super(ErrorCodes.ERROR_NOT_SUPPORTED, operation, "Operation not supported on this platform");
        addContext("platform", System.getProperty("os.name"));
    }
}