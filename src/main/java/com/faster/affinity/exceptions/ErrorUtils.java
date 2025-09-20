package com.faster.affinity.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for error handling
 */
public class ErrorUtils {
    private static final Logger logger = LoggerFactory.getLogger(ErrorUtils.class);

    private ErrorUtils() {
        // Utility class - prevent instantiation
    }

    public static void logError(AffinityException error) {
        logger.error("Affinity operation failed: {}", error.toString());
    }

    public static void logError(String operation, Throwable error) {
        logger.error("Affinity operation '{}' failed: {}", operation, error.getMessage(), error);
    }

    public static boolean isRetryableError(int errorCode) {
        return errorCode == ErrorCodes.ERROR_RESOURCE_BUSY ||
                errorCode == ErrorCodes.ERROR_TIMEOUT ||
                errorCode == ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
    }

    public static boolean isPermissionError(int errorCode) {
        return errorCode == ErrorCodes.ERROR_PERMISSION_DENIED;
    }

    public static boolean isFatalError(int errorCode) {
        return errorCode == ErrorCodes.ERROR_NOT_SUPPORTED ||
                errorCode == ErrorCodes.ERROR_HARDWARE_NOT_AVAILABLE ||
                errorCode == ErrorCodes.ERROR_KERNEL_VERSION_UNSUPPORTED;
    }
}