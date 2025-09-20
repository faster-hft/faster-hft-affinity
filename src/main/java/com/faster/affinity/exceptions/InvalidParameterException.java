package com.faster.affinity.exceptions;

/**
 * Exception for invalid parameters
 */
public class InvalidParameterException extends AffinityException {
    public InvalidParameterException(String operation, String paramName, Object value) {
        super(ErrorCodes.ERROR_INVALID_PARAMETER, operation,
                String.format("Invalid parameter '%s': %s", paramName, value));
        addContext("parameter", paramName).addContext("value", value);
    }

    public InvalidParameterException(String operation, String message) {
        super(ErrorCodes.ERROR_INVALID_PARAMETER, operation, message);
    }
}