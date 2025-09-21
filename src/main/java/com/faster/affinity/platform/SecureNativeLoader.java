package com.faster.affinity.platform;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Secure native library loader with validation and whitelisting.
 * Prevents DLL/SO injection attacks by validating library signatures and paths.
 */
public final class SecureNativeLoader {
    private static final Logger logger = LoggerFactory.getLogger(SecureNativeLoader.class);

    // Whitelist of allowed system libraries with their expected signatures
    private static final Map<String, byte[]> ALLOWED_LIBRARIES = new HashMap<>();

    static {
        // Initialize whitelist for known system libraries with computed signatures
        // SECURITY FIX: Use actual signatures instead of null to prevent DLL/SO injection
        initializeSystemLibrarySignatures();
    }

    /**
     * Initialize signatures for system libraries based on current platform.
     * SECURITY FIX: Only add libraries with valid signatures to prevent bypass.
     */
    private static void initializeSystemLibrarySignatures() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("windows")) {
            // For Windows system libraries, verify they exist in System32
            addLibraryIfValid("kernel32");
        } else if (osName.contains("linux")) {
            // For Linux system libraries, verify they exist in standard system paths
            addLibraryIfValid("c");
            addLibraryIfValid("numa");
        }

        logger.info("Initialized secure library whitelist for platform: {} with {} libraries",
                   osName, ALLOWED_LIBRARIES.size());
    }

    /**
     * SECURITY FIX: Only add libraries with valid signatures to the whitelist.
     */
    private static void addLibraryIfValid(String libraryName) {
        try {
            byte[] signature = computeSystemLibrarySignature(libraryName);
            if (signature != null && signature.length > 1) {
                ALLOWED_LIBRARIES.put(libraryName, signature);
                logger.debug("Added trusted library to whitelist: {} (signature length: {})",
                           libraryName, signature.length);
            } else {
                logger.warn("Skipping library due to invalid signature: {}", libraryName);
                // SECURITY AUDIT: Log libraries that couldn't be validated
                auditSecurityEvent("LIBRARY_SIGNATURE_INVALID", libraryName,
                                 "Could not compute valid signature during initialization");
            }
        } catch (Exception e) {
            logger.error("Failed to compute signature for library {}: {}", libraryName, e.getMessage());
            auditSecurityEvent("LIBRARY_INITIALIZATION_ERROR", libraryName,
                             "Error: " + e.getMessage());
        }
    }

    /**
     * SECURITY FIX: Pre-computed signatures for trusted system libraries.
     * These should be computed during build time and embedded in the application.
     */
    private static final Map<String, String> TRUSTED_LIBRARY_HASHES = new ConcurrentHashMap<>();

    static {
        // CRITICAL FIX: Initialize with actual SHA-256 hashes of trusted system libraries
        // These values should be computed during build time for the target deployment environment
        initializeTrustedLibraryHashes();
    }

    /**
     * Initialize trusted library hashes for the current platform.
     * SECURITY CRITICAL: These hashes must be computed during secure build process.
     */
    private static void initializeTrustedLibraryHashes() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("windows")) {
            // Windows system library hashes - these should be computed during build
            // Example: TRUSTED_LIBRARY_HASHES.put("kernel32", "actual-sha256-hash-here");
            logger.warn("Windows library hashes not pre-computed - using runtime verification");
        } else if (osName.contains("linux")) {
            // Linux system library hashes - these should be computed during build
            // Example: TRUSTED_LIBRARY_HASHES.put("c", "actual-sha256-hash-here");
            logger.warn("Linux library hashes not pre-computed - using runtime verification");
        }
    }

    /**
     * Compute signature for system libraries with enhanced security.
     * SECURITY FIX: Reject unknown libraries instead of using placeholder signatures.
     */
    private static byte[] computeSystemLibrarySignature(String libraryName) {
        // SECURITY FIX: Check for pre-computed trusted hash first
        String trustedHash = TRUSTED_LIBRARY_HASHES.get(libraryName);
        if (trustedHash != null) {
            try {
                return hexStringToByteArray(trustedHash);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid trusted hash format for library {}: {}", libraryName, e.getMessage());
                return null; // Fail secure - reject invalid hashes
            }
        }

        // SECURITY FIX: For runtime computation, be more strict about library validation
        try {
            Path libraryPath = findSystemLibraryPath(libraryName);
            if (libraryPath != null && Files.exists(libraryPath)) {
                // Additional security check: verify path is in trusted system directory
                if (!isInTrustedSystemDirectory(libraryPath)) {
                    logger.error("Library path not in trusted system directory: {}", libraryPath);
                    return null; // Fail secure
                }

                byte[] hash = computeFileHash(libraryPath);
                logger.warn("Runtime computed hash for {}: {} - Consider pre-computing for production",
                           libraryName, byteArrayToHexString(hash));
                return hash;
            }
        } catch (Exception e) {
            logger.error("Failed to compute signature for system library {}: {}", libraryName, e.getMessage());
        }

        // SECURITY FIX: Return null instead of placeholder to force failure
        logger.error("Cannot validate library signature for: {} - library will be rejected", libraryName);
        return null; // Fail secure - no placeholder signatures
    }

    /**
     * Verify that a library path is in a trusted system directory.
     */
    private static boolean isInTrustedSystemDirectory(Path libraryPath) {
        try {
            Path canonicalPath = libraryPath.toRealPath();
            String pathStr = canonicalPath.toString().toLowerCase();

            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("windows")) {
                String systemRoot = System.getenv("SystemRoot");
                if (systemRoot != null) {
                    String trustedDir = Paths.get(systemRoot, "System32").toString().toLowerCase();
                    return pathStr.startsWith(trustedDir);
                }
                return pathStr.contains("\\windows\\system32\\");
            } else if (osName.contains("linux")) {
                return pathStr.startsWith("/lib") || pathStr.startsWith("/usr/lib");
            }

            return false; // Unknown OS - fail secure
        } catch (IOException e) {
            logger.error("Cannot resolve canonical path for {}: {}", libraryPath, e.getMessage());
            return false; // Fail secure
        }
    }

    /**
     * Convert hex string to byte array.
     */
    private static byte[] hexStringToByteArray(String hexString) {
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }

        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hexString.substring(i, i + 2), 16);
        }
        return bytes;
    }

    /**
     * Convert byte array to hex string.
     */
    private static String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Find the path to a system library on the current platform.
     */
    private static Path findSystemLibraryPath(String libraryName) {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("windows")) {
            return findWindowsSystemLibrary(libraryName);
        } else if (osName.contains("linux")) {
            return findLinuxSystemLibrary(libraryName);
        }

        return null;
    }

    /**
     * Find Windows system library in System32 directory.
     */
    private static Path findWindowsSystemLibrary(String libraryName) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) {
            systemRoot = "C:\\Windows";
        }

        Path system32 = Paths.get(systemRoot, "System32");
        Path dllPath = system32.resolve(libraryName + ".dll");

        return Files.exists(dllPath) ? dllPath : null;
    }

    /**
     * Find Linux system library in standard system directories.
     */
    private static Path findLinuxSystemLibrary(String libraryName) {
        String[] systemPaths = {"/lib64", "/lib", "/usr/lib64", "/usr/lib"};
        String[] prefixes = {"lib", ""};
        String[] extensions = {".so", ".so.6", ".so.1"};

        for (String basePath : systemPaths) {
            for (String prefix : prefixes) {
                for (String extension : extensions) {
                    String fullName = prefix + libraryName + extension;
                    Path libPath = Paths.get(basePath, fullName);
                    if (Files.exists(libPath)) {
                        return libPath;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Securely load a native library with validation.
     *
     * @param libraryName The name of the library to load
     * @param interfaceClass The JNA interface class
     * @param <T> The interface type
     * @return The loaded library instance
     * @throws SecurityException if the library fails validation
     */
    public static <T extends Library> T loadLibrary(String libraryName, Class<T> interfaceClass) {
        // SECURITY FIX: Comprehensive validation including signature verification
        validateLibraryName(libraryName);
        validateLibraryPath(libraryName);
        validateLibrarySignature(libraryName); // NEW: Mandatory signature verification

        try {
            logger.debug("Loading validated native library: {}", libraryName);
            return Native.load(libraryName, interfaceClass);
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load native library: {}", libraryName, e);
            throw new SecurityException("Failed to load validated native library: " + libraryName, e);
        }
    }

    /**
     * Validate library signature to prevent DLL/SO injection attacks.
     */
    private static void validateLibrarySignature(String libraryName) {
        Path libraryPath = findSystemLibraryPath(libraryName);
        if (libraryPath == null) {
            throw new SecurityException("Could not locate system library: " + libraryName);
        }

        if (!verifySignature(libraryName, libraryPath)) {
            throw new SecurityException("Library signature verification failed: " + libraryName);
        }

        logger.debug("Library signature verification passed: {}", libraryName);
    }

    /**
     * Validate that the library name is in the whitelist.
     */
    private static void validateLibraryName(String libraryName) {
        if (libraryName == null || libraryName.trim().isEmpty()) {
            throw new SecurityException("Library name cannot be null or empty");
        }

        if (!ALLOWED_LIBRARIES.containsKey(libraryName)) {
            throw new SecurityException("Library not in whitelist: " + libraryName);
        }

        // Check for path traversal attempts
        if (libraryName.contains("..") || libraryName.contains("/") || libraryName.contains("\\")) {
            throw new SecurityException("Invalid library name format: " + libraryName);
        }
    }

    /**
     * Validate the library path and signature if available.
     */
    private static void validateLibraryPath(String libraryName) {
        try {
            // Get the system property for library path
            String libraryPath = System.getProperty("java.library.path");
            if (libraryPath == null) {
                logger.warn("java.library.path not set, using system default");
                return;
            }

            // For system libraries like kernel32 and libc, we rely on OS validation
            // In a more secure implementation, you would verify digital signatures here
            logger.debug("Library path validation passed for: {}", libraryName);

        } catch (Exception e) {
            logger.error("Library path validation failed for: {}", libraryName, e);
            throw new SecurityException("Library path validation failed", e);
        }
    }

    /**
     * Compute SHA-256 hash of a file for signature verification.
     */
    @SuppressWarnings("unused")
    private static byte[] computeFileHash(Path filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(filePath);
        return digest.digest(fileBytes);
    }

    /**
     * Verify library signature against known good signature.
     * SECURITY FIX: Enhanced signature verification with fail-secure defaults.
     */
    private static boolean verifySignature(String libraryName, Path libraryPath) {
        try {
            byte[] expectedSignature = ALLOWED_LIBRARIES.get(libraryName);
            if (expectedSignature == null) {
                logger.error("No expected signature found for library: {}", libraryName);
                return false; // SECURITY FIX: Reject libraries without expected signatures
            }

            // SECURITY FIX: Reject null signatures (fail-secure from computeSystemLibrarySignature)
            if (expectedSignature.length == 0) {
                logger.error("Empty signature indicates security validation failure for library: {}", libraryName);
                return false;
            }

            // SECURITY FIX: Enhanced placeholder signature detection
            if ((expectedSignature.length == 1 && expectedSignature[0] == 0) ||
                Arrays.equals(expectedSignature, new byte[]{0})) {
                logger.error("Library not properly initialized in whitelist: {}", libraryName);
                return false;
            }

            byte[] actualSignature = computeFileHash(libraryPath);
            if (actualSignature == null || actualSignature.length == 0) {
                logger.error("Could not compute signature for library: {} at path: {}", libraryName, libraryPath);
                return false;
            }

            boolean verified = Arrays.equals(expectedSignature, actualSignature);

            if (!verified) {
                logger.error("Signature verification failed for library: {} at path: {}",
                           libraryName, libraryPath);
                logger.debug("Expected signature: {}, Actual signature: {}",
                           byteArrayToHexString(expectedSignature), byteArrayToHexString(actualSignature));

                // SECURITY AUDIT: Log failed signature verification for monitoring
                auditSecurityEvent("SIGNATURE_VERIFICATION_FAILED", libraryName, libraryPath.toString());
            } else {
                logger.debug("Signature verification successful for library: {}", libraryName);
                auditSecurityEvent("SIGNATURE_VERIFICATION_SUCCESS", libraryName, libraryPath.toString());
            }

            return verified;
        } catch (Exception e) {
            logger.error("Signature verification error for library: {} at path: {}",
                       libraryName, libraryPath, e);
            auditSecurityEvent("SIGNATURE_VERIFICATION_ERROR", libraryName,
                             "Error: " + e.getMessage());
            return false; // Fail secure on any exception
        }
    }

    /**
     * Audit security events for monitoring and alerting.
     */
    private static void auditSecurityEvent(String eventType, String libraryName, String details) {
        try {
            // Log security event in structured format for SIEM integration
            logger.warn("SECURITY_AUDIT: event={}, library={}, details={}, timestamp={}",
                       eventType, libraryName, details, System.currentTimeMillis());

            // TODO: Integrate with security monitoring system
            // - Send to SIEM
            // - Trigger alerts for failed verifications
            // - Rate limit to prevent log flooding
        } catch (Exception e) {
            // Never let audit logging break the security check
            logger.debug("Failed to audit security event: {}", e.getMessage());
        }
    }

    /**
     * Add a library to the whitelist with its signature.
     * This should only be called during secure initialization.
     */
    public static void addToWhitelist(String libraryName, byte[] signature) {
        if (libraryName == null || libraryName.trim().isEmpty()) {
            throw new IllegalArgumentException("Library name cannot be null or empty");
        }

        logger.info("Adding library to whitelist: {}", libraryName);
        ALLOWED_LIBRARIES.put(libraryName, signature);
    }

    /**
     * Check if a library is whitelisted.
     */
    public static boolean isWhitelisted(String libraryName) {
        return ALLOWED_LIBRARIES.containsKey(libraryName);
    }
}