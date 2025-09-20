package com.faster.affinity.exceptions;

/**
 * Exception for general operation failures
 */
public class OperationFailedException extends AffinityException {
    public OperationFailedException(String operation, String message) {
        super(ErrorCodes.ERROR_OPERATION_FAILED, operation, message);
    }

    public OperationFailedException(String operation, String message, Throwable cause) {
        super(ErrorCodes.ERROR_OPERATION_FAILED, operation, message, cause);
    }
}