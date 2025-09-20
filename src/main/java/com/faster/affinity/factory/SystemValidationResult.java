package com.faster.affinity.factory;

import com.faster.affinity.platform.PlatformProviderFactory;

/**
 * System validation result
 */
public class SystemValidationResult {
    private final boolean supported;
    private final String platformName;
    private final java.util.List<String> supportedFeatures;
    private final java.util.List<String> warnings;
    private final java.util.List<String> errors;

    private SystemValidationResult(boolean supported, String platformName,
                                   java.util.List<String> supportedFeatures,
                                   java.util.List<String> warnings,
                                   java.util.List<String> errors) {
        this.supported = supported;
        this.platformName = platformName;
        this.supportedFeatures = new java.util.ArrayList<>(supportedFeatures);
        this.warnings = new java.util.ArrayList<>(warnings);
        this.errors = new java.util.ArrayList<>(errors);
    }

    public static SystemValidationResult validate() {
        String platformName = System.getProperty("os.name", "Unknown");
        java.util.List<String> features = new java.util.ArrayList<>();
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<String> errors = new java.util.ArrayList<>();

        boolean supported = true;

        // Check platform support
        if (!PlatformProviderFactory.isPlatformSupported()) {
            errors.add("Platform not supported: " + platformName);
            supported = false;
        } else {
            features.add("platform_supported");
        }

        // Check JNA availability
        try {
            Class.forName("com.sun.jna.Native");
            features.add("jna_available");
        } catch (ClassNotFoundException e) {
            errors.add("JNA library not available - required for native operations");
            supported = false;
        }

        // Check SLF4J availability
        try {
            Class.forName("org.slf4j.Logger");
            features.add("slf4j_available");
        } catch (ClassNotFoundException e) {
            warnings.add("SLF4J not available - logging will be limited");
        }

        // Platform-specific checks
        if (platformName.toLowerCase().contains("linux")) {
            validateLinuxPlatform(features, warnings, errors);
        } else if (platformName.toLowerCase().contains("windows")) {
            validateWindowsPlatform(features, warnings, errors);
        }

        // Check Java version
        String javaVersion = System.getProperty("java.version", "unknown");
        try {
            String[] parts = javaVersion.split("\\.");
            int majorVersion = Integer.parseInt(parts[0]);
            if (majorVersion < 8) {
                errors.add("Java 8 or higher required, found: " + javaVersion);
                supported = false;
            } else {
                features.add("java_version_ok");
                if (majorVersion >= 11) {
                    features.add("java_11_plus");
                }
            }
        } catch (Exception e) {
            warnings.add("Could not determine Java version: " + javaVersion);
        }

        return new SystemValidationResult(supported, platformName, features, warnings, errors);
    }

    private static void validateLinuxPlatform(java.util.List<String> features,
                                              java.util.List<String> warnings, java.util.List<String> errors) {

        // Check for sysfs
        if (new java.io.File("/sys").exists()) {
            features.add("sysfs_available");
        } else {
            errors.add("sysfs not available - required for topology detection");
        }

        // Check for procfs
        if (new java.io.File("/proc").exists()) {
            features.add("procfs_available");
        } else {
            errors.add("procfs not available - required for process monitoring");
        }

        // Check for NUMA support
        if (new java.io.File("/sys/devices/system/node").exists()) {
            features.add("numa_topology_available");
        }

        // Check for CPU topology
        if (new java.io.File("/sys/devices/system/cpu").exists()) {
            features.add("cpu_topology_available");
        }

        // Check for performance monitoring
        if (new java.io.File("/proc/sys/kernel/perf_event_paranoid").exists()) {
            features.add("perf_events_available");
        } else {
            warnings.add("Performance event monitoring may be limited");
        }
    }

    private static void validateWindowsPlatform(java.util.List<String> features,
                                                java.util.List<String> warnings, java.util.List<String> errors) {

        // Basic Windows support
        features.add("windows_kernel32_available");

        // Check Windows version
        String osVersion = System.getProperty("os.version", "unknown");
        try {
            double version = Double.parseDouble(osVersion);
            if (version >= 6.1) { // Windows 7/Server 2008 R2 or later
                features.add("modern_windows_version");
            } else {
                warnings.add("Old Windows version detected: " + osVersion);
            }
        } catch (Exception e) {
            warnings.add("Could not determine Windows version: " + osVersion);
        }

        // NUMA support is available on most modern Windows
        features.add("numa_api_available");
    }

    // Getters
    public boolean isSupported() { return supported; }
    public String getPlatformName() { return platformName; }
    public java.util.List<String> getSupportedFeatures() { return new java.util.ArrayList<>(supportedFeatures); }
    public java.util.List<String> getWarnings() { return new java.util.ArrayList<>(warnings); }
    public java.util.List<String> getErrors() { return new java.util.ArrayList<>(errors); }

    public boolean hasFeature(String feature) {
        return supportedFeatures.contains(feature);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SystemValidation{")
                .append("platform=").append(platformName)
                .append(", supported=").append(supported)
                .append(", features=").append(supportedFeatures.size())
                .append(", warnings=").append(warnings.size())
                .append(", errors=").append(errors.size())
                .append("}");
        return sb.toString();
    }

    public String getDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Affinity Library System Validation ===\n");
        sb.append("Platform: ").append(platformName).append("\n");
        sb.append("Overall Support: ").append(supported ? "YES" : "NO").append("\n\n");

        if (!supportedFeatures.isEmpty()) {
            sb.append("Supported Features:\n");
            supportedFeatures.forEach(f -> sb.append("  ✓ ").append(f).append("\n"));
            sb.append("\n");
        }

        if (!warnings.isEmpty()) {
            sb.append("Warnings:\n");
            warnings.forEach(w -> sb.append("  ⚠ ").append(w).append("\n"));
            sb.append("\n");
        }

        if (!errors.isEmpty()) {
            sb.append("Errors:\n");
            errors.forEach(e -> sb.append("  ✗ ").append(e).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }
}