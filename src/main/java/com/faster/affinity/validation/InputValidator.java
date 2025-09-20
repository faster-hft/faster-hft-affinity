package com.faster.affinity.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.regex.Pattern;

/**
 * Comprehensive input validation for thread affinity operations.
 * Prevents injection attacks and ensures data integrity.
 */
public final class InputValidator {
    private static final Logger logger = LoggerFactory.getLogger(InputValidator.class);

    // Security constraints
    private static final int MAX_THREAD_ID = Integer.MAX_VALUE;
    private static final int MAX_CPU_COUNT = 4096; // Maximum supported CPUs
    private static final int MAX_NUMA_NODE = 255;  // Maximum NUMA nodes
    private static final int MAX_STRING_LENGTH = 1024; // Maximum string length

    // Patterns for validation
    private static final Pattern SAFE_STRING_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.\\s]*$");
    private static final Pattern PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\./\\\\:]*$");

    /**
     * Validate thread ID parameter.
     */
    public static long validateThreadId(long threadId, String operation) {
        if (threadId < 0) {
            throw new IllegalArgumentException("Thread ID cannot be negative in operation: " + operation);
        }
        if (threadId > MAX_THREAD_ID) {
            throw new IllegalArgumentException("Thread ID exceeds maximum value in operation: " + operation);
        }
        return threadId;
    }

    /**
     * Validate process ID parameter.
     */
    public static int validateProcessId(int processId, String operation) {
        if (processId < 0) {
            throw new IllegalArgumentException("Process ID cannot be negative in operation: " + operation);
        }
        if (processId > MAX_THREAD_ID) {
            throw new IllegalArgumentException("Process ID exceeds maximum value in operation: " + operation);
        }
        return processId;
    }

    /**
     * Validate CPU mask BitSet.
     */
    public static BitSet validateCpuMask(BitSet cpuMask, String operation) {
        if (cpuMask == null) {
            throw new IllegalArgumentException("CPU mask cannot be null in operation: " + operation);
        }
        if (cpuMask.isEmpty()) {
            throw new IllegalArgumentException("CPU mask cannot be empty in operation: " + operation);
        }

        // Check for reasonable CPU count
        int highestBit = cpuMask.length();
        if (highestBit > MAX_CPU_COUNT) {
            throw new IllegalArgumentException("CPU mask exceeds maximum CPU count (" + MAX_CPU_COUNT + ") in operation: " + operation);
        }

        // Check for valid CPU indices
        for (int i = cpuMask.nextSetBit(0); i >= 0; i = cpuMask.nextSetBit(i + 1)) {
            if (i >= MAX_CPU_COUNT) {
                throw new IllegalArgumentException("CPU index " + i + " exceeds maximum in operation: " + operation);
            }
        }

        return cpuMask;
    }

    /**
     * Validate CPU index.
     */
    public static int validateCpuIndex(int cpuIndex, String operation) {
        if (cpuIndex < 0) {
            throw new IllegalArgumentException("CPU index cannot be negative in operation: " + operation);
        }
        if (cpuIndex >= MAX_CPU_COUNT) {
            throw new IllegalArgumentException("CPU index exceeds maximum (" + MAX_CPU_COUNT + ") in operation: " + operation);
        }
        return cpuIndex;
    }

    /**
     * Validate NUMA node ID.
     */
    public static int validateNumaNode(int numaNode, String operation) {
        if (numaNode < 0) {
            throw new IllegalArgumentException("NUMA node cannot be negative in operation: " + operation);
        }
        if (numaNode > MAX_NUMA_NODE) {
            throw new IllegalArgumentException("NUMA node exceeds maximum (" + MAX_NUMA_NODE + ") in operation: " + operation);
        }
        return numaNode;
    }

    /**
     * Validate string input for safety.
     */
    public static String validateSafeString(String input, String fieldName, String operation) {
        if (input == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null in operation: " + operation);
        }
        if (input.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length in operation: " + operation);
        }
        if (!SAFE_STRING_PATTERN.matcher(input).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsafe characters in operation: " + operation);
        }
        return input;
    }

    /**
     * Validate file path for safety.
     */
    public static String validatePath(String path, String operation) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null in operation: " + operation);
        }
        if (path.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Path exceeds maximum length in operation: " + operation);
        }

        // Check for path traversal attempts
        if (path.contains("..") || path.contains("~")) {
            throw new IllegalArgumentException("Path contains unsafe traversal patterns in operation: " + operation);
        }

        // Basic pattern validation
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Path contains unsafe characters in operation: " + operation);
        }

        return path;
    }

    /**
     * Validate array parameter.
     */
    public static <T> T[] validateArray(T[] array, String fieldName, String operation) {
        if (array == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null in operation: " + operation);
        }
        if (array.length == 0) {
            throw new IllegalArgumentException(fieldName + " cannot be empty in operation: " + operation);
        }
        if (array.length > MAX_CPU_COUNT) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum size in operation: " + operation);
        }
        return array;
    }

    /**
     * Validate long array parameter.
     */
    public static long[] validateLongArray(long[] array, String fieldName, String operation) {
        if (array == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null in operation: " + operation);
        }
        if (array.length == 0) {
            throw new IllegalArgumentException(fieldName + " cannot be empty in operation: " + operation);
        }
        if (array.length > MAX_CPU_COUNT) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum size in operation: " + operation);
        }

        // Validate each element
        for (int i = 0; i < array.length; i++) {
            if (array[i] < 0) {
                throw new IllegalArgumentException(fieldName + "[" + i + "] cannot be negative in operation: " + operation);
            }
        }

        return array;
    }

    /**
     * Validate memory size parameter.
     */
    public static long validateMemorySize(long size, String operation) {
        if (size < 0) {
            throw new IllegalArgumentException("Memory size cannot be negative in operation: " + operation);
        }
        if (size > Runtime.getRuntime().maxMemory()) {
            throw new IllegalArgumentException("Memory size exceeds JVM maximum in operation: " + operation);
        }
        return size;
    }

    /**
     * Validate timeout parameter.
     */
    public static long validateTimeout(long timeoutMs, String operation) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative in operation: " + operation);
        }
        if (timeoutMs > 300000) { // 5 minutes max
            throw new IllegalArgumentException("Timeout exceeds maximum (5 minutes) in operation: " + operation);
        }
        return timeoutMs;
    }

    /**
     * Validate percentage value.
     */
    public static double validatePercentage(double percentage, String fieldName, String operation) {
        if (percentage < 0.0 || percentage > 100.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100 in operation: " + operation);
        }
        return percentage;
    }

    /**
     * Validate configuration parameter.
     */
    public static <T> T validateNotNull(T value, String fieldName, String operation) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null in operation: " + operation);
        }
        return value;
    }

    /**
     * Validate range of values.
     */
    public static int validateRange(int value, int min, int max, String fieldName, String operation) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max + " in operation: " + operation);
        }
        return value;
    }

    /**
     * Validate long range of values.
     */
    public static long validateRange(long value, long min, long max, String fieldName, String operation) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max + " in operation: " + operation);
        }
        return value;
    }

    /**
     * Sanitize string for logging to prevent log injection.
     */
    public static String sanitizeForLogging(String input) {
        if (input == null) {
            return "null";
        }

        // Remove or escape characters that could cause log injection
        return input.replaceAll("[\r\n\t]", "_")
                   .replaceAll("[<>\"']", "")
                   .substring(0, Math.min(input.length(), 200)); // Limit length
    }

    /**
     * Validate syscall number for Linux platform.
     */
    public static int validateSyscallNumber(int syscallNum, String operation) {
        // Whitelist of allowed syscalls for affinity operations
        switch (syscallNum) {
            case 203: // sched_setaffinity
            case 204: // sched_getaffinity
            case 186: // gettid
                return syscallNum;
            default:
                throw new IllegalArgumentException("Syscall number " + syscallNum + " not allowed in operation: " + operation);
        }
    }

    /**
     * Log validation error securely.
     */
    private static void logValidationError(String operation, String error) {
        logger.warn("Validation failed for operation '{}': {}",
                   sanitizeForLogging(operation),
                   sanitizeForLogging(error));
    }
}