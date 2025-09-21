package com.faster.affinity.exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive error code constants and descriptions for the affinity library.
 * Organized hierarchically with severity levels and recovery guidance.
 */
public final class ErrorCodes {

    // Success
    public static final int SUCCESS = 0;

    // General error codes (-1 to -99)
    public static final int ERROR_INVALID_PARAMETER = -1;
    public static final int ERROR_PERMISSION_DENIED = -2;
    public static final int ERROR_NOT_SUPPORTED = -3;
    public static final int ERROR_INSUFFICIENT_MEMORY = -4;
    public static final int ERROR_SYSTEM_CALL_FAILED = -5;
    public static final int ERROR_TIMEOUT = -6;
    public static final int ERROR_RESOURCE_BUSY = -7;
    public static final int ERROR_HARDWARE_NOT_AVAILABLE = -8;
    public static final int ERROR_CONFIGURATION_INVALID = -9;
    public static final int ERROR_OPERATION_FAILED = -10;
    public static final int ERROR_INITIALIZATION_FAILED = -11;
    public static final int ERROR_SHUTDOWN_FAILED = -12;
    public static final int ERROR_LIBRARY_NOT_FOUND = -13;
    public static final int ERROR_LIBRARY_VALIDATION_FAILED = -14;
    public static final int ERROR_FEATURE_DISABLED = -15;

    // Thread and Process errors (-100 to -199)
    public static final int ERROR_THREAD_NOT_FOUND = -100;
    public static final int ERROR_PROCESS_NOT_FOUND = -101;
    public static final int ERROR_THREAD_AFFINITY_GET_FAILED = -102;
    public static final int ERROR_THREAD_AFFINITY_SET_FAILED = -103;
    public static final int ERROR_PROCESS_AFFINITY_GET_FAILED = -104;
    public static final int ERROR_PROCESS_AFFINITY_SET_FAILED = -105;
    public static final int ERROR_THREAD_PRIORITY_FAILED = -106;
    public static final int ERROR_THREAD_SCHEDULING_FAILED = -107;

    // NUMA errors (-200 to -299)
    public static final int ERROR_NUMA_NOT_AVAILABLE = -200;
    public static final int ERROR_NUMA_NODE_NOT_FOUND = -201;
    public static final int ERROR_NUMA_NODE_INVALID = -202;
    public static final int ERROR_NUMA_MEMORY_ALLOCATION_FAILED = -203;
    public static final int ERROR_NUMA_MEMORY_BINDING_FAILED = -204;
    public static final int ERROR_NUMA_POLICY_SET_FAILED = -205;
    public static final int ERROR_NUMA_TOPOLOGY_DETECTION_FAILED = -206;
    public static final int ERROR_NUMA_DISTANCE_QUERY_FAILED = -207;

    // Cache errors (-300 to -399)
    public static final int ERROR_CACHE_EXPIRED = -300;
    public static final int ERROR_CACHE_FULL = -301;
    public static final int ERROR_CACHE_CLOSED = -302;
    public static final int ERROR_CACHE_CORRUPTION = -303;
    public static final int ERROR_CACHE_MISS = -304;
    public static final int ERROR_CACHE_EVICTION_FAILED = -305;
    public static final int ERROR_CACHE_VALIDATION_FAILED = -306;

    // IRQ and Hardware errors (-400 to -499)
    public static final int ERROR_IRQ_NOT_FOUND = -400;
    public static final int ERROR_IRQ_AFFINITY_SET_FAILED = -401;
    public static final int ERROR_IRQ_AFFINITY_GET_FAILED = -402;
    public static final int ERROR_IRQ_ISOLATION_FAILED = -403;
    public static final int ERROR_IRQ_SCAN_FAILED = -404;
    public static final int ERROR_HARDWARE_DETECTION_FAILED = -405;
    public static final int ERROR_CPU_GOVERNOR_SET_FAILED = -406;
    public static final int ERROR_CPU_GOVERNOR_GET_FAILED = -407;
    public static final int ERROR_FREQUENCY_SCALING_FAILED = -408;

    // Performance Monitoring errors (-500 to -599)
    public static final int ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE = -500;
    public static final int ERROR_PERFORMANCE_COUNTER_CREATION_FAILED = -501;
    public static final int ERROR_PERFORMANCE_COUNTER_READ_FAILED = -502;
    public static final int ERROR_PERFORMANCE_COUNTER_RESET_FAILED = -503;
    public static final int ERROR_PERFORMANCE_PROFILER_INIT_FAILED = -504;
    public static final int ERROR_PERFORMANCE_EVENT_OPEN_FAILED = -505;

    // Security and Validation errors (-600 to -699)
    public static final int ERROR_SECURITY_VALIDATION_FAILED = -600;
    public static final int ERROR_RATE_LIMIT_EXCEEDED = -601;
    public static final int ERROR_AUDIT_LOG_FAILED = -602;
    public static final int ERROR_INPUT_SANITIZATION_FAILED = -603;
    public static final int ERROR_PRIVILEGE_ESCALATION_DENIED = -604;
    public static final int ERROR_LIBRARY_SIGNATURE_INVALID = -605;
    public static final int ERROR_PATH_TRAVERSAL_DETECTED = -606;

