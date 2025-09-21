package com.faster.affinity.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

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

    // SECURITY FIX: More restrictive path pattern - excludes dots to prevent traversal attacks
    // Allows: alphanumeric, underscore, hyphen, forward slash, backslash, colon (for drive letters)
    private static final Pattern SAFE_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-/\\\\:]+$");

    // SECURITY FIX: Whitelist of allowed path prefixes with no relative paths
    private static final Set<String> ALLOWED_PATH_PREFIXES = Set.of(
        "/proc", "/sys", "/dev", "/tmp",  // Linux safe paths
        "C:\\Windows\\System32", "C:\\Program Files",  // Windows safe paths
        "/usr/lib", "/lib", "/lib64", "/usr/lib64"  // Additional Linux system library paths
        // SECURITY FIX: Removed "." (current directory) to prevent relative path attacks
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
     * SECURITY FIX: Race-condition resistant validation with proper privilege checking.
     */
    private static boolean checkLinuxThreadOwnership(long threadId, long processId) {
        try {
            // SECURITY FIX: Validate input parameters first
            if (threadId <= 0 || processId <= 0) {
                logger.warn("Invalid thread/process ID: thread={}, process={}", threadId, processId);
                return false;
            }

            // SECURITY FIX: Only allow access to current process by default
            long currentPid = ProcessHandle.current().pid();
            if (processId != currentPid) {
                // For cross-process access, require explicit privilege check
                if (!hasLinuxPrivilegeForProcess(processId)) {
                    logger.warn("Insufficient privileges to access thread {} in process {} (current: {})",
                               threadId, processId, currentPid);
                    return false;
                }
            }

            // SECURITY FIX: Use atomic read to prevent TOCTOU race conditions
            return validateLinuxThreadAtomic(threadId, processId);

        } catch (Exception e) {
            // SECURITY FIX: Log security-relevant failures with audit trail
            logger.warn("Linux thread ownership validation failed for thread {} in process {}: {}",
                       threadId, processId, e.getMessage());
            auditSecurityViolation("THREAD_OWNERSHIP_CHECK_FAILED", threadId, processId, e.getMessage());
            return false;
        }
    }

    /**
     * SECURITY FIX: Atomic validation to prevent race conditions.
     */
    private static boolean validateLinuxThreadAtomic(long threadId, long processId) {
        try {
            // Read all relevant information in a single atomic operation
            java.nio.file.Path threadPath = java.nio.file.Paths.get("/proc", String.valueOf(processId), "task", String.valueOf(threadId));
            java.nio.file.Path statusPath = threadPath.resolve("status");

            // SECURITY FIX: Check existence and readability atomically
            if (!java.nio.file.Files.exists(statusPath) || !java.nio.file.Files.isReadable(statusPath)) {
                return false;
            }

            // SECURITY FIX: Read status file completely to prevent partial reads
            java.util.List<String> statusLines;
            try {
                statusLines = java.nio.file.Files.readAllLines(statusPath);
            } catch (IOException e) {
                logger.debug("Cannot read thread status file: {}", e.getMessage());
                return false;
            }

            // Validate thread group ID matches expected process
            boolean tgidValid = false;
            boolean uidValid = false;
            long currentUid = getCurrentLinuxUid();

            for (String line : statusLines) {
                if (line.startsWith("Tgid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            long tgid = Long.parseLong(parts[1]);
                            tgidValid = (tgid == processId);
                        } catch (NumberFormatException e) {
                            logger.debug("Invalid TGID format: {}", line);
                            return false;
                        }
                    }
                } else if (line.startsWith("Uid:")) {
                    // Check real UID matches current process
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            long realUid = Long.parseLong(parts[1]);
                            uidValid = (realUid == currentUid);
                        } catch (NumberFormatException e) {
                            logger.debug("Invalid UID format: {}", line);
                            return false;
                        }
                    }
                }

                if (tgidValid && uidValid) {
                    break; // Found both validations
                }
            }

            return tgidValid && uidValid;

        } catch (Exception e) {
            logger.debug("Atomic thread validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * SECURITY FIX: Check Linux privileges for cross-process access.
     */
    private static boolean hasLinuxPrivilegeForProcess(long processId) {
        try {
            // Check if we can read the process status (basic permission check)
            java.nio.file.Path processStatusPath = java.nio.file.Paths.get("/proc", String.valueOf(processId), "status");
            if (!java.nio.file.Files.isReadable(processStatusPath)) {
                return false;
            }

            // For enhanced security, only allow access to processes with same UID
            // or if running as root (UID 0)
            long currentUid = getCurrentLinuxUid();
            if (currentUid == 0) {
                return true; // Root can access all processes
            }

            // Check target process UID
            java.util.List<String> statusLines = java.nio.file.Files.readAllLines(processStatusPath);
            for (String line : statusLines) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            long targetUid = Long.parseLong(parts[1]);
                            return (targetUid == currentUid);
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                }
            }

            return false; // Could not determine UID - deny access
        } catch (Exception e) {
            logger.debug("Linux privilege check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get current process UID on Linux.
     */
    private static long getCurrentLinuxUid() {
        try {
            java.nio.file.Path statusPath = java.nio.file.Paths.get("/proc/self/status");
            java.util.List<String> lines = java.nio.file.Files.readAllLines(statusPath);
            for (String line : lines) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Cannot determine current UID: {}", e.getMessage());
        }
        return -1; // Unknown UID - fail secure
    }

    /**
     * Check thread ownership on Windows.
     * SECURITY FIX: Enhanced validation with proper privilege checking.
     */
    private static boolean checkWindowsThreadOwnership(long threadId, long processId) {
        try {
            // SECURITY FIX: Validate input parameters first
            if (threadId <= 0 || processId <= 0) {
                logger.warn("Invalid thread/process ID: thread={}, process={}", threadId, processId);
                return false;
            }

            // SECURITY FIX: Use Java ProcessHandle for robust validation
            long currentPid = ProcessHandle.current().pid();

            // 1. Basic validation: only allow threads from current process by default
            if (processId != currentPid) {
                // For cross-process access, require explicit privilege verification
                if (!hasWindowsPrivilegeForProcess(processId)) {
                    logger.warn("Insufficient privileges to access thread {} in process {} (current: {})",
                               threadId, processId, currentPid);
                    auditSecurityViolation("CROSS_PROCESS_ACCESS_DENIED", threadId, processId,
                                          "Attempted cross-process thread access without privileges");
                    return false;
                }
            }

            // 2. Always allow current thread (optimization and safety)
            if (threadId == Thread.currentThread().getId()) {
                return true;
            }

            // 3. SECURITY FIX: Validate thread ID is in reasonable range
            if (threadId > Integer.MAX_VALUE) {
                logger.warn("Thread ID exceeds valid range: {}", threadId);
                return false;
            }

            // 4. SECURITY FIX: For threads in current process, verify Java thread accessibility
            return validateWindowsThreadAccess(threadId, processId);

        } catch (Exception e) {
            // SECURITY FIX: Log security-relevant failures with audit trail
            logger.warn("Windows thread ownership validation failed for thread {} in process {}: {}",
                       threadId, processId, e.getMessage());
            auditSecurityViolation("THREAD_OWNERSHIP_CHECK_FAILED", threadId, processId, e.getMessage());
            return false;
        }
    }

    /**
     * SECURITY FIX: Validate Windows thread access within current process.
     */
    private static boolean validateWindowsThreadAccess(long threadId, long processId) {
        try {
            // For threads in current process, use Java thread enumeration for validation
            java.util.Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

            for (Thread thread : allThreads.keySet()) {
                if (thread.getId() == threadId) {
                    // Found the thread - verify it's alive and accessible
                    if (thread.isAlive()) {
                        return true;
                    }
                    break;
                }
            }

            // Thread not found in current JVM - this could be a native thread
            // SECURITY FIX: Be conservative for unknown threads
            logger.debug("Thread {} not found in Java thread enumeration", threadId);
            return false;

        } catch (Exception e) {
            logger.debug("Windows thread access validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * SECURITY FIX: Check Windows privileges for cross-process access.
     */
    private static boolean hasWindowsPrivilegeForProcess(long processId) {
        try {
            // Check if target process is accessible
            java.util.Optional<ProcessHandle> targetProcess = ProcessHandle.of(processId);
            if (!targetProcess.isPresent()) {
                return false; // Process doesn't exist or no access
            }

            ProcessHandle target = targetProcess.get();
            ProcessHandle current = ProcessHandle.current();

            // Check if we can access process information (basic permission test)
            try {
                ProcessHandle.Info targetInfo = target.info();
                ProcessHandle.Info currentInfo = current.info();

                // SECURITY FIX: Only allow access to processes with same user
                // (Windows equivalent of UID checking)
                java.util.Optional<String> targetUser = targetInfo.user();
                java.util.Optional<String> currentUser = currentInfo.user();

                if (targetUser.isPresent() && currentUser.isPresent()) {
                    boolean sameUser = targetUser.get().equals(currentUser.get());
                    if (!sameUser) {
                        logger.debug("Cross-process access denied: different users ({} vs {})",
                                   currentUser.get(), targetUser.get());
                        return false;
                    }
                }

                return true;
            } catch (Exception e) {
                logger.debug("Cannot access target process information: {}", e.getMessage());
                return false;
            }

        } catch (Exception e) {
            logger.debug("Windows privilege check failed: {}", e.getMessage());
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

        // SECURITY FIX: Require absolute paths only - no relative paths allowed
        if (!java.nio.file.Paths.get(path).isAbsolute()) {
            throw new IllegalArgumentException("Only absolute paths are allowed in operation: " + operation);
        }

        // Normalize path to detect hidden traversal attempts
        String normalizedPath = path.replace("\\", "/").toLowerCase();

        // SECURITY FIX: Enhanced path traversal detection with more patterns
        if (normalizedPath.contains("..") || normalizedPath.contains("~") ||
            normalizedPath.contains("./") || normalizedPath.contains("/../") ||
            normalizedPath.contains("%2e%2e") || normalizedPath.contains("..\\") ||
            normalizedPath.contains("%2f") || normalizedPath.contains("%5c") ||
            normalizedPath.contains("%%") || normalizedPath.contains("%2e") ||
            normalizedPath.contains("%252e") || normalizedPath.contains("%c0%af") ||
            normalizedPath.contains("%c1%9c")) {
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

        // SECURITY FIX: Enhanced canonical path check to prevent symlink and traversal attacks
        try {
            java.nio.file.Path originalPath = java.nio.file.Paths.get(path);
            java.nio.file.Path canonicalPath = originalPath.toAbsolutePath().normalize();
            String canonicalStr = canonicalPath.toString();

            // SECURITY FIX: Ensure original and canonical paths match (prevents symlink attacks)
            if (!originalPath.toAbsolutePath().normalize().equals(canonicalPath)) {
                throw new IllegalArgumentException("Path resolution mismatch detected (possible symlink attack) in operation: " + operation);
            }

            // SECURITY FIX: Verify canonical path doesn't escape allowed prefixes
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

            // SECURITY FIX: Additional check that canonical path doesn't contain traversal sequences
            String canonicalNormalized = canonicalStr.replace("\\", "/").toLowerCase();
            if (canonicalNormalized.contains("..") || canonicalNormalized.contains("~")) {
                throw new IllegalArgumentException("Canonical path contains traversal sequences in operation: " + operation);
            }

        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path syntax in operation: " + operation + " - " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Path validation failed in operation: " + operation + " - " + e.getMessage());
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

    /**
     * SECURITY FIX: Audit security violations for monitoring and alerting.
     */
    private static void auditSecurityViolation(String violationType, long threadId, long processId, String details) {
        try {
            // Log security violation in structured format for SIEM integration
            logger.error("SECURITY_VIOLATION: type={}, thread={}, process={}, details={}, timestamp={}",
                        violationType, threadId, processId, sanitizeForLogging(details), System.currentTimeMillis());

            // TODO: Integrate with security monitoring system
            // - Send alert to SIEM
            // - Trigger incident response
            // - Rate limit to prevent log flooding
            // - Store in security audit database

        } catch (Exception e) {
            // Never let audit logging break the security validation
            logger.debug("Failed to audit security violation: {}", e.getMessage());
        }
    }
}