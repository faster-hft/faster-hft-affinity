package com.faster.affinity.exceptions;

/**
 * Exception for hardware-related errors
 */
public class HardwareException extends AffinityException {
    public HardwareException(String operation, String feature) {
        super(ErrorCodes.ERROR_HARDWARE_NOT_AVAILABLE, operation,
                String.format("Required hardware feature not available: %s", feature));
        addContext("feature", feature);
    }
}