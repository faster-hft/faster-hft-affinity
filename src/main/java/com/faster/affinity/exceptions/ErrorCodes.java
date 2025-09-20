package com.faster.affinity.exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Error code constants and descriptions for the affinity library
 */
public final class ErrorCodes {

    // Error code constants
    public static final int SUCCESS = 0;
    public static final int ERROR_INVALID_PARAMETER = -1;
    public static final int ERROR_PERMISSION_DENIED = -2;
    public static final int ERROR_NOT_SUPPORTED = -3;
    public static final int ERROR_INSUFFICIENT_MEMORY = -4;
    public static final int ERROR_SYSTEM_CALL_FAILED = -5;
    public static final int ERROR_TIMEOUT = -6;
    public static final int ERROR_RESOURCE_BUSY = -7;
    public static final int ERROR_HARDWARE_NOT_AVAILABLE = -8;
    public static final int ERROR_CONFIGURATION_INVALID = -9;
    public static final int ERROR_NUMA_NOT_AVAILABLE = -10;
    public static final int ERROR_CACHE_EXPIRED = -11;
    public static final int ERROR_THREAD_NOT_FOUND = -12;
    public static final int ERROR_PROCESS_NOT_FOUND = -13;
    public static final int ERROR_KERNEL_VERSION_UNSUPPORTED = -14;
    public static final int ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE = -15;
    public static final int ERROR_OPERATION_FAILED = -16;

    private static final Map<Integer, String> ERROR_DESCRIPTIONS = new HashMap<>();

    static {
        ERROR_DESCRIPTIONS.put(SUCCESS, "Operation completed successfully");
        ERROR_DESCRIPTIONS.put(ERROR_INVALID_PARAMETER, "Invalid parameter provided");
        ERROR_DESCRIPTIONS.put(ERROR_PERMISSION_DENIED, "Permission denied - may require root/administrator privileges");
        ERROR_DESCRIPTIONS.put(ERROR_NOT_SUPPORTED, "Operation not supported on this platform");
        ERROR_DESCRIPTIONS.put(ERROR_INSUFFICIENT_MEMORY, "Insufficient memory available");
        ERROR_DESCRIPTIONS.put(ERROR_SYSTEM_CALL_FAILED, "System call failed");
        ERROR_DESCRIPTIONS.put(ERROR_TIMEOUT, "Operation timed out");
        ERROR_DESCRIPTIONS.put(ERROR_RESOURCE_BUSY, "Resource is currently busy");
        ERROR_DESCRIPTIONS.put(ERROR_HARDWARE_NOT_AVAILABLE, "Required hardware feature not available");
        ERROR_DESCRIPTIONS.put(ERROR_CONFIGURATION_INVALID, "System configuration is invalid");
        ERROR_DESCRIPTIONS.put(ERROR_NUMA_NOT_AVAILABLE, "NUMA functionality not available");
        ERROR_DESCRIPTIONS.put(ERROR_CACHE_EXPIRED, "Cached data has expired");
        ERROR_DESCRIPTIONS.put(ERROR_THREAD_NOT_FOUND, "Specified thread not found");
        ERROR_DESCRIPTIONS.put(ERROR_PROCESS_NOT_FOUND, "Specified process not found");
        ERROR_DESCRIPTIONS.put(ERROR_KERNEL_VERSION_UNSUPPORTED, "Kernel version not supported");
        ERROR_DESCRIPTIONS.put(ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE, "Performance counters not available");
        ERROR_DESCRIPTIONS.put(ERROR_OPERATION_FAILED, "General operation failure");
    }

    private ErrorCodes() {
        // Utility class - prevent instantiation
    }

    public static String getErrorDescription(int errorCode) {
        return ERROR_DESCRIPTIONS.getOrDefault(errorCode, "Unknown error code: " + errorCode);
    }
}