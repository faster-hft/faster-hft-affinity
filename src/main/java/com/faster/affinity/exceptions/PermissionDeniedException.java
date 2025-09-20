package com.faster.affinity.exceptions;

/**
 * Exception for permission-related errors
 */
public class PermissionDeniedException extends AffinityException {
    public PermissionDeniedException(String operation, String resource) {
        super(ErrorCodes.ERROR_PERMISSION_DENIED, operation,
                String.format("Permission denied accessing resource: %s", resource));
        addContext("resource", resource);
    }

    public PermissionDeniedException(String operation, String message, Throwable cause) {
        super(ErrorCodes.ERROR_PERMISSION_DENIED, operation, message, cause);
    }
}