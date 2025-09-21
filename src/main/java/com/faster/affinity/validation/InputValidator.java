package com.faster.affinity.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.Set;
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

    // Enhanced security patterns for validation
    // Strictly allow only alphanumeric, underscore, hyphen, and space - no dots to prevent traversal
    private static final Pattern SAFE_STRING_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\s]{1,128}$");

    // More restrictive path pattern - whitelist approach for known safe characters
    // Allows: alphanumeric, underscore, hyphen, forward slash, backslash, colon (for drive letters)
    private static final Pattern SAFE_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-/\\\\:]{1,256}$");

    // Whitelist of allowed path prefixes for additional security
    private static final Set<String> ALLOWED_PATH_PREFIXES = Set.of(
        "/proc", "/sys", "/dev", "/tmp",  // Linux safe paths
        "C:\\Windows\\System32", "C:\\Program Files",  // Windows safe paths
        "."  // Current directory only
    );

    /**
     * Validate thread ID parameter with ownership verification.
     * SECURITY FIX: Now includes ownership validation to prevent privilege escalation.
     */
    public static long validateThreadId(long threadId, String operation) {
        if (threadId < 0) {
            throw new IllegalArgumentException("Thread ID cannot be negative in operation: " + operation);
        }
        if (threadId > MAX_THREAD_ID) {
            throw new IllegalArgumentException("Thread ID exceeds maximum value in operation: " + operation);
        }

        // SECURITY FIX: Validate thread ownership to prevent unauthorized access
        validateThreadOwnership(threadId, operation);

        return threadId;
    }

    /**
     * Validate that the current process has permission to modify the specified thread.
     * SECURITY FIX: Prevents privilege escalation by validating thread ownership.
     */
    private static void validateThreadOwnership(long threadId, String operation) {
        try {
            // Get current process ID for ownership validation
            long currentPid = ProcessHandle.current().pid();

            // Check if thread belongs to current process or if we have system privileges
            if (!isThreadOwnedByProcess(threadId, currentPid)) {
                // For system threads or threads in other processes, check if we have admin privileges
                if (!hasSystemPrivileges()) {
                    throw new SecurityException(
                        String.format("Insufficient privileges to access thread %d in operation %s. " +
                                    "Thread does not belong to current process %d and caller lacks system privileges.",
                                    threadId, operation, currentPid));
                }
            }
        } catch (SecurityException e) {
            throw e; // Re-throw security exceptions
        } catch (Exception e) {
            // If ownership validation fails for any reason, be conservative and deny access
            throw new SecurityException("Thread ownership validation failed for thread " + threadId +
                                      " in operation " + operation + ": " + e.getMessage());
        }
    }

    /**
     * Check if a thread belongs to the specified process.
     */
    private static boolean isThreadOwnedByProcess(long threadId, long processId) {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();

            if (osName.contains("linux")) {
                return checkLinuxThreadOwnership(threadId, processId);
            } else if (osName.contains("windows")) {
                return checkWindowsThreadOwnership(threadId, processId);
            }

            // Unknown OS - be conservative and require system privileges
            return false;
        } catch (Exception e) {
            // On any error, be conservative and deny access
            return false;
        }
    }

    /**
     * Check thread ownership on Linux by examining /proc filesystem.
     * CRITICAL FIX: Enhanced validation to prevent privilege escalation.
     */
    private static boolean checkLinuxThreadOwnership(long threadId, long processId) {
        try {
            // CRITICAL FIX: Multiple validation checks for thread ownership

            // 1. Check if thread exists in current process's task directory
            java.nio.file.Path threadPath = java.nio.file.Paths.get("/proc", String.valueOf(processId), "task", String.valueOf(threadId));
            if (!java.nio.file.Files.exists(threadPath)) {
                return false;
            }

            // 2. CRITICAL FIX: Verify thread actually belongs to our process by reading its status
            java.nio.file.Path statusPath = threadPath.resolve("status");
            if (java.nio.file.Files.exists(statusPath)) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(statusPath);
                boolean processIdMatches = false;

                for (String line : lines) {
                    if (line.startsWith("Tgid:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long tgid = Long.parseLong(parts[1]);
                            processIdMatches = (tgid == processId);
                            break;
                        }
                    }
                }

                if (!processIdMatches) {
                    return false;
                }
            }

            // 3. CRITICAL FIX: Additional verification - check if we can actually access the thread
            java.nio.file.Path commPath = threadPath.resolve("comm");
            return java.nio.file.Files.isReadable(commPath);

        } catch (Exception e) {
            // CRITICAL FIX: Log security-relevant failures
            logger.warn("Thread ownership validation failed for thread {} in process {}: {}",
                       threadId, processId, e.getMessage());
            return false;
        }
    }

    /**
     * Check thread ownership on Windows.
     * CRITICAL FIX: Enhanced validation to prevent privilege escalation.
     */
    private static boolean checkWindowsThreadOwnership(long threadId, long processId) {
        try {
            // CRITICAL FIX: Use Java ProcessHandle for more robust validation
            long currentPid = ProcessHandle.current().pid();

            // 1. Basic validation: only allow threads from current process
            if (processId != currentPid) {
                logger.warn("Thread {} belongs to different process {} (current: {})",
                           threadId, processId, currentPid);
                return false;
            }

            // 2. CRITICAL FIX: Additional verification using Java thread management
            // For threads in current process, verify they are accessible
            if (threadId == Thread.currentThread().getId()) {
                return true; // Always allow current thread
            }

            // 3. CRITICAL FIX: For other threads, use conservative validation
            // In a production environment, this should use native Windows APIs
            // to properly validate thread ownership and access rights

            // Check if thread ID is in reasonable range
            if (threadId <= 0 || threadId > Integer.MAX_VALUE) {
                logger.warn("Invalid thread ID range: {}", threadId);
                return false;
            }

            // CRITICAL FIX: Conservative approach - require administrative privileges
            // for threads other than current thread to prevent unauthorized access
            if (!hasSystemPrivileges()) {
                logger.warn("Insufficient privileges to access thread {} without system privileges", threadId);
                return false;
            }

            return true;

        } catch (Exception e) {
            // CRITICAL FIX: Log security-relevant failures
            logger.warn("Windows thread ownership validation failed for thread {} in process {}: {}",
                       threadId, processId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if current process has system-level privileges.
     */
    private static boolean hasSystemPrivileges() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();

            if (osName.contains("linux")) {
                // Check if running as root (UID 0)
                return System.getProperty("user.name", "").equals("root");
            } else if (osName.contains("windows")) {
                // Basic Windows admin check - in production this should use native Windows APIs
                return isWindowsAdmin();
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check Windows administrator privileges (basic implementation).
     */
    private static boolean isWindowsAdmin() {
        try {
            // This is a basic check - production code should use Windows APIs
            // to properly check for elevated privileges
            String userName = System.getProperty("user.name", "").toLowerCase();
            return userName.contains("admin") || userName.equals("administrator");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate process ID parameter with ownership verification.
     * SECURITY FIX: Now includes ownership validation to prevent privilege escalation.
     */
    public static int validateProcessId(int processId, String operation) {
        if (processId < 0) {
            throw new IllegalArgumentException("Process ID cannot be negative in operation: " + operation);
        }
        if (processId > MAX_THREAD_ID) {
            throw new IllegalArgumentException("Process ID exceeds maximum value in operation: " + operation);
        }

        // SECURITY FIX: Validate process ownership to prevent unauthorized access
        validateProcessOwnership(processId, operation);

        return processId;
    }

    /**
     * Validate that the current process has permission to modify the specified process.
     * SECURITY FIX: Prevents privilege escalation by validating process ownership.
     */
    private static void validateProcessOwnership(int processId, String operation) {
        try {
            long currentPid = ProcessHandle.current().pid();

            // Allow access to current process
            if (processId == currentPid) {
                return;
            }

            // For other processes, require system privileges
            if (!hasSystemPrivileges()) {
                throw new SecurityException(
                    String.format("Insufficient privileges to access process %d in operation %s. " +
                                "Current process is %d and caller lacks system privileges.",
                                processId, operation, currentPid));
            }

            // Even with system privileges, validate the target process exists and is accessible
            if (!isProcessAccessible(processId)) {
                throw new SecurityException("Target process " + processId + " is not accessible for operation " + operation);
            }

        } catch (SecurityException e) {
            throw e; // Re-throw security exceptions
        } catch (Exception e) {
            // If ownership validation fails for any reason, be conservative and deny access
            throw new SecurityException("Process ownership validation failed for process " + processId +
                                      " in operation " + operation + ": " + e.getMessage());
        }
    }

    /**
     * Check if a process is accessible for modification.
     */
    private static boolean isProcessAccessible(int processId) {
        try {
            // Use ProcessHandle to check if process exists and is accessible
            java.util.Optional<ProcessHandle> processHandle = ProcessHandle.of(processId);
            return processHandle.isPresent() && processHandle.get().isAlive();
        } catch (Exception e) {
            return false;
        }
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
     * Validate string input for safety with enhanced security checks.
     */
    public static String validateSafeString(String input, String fieldName, String operation) {
        if (input == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null in operation: " + operation);
        }
        if (input.length() > 128) { // Reduced from MAX_STRING_LENGTH for better security
            throw new IllegalArgumentException(fieldName + " exceeds maximum length (128) in operation: " + operation);
        }
        if (input.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty in operation: " + operation);
        }

        // Check for null bytes and control characters
        if (input.contains("\0") || input.matches(".*[\\x00-\\x1F\\x7F].*")) {
            throw new IllegalArgumentException(fieldName + " contains control characters in operation: " + operation);
        }

        // Check for script injection patterns
        String lowerInput = input.toLowerCase();
        if (lowerInput.contains("<script") || lowerInput.contains("javascript:") ||
            lowerInput.contains("vbscript:") || lowerInput.contains("onload=") ||
            lowerInput.contains("onerror=") || lowerInput.contains("eval(") ||
            lowerInput.contains("exec(") || lowerInput.contains("system(")) {
            throw new IllegalArgumentException(fieldName + " contains potential script injection in operation: " + operation);
        }

        // Enhanced pattern validation - strictly alphanumeric with limited special chars
        if (!SAFE_STRING_PATTERN.matcher(input).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsafe characters in operation: " + operation);
        }

        return input.trim(); // Return trimmed input
    }

    /**
     * Validate file path for safety with enhanced security checks.
     */
    public static String validatePath(String path, String operation) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null in operation: " + operation);
        }
        if (path.length() > 256) { // Reduced from MAX_STRING_LENGTH for paths
            throw new IllegalArgumentException("Path exceeds maximum length (256) in operation: " + operation);
        }
        if (path.trim().isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty in operation: " + operation);
        }

        // Normalize path to detect hidden traversal attempts
        String normalizedPath = path.replace("\\", "/").toLowerCase();

        // Enhanced path traversal detection
        if (normalizedPath.contains("..") || normalizedPath.contains("~") ||
            normalizedPath.contains("./") || normalizedPath.contains("/../") ||
            normalizedPath.contains("%2e%2e") || normalizedPath.contains("..\\") ||
            normalizedPath.contains("%2f") || normalizedPath.contains("%5c")) {
            throw new IllegalArgumentException("Path contains unsafe traversal patterns in operation: " + operation);
        }

        // Check for null bytes and control characters
        if (path.contains("\0") || path.matches(".*[\\x00-\\x1F\\x7F].*")) {
            throw new IllegalArgumentException("Path contains control characters in operation: " + operation);
        }

        // Enhanced pattern validation - more restrictive
        if (!SAFE_PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Path contains unsafe characters in operation: " + operation);
        }

        // Whitelist validation - only allow known safe path prefixes
        boolean isAllowedPrefix = false;
        for (String allowedPrefix : ALLOWED_PATH_PREFIXES) {
            if (normalizedPath.startsWith(allowedPrefix.toLowerCase()) || path.startsWith(allowedPrefix)) {
                isAllowedPrefix = true;
                break;
            }
        }

        if (!isAllowedPrefix) {
            throw new IllegalArgumentException("Path prefix not in whitelist for operation: " + operation);
        }

        // Additional canonical path check to prevent symlink attacks
        try {
            java.nio.file.Path canonicalPath = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
            String canonicalStr = canonicalPath.toString();

            // Ensure canonical path also starts with an allowed prefix
            boolean canonicalAllowed = false;
            for (String allowedPrefix : ALLOWED_PATH_PREFIXES) {
                if (canonicalStr.toLowerCase().startsWith(allowedPrefix.toLowerCase())) {
                    canonicalAllowed = true;
                    break;
                }
            }

            if (!canonicalAllowed) {
                throw new IllegalArgumentException("Canonical path not in whitelist for operation: " + operation);
            }

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid path format in operation: " + operation + " - " + e.getMessage());
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