    // Transaction errors (-700 to -799)
    public static final int ERROR_TRANSACTION_INIT_FAILED = -700;
    public static final int ERROR_TRANSACTION_COMMIT_FAILED = -701;
    public static final int ERROR_TRANSACTION_ROLLBACK_FAILED = -702;
    public static final int ERROR_TRANSACTION_TIMEOUT = -703;
    public static final int ERROR_TRANSACTION_NESTED_NOT_SUPPORTED = -704;
    public static final int ERROR_TRANSACTION_ALREADY_ACTIVE = -705;
    public static final int ERROR_TRANSACTION_NOT_ACTIVE = -706;

    // Platform-specific errors (-800 to -899)
    public static final int ERROR_KERNEL_VERSION_UNSUPPORTED = -800;
    public static final int ERROR_PLATFORM_NOT_SUPPORTED = -801;
    public static final int ERROR_ARCHITECTURE_NOT_SUPPORTED = -802;
    public static final int ERROR_SYSCALL_NOT_AVAILABLE = -803;
    public static final int ERROR_DRIVER_NOT_LOADED = -804;
    public static final int ERROR_KERNEL_MODULE_MISSING = -805;

    // Object Pool errors (-900 to -999)
    public static final int ERROR_POOL_EXHAUSTED = -900;
    public static final int ERROR_POOL_CLOSED = -901;
    public static final int ERROR_POOL_RESOURCE_INVALID = -902;
    public static final int ERROR_POOL_ACQUISITION_TIMEOUT = -903;
    public static final int ERROR_POOL_RELEASE_FAILED = -904;

    // Error severity levels
    public enum ErrorSeverity {
        INFO,       // Informational - operation completed with warnings
        WARNING,    // Warning - operation completed but with issues
        RECOVERABLE, // Error - operation failed but can be retried
        CRITICAL,   // Error - operation failed, manual intervention needed
        FATAL       // Error - system is in inconsistent state
    }

    private static final Map<Integer, String> ERROR_DESCRIPTIONS = new HashMap<>();
    private static final Map<Integer, ErrorSeverity> ERROR_SEVERITIES = new HashMap<>();
    private static final Map<Integer, String> RECOVERY_SUGGESTIONS = new HashMap<>();

    static {
        // Success
        ERROR_DESCRIPTIONS.put(SUCCESS, "Operation completed successfully");
        ERROR_SEVERITIES.put(SUCCESS, ErrorSeverity.INFO);

        // General errors
        ERROR_DESCRIPTIONS.put(ERROR_INVALID_PARAMETER, "Invalid parameter provided");
        ERROR_SEVERITIES.put(ERROR_INVALID_PARAMETER, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_INVALID_PARAMETER, "Validate input parameters and retry");

        ERROR_DESCRIPTIONS.put(ERROR_PERMISSION_DENIED, "Permission denied - may require elevated privileges");
        ERROR_SEVERITIES.put(ERROR_PERMISSION_DENIED, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_PERMISSION_DENIED, "Run with administrator/root privileges or adjust security policies");

        ERROR_DESCRIPTIONS.put(ERROR_NOT_SUPPORTED, "Operation not supported on this platform");
        ERROR_SEVERITIES.put(ERROR_NOT_SUPPORTED, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_NOT_SUPPORTED, "Check platform compatibility or use alternative operation");

        ERROR_DESCRIPTIONS.put(ERROR_INSUFFICIENT_MEMORY, "Insufficient memory available");
        ERROR_SEVERITIES.put(ERROR_INSUFFICIENT_MEMORY, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_INSUFFICIENT_MEMORY, "Free memory or increase system memory allocation");

        ERROR_DESCRIPTIONS.put(ERROR_SYSTEM_CALL_FAILED, "System call failed");
        ERROR_SEVERITIES.put(ERROR_SYSTEM_CALL_FAILED, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_SYSTEM_CALL_FAILED, "Check system logs and retry operation");

        ERROR_DESCRIPTIONS.put(ERROR_TIMEOUT, "Operation timed out");
        ERROR_SEVERITIES.put(ERROR_TIMEOUT, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_TIMEOUT, "Increase timeout value and retry operation");

        ERROR_DESCRIPTIONS.put(ERROR_RESOURCE_BUSY, "Resource is currently busy");
        ERROR_SEVERITIES.put(ERROR_RESOURCE_BUSY, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_RESOURCE_BUSY, "Wait and retry operation, or check for resource conflicts");

        ERROR_DESCRIPTIONS.put(ERROR_HARDWARE_NOT_AVAILABLE, "Required hardware feature not available");
        ERROR_SEVERITIES.put(ERROR_HARDWARE_NOT_AVAILABLE, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_HARDWARE_NOT_AVAILABLE, "Check hardware specifications or use alternative implementation");

        // Thread/Process errors
        ERROR_DESCRIPTIONS.put(ERROR_THREAD_NOT_FOUND, "Specified thread not found");
        ERROR_SEVERITIES.put(ERROR_THREAD_NOT_FOUND, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_THREAD_NOT_FOUND, "Verify thread ID is valid and thread is still active");

        ERROR_DESCRIPTIONS.put(ERROR_PROCESS_NOT_FOUND, "Specified process not found");
        ERROR_SEVERITIES.put(ERROR_PROCESS_NOT_FOUND, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_PROCESS_NOT_FOUND, "Verify process ID is valid and process is still running");

        // NUMA errors
        ERROR_DESCRIPTIONS.put(ERROR_NUMA_NOT_AVAILABLE, "NUMA functionality not available");
        ERROR_SEVERITIES.put(ERROR_NUMA_NOT_AVAILABLE, ErrorSeverity.WARNING);
        RECOVERY_SUGGESTIONS.put(ERROR_NUMA_NOT_AVAILABLE, "Continue with single-node operation or check NUMA support");

        ERROR_DESCRIPTIONS.put(ERROR_NUMA_NODE_NOT_FOUND, "NUMA node not found");
        ERROR_SEVERITIES.put(ERROR_NUMA_NODE_NOT_FOUND, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_NUMA_NODE_NOT_FOUND, "Verify NUMA node ID and check system topology");

        // Cache errors
        ERROR_DESCRIPTIONS.put(ERROR_CACHE_EXPIRED, "Cached data has expired");
        ERROR_SEVERITIES.put(ERROR_CACHE_EXPIRED, ErrorSeverity.WARNING);
        RECOVERY_SUGGESTIONS.put(ERROR_CACHE_EXPIRED, "Refresh cache and retry operation");

        ERROR_DESCRIPTIONS.put(ERROR_CACHE_CLOSED, "Cache has been closed");
        ERROR_SEVERITIES.put(ERROR_CACHE_CLOSED, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_CACHE_CLOSED, "Reinitialize cache or use alternative data source");

        // Performance errors
        ERROR_DESCRIPTIONS.put(ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE, "Performance counters not available");
        ERROR_SEVERITIES.put(ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE, ErrorSeverity.WARNING);
        RECOVERY_SUGGESTIONS.put(ERROR_PERFORMANCE_COUNTERS_UNAVAILABLE, "Continue without performance monitoring or check kernel support");

        // Security errors
        ERROR_DESCRIPTIONS.put(ERROR_RATE_LIMIT_EXCEEDED, "Rate limit exceeded");
        ERROR_SEVERITIES.put(ERROR_RATE_LIMIT_EXCEEDED, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_RATE_LIMIT_EXCEEDED, "Wait and retry, or reduce operation frequency");

        // Platform errors
        ERROR_DESCRIPTIONS.put(ERROR_KERNEL_VERSION_UNSUPPORTED, "Kernel version not supported");
        ERROR_SEVERITIES.put(ERROR_KERNEL_VERSION_UNSUPPORTED, ErrorSeverity.CRITICAL);
        RECOVERY_SUGGESTIONS.put(ERROR_KERNEL_VERSION_UNSUPPORTED, "Update kernel or use compatibility mode");

        // Generic fallback
        ERROR_DESCRIPTIONS.put(ERROR_OPERATION_FAILED, "General operation failure");
        ERROR_SEVERITIES.put(ERROR_OPERATION_FAILED, ErrorSeverity.RECOVERABLE);
        RECOVERY_SUGGESTIONS.put(ERROR_OPERATION_FAILED, "Check system logs and retry operation");
    }

    private ErrorCodes() {
        // Utility class - prevent instantiation
    }

    public static String getErrorDescription(int errorCode) {
        return ERROR_DESCRIPTIONS.getOrDefault(errorCode, "Unknown error code: " + errorCode);
    }

    public static ErrorSeverity getErrorSeverity(int errorCode) {
        return ERROR_SEVERITIES.getOrDefault(errorCode, ErrorSeverity.CRITICAL);
    }

    public static String getRecoverySuggestion(int errorCode) {
        return RECOVERY_SUGGESTIONS.getOrDefault(errorCode, "Check system logs and contact support");
    }

    public static boolean isRecoverable(int errorCode) {
        ErrorSeverity severity = getErrorSeverity(errorCode);
        return severity == ErrorSeverity.RECOVERABLE || severity == ErrorSeverity.WARNING;
    }

    public static boolean isCritical(int errorCode) {
        ErrorSeverity severity = getErrorSeverity(errorCode);
        return severity == ErrorSeverity.CRITICAL || severity == ErrorSeverity.FATAL;
    }

    public static String getErrorCategory(int errorCode) {
        if (errorCode >= -99) return "General";
        if (errorCode >= -199) return "Thread/Process";
        if (errorCode >= -299) return "NUMA";
        if (errorCode >= -399) return "Cache";
        if (errorCode >= -499) return "Hardware/IRQ";
        if (errorCode >= -599) return "Performance";
        if (errorCode >= -699) return "Security";
        if (errorCode >= -799) return "Transaction";
        if (errorCode >= -899) return "Platform";
        if (errorCode >= -999) return "ObjectPool";
        return "Unknown";
    }
}