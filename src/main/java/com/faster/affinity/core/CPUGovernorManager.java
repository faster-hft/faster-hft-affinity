package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU Governor Manager for HFT Performance Optimization.
 *
 * <p>Controls CPU frequency scaling governors to ensure deterministic performance.
 * Critical for trading applications that require consistent, predictable latency.</p>
 *
 * <p>CPU governors control how the processor adjusts its frequency based on system load.
 * For high-frequency trading applications, maintaining maximum frequency (PERFORMANCE mode)
 * is typically preferred to avoid frequency transition latencies that can impact
 * trading algorithm execution times.</p>
 *
 * <h2>Supported Governors:</h2>
 * <ul>
 *   <li><b>PERFORMANCE:</b> Runs CPU at maximum frequency - recommended for HFT</li>
 *   <li><b>POWERSAVE:</b> Runs CPU at minimum frequency - saves power but increases latency</li>
 *   <li><b>ONDEMAND:</b> Dynamically adjusts frequency based on CPU load</li>
 *   <li><b>CONSERVATIVE:</b> Similar to ondemand but with more gradual changes</li>
 *   <li><b>SCHEDUTIL:</b> Uses scheduler information for frequency decisions</li>
 *   <li><b>USERSPACE:</b> Allows manual frequency control</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * CPUGovernorManager manager = new CPUGovernorManager(platformProvider, config);
 * manager.initialize();
 *
 * // Set all cores to performance mode for maximum frequency
 * OperationResult<Void> result = manager.setAllCoresGovernor(GovernorMode.PERFORMANCE);
 * if (result.isSuccess()) {
 *     System.out.println("All cores set to performance mode");
 * }
 *
 * // Check status
 * OperationResult<GovernorStatus> status = manager.getGovernorStatus();
 * System.out.println("Governor status: " + status.getData());
 *
 * // Cleanup - restore original governors
 * manager.shutdown();
 * }</pre>
 *
 * <p><b>Platform Support:</b></p>
 * <ul>
 *   <li><b>Linux:</b> Full support via /sys/devices/system/cpu/cpufreq interface</li>
 *   <li><b>Windows:</b> Limited support via power management APIs</li>
 *   <li><b>macOS:</b> Not supported (requires mock implementation)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe and can be used concurrently.</p>
 *
 * @author Amar Mond
 * @version 1.1.0
 * @since 1.0.0
 * @see GovernorMode
 * @see GovernorStatus
 * @see GovernorInfo
 */
public final class CPUGovernorManager {
    private static final Logger logger = LoggerFactory.getLogger(CPUGovernorManager.class);

    /**
     * CPU frequency governor modes available on Linux systems.
     *
     * <p>Each governor implements a different policy for CPU frequency scaling
     * based on system load and performance requirements.</p>
     *
     * @author Amar Mond
     */
    public enum GovernorMode {
        /**
         * Performance governor - runs CPU at maximum frequency.
         * <p>Recommended for HFT applications requiring consistent, minimal latency.</p>
         */
        PERFORMANCE("performance"),

        /**
         * Powersave governor - runs CPU at minimum frequency.
         * <p>Saves power but increases latency. Not recommended for trading applications.</p>
         */
        POWERSAVE("powersave"),

        /**
         * Ondemand governor - dynamically scales frequency based on CPU load.
         * <p>Increases frequency when load is high, decreases when idle.</p>
         */
        ONDEMAND("ondemand"),

        /**
         * Conservative governor - similar to ondemand but with more gradual frequency changes.
         * <p>Less aggressive frequency scaling than ondemand.</p>
         */
        CONSERVATIVE("conservative"),

        /**
         * Schedutil governor - uses scheduler information for frequency decisions.
         * <p>Modern governor that integrates with the kernel scheduler.</p>
         */
        SCHEDUTIL("schedutil"),

        /**
         * Userspace governor - allows manual frequency control.
         * <p>Frequency must be set explicitly by userspace applications.</p>
         */
        USERSPACE("userspace"),

        /**
         * Unknown or unsupported governor.
         * <p>Used when the actual governor cannot be determined or is not supported.</p>
         */
        UNKNOWN("unknown");

        private final String linuxName;

        /**
         * Creates a governor mode with the specified Linux kernel name.
         *
         * @param linuxName the name used by the Linux kernel for this governor
         */
        GovernorMode(String linuxName) {
            this.linuxName = linuxName;
        }

        /**
         * Gets the Linux kernel name for this governor mode.
         *
         * @return the string name used by the Linux kernel
         */
        public String getLinuxName() {
            return linuxName;
        }

        /**
         * Converts a Linux kernel governor name to a GovernorMode enum.
         *
         * @param name the Linux kernel governor name
         * @return the corresponding GovernorMode
         * @throws IllegalArgumentException if the name is not recognized
         */
        public static GovernorMode fromString(String name) {
            for (GovernorMode mode : values()) {
                if (mode.linuxName.equals(name)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown governor mode: " + name);
        }
    }

    public static class GovernorInfo {
        private final int coreId;
        private final GovernorMode currentGovernor;
        private final GovernorMode originalGovernor;
        private final List<GovernorMode> availableGovernors;
        private final long currentFrequency;
        private final long minFrequency;
        private final long maxFrequency;

        public GovernorInfo(int coreId, GovernorMode currentGovernor, GovernorMode originalGovernor,
                           List<GovernorMode> availableGovernors, long currentFrequency,
                           long minFrequency, long maxFrequency) {
            this.coreId = coreId;
            this.currentGovernor = currentGovernor;
            this.originalGovernor = originalGovernor;
            this.availableGovernors = Collections.unmodifiableList(new ArrayList<>(availableGovernors));
            this.currentFrequency = currentFrequency;
            this.minFrequency = minFrequency;
            this.maxFrequency = maxFrequency;
        }

        public int getCoreId() { return coreId; }
        public GovernorMode getCurrentGovernor() { return currentGovernor; }
        public GovernorMode getOriginalGovernor() { return originalGovernor; }
        public List<GovernorMode> getAvailableGovernors() { return availableGovernors; }
        public long getCurrentFrequency() { return currentFrequency; }
        public long getMinFrequency() { return minFrequency; }
        public long getMaxFrequency() { return maxFrequency; }

        @Override
        public String toString() {
            return String.format("Core %d: %s (%,d MHz), available: %s",
                coreId, currentGovernor, currentFrequency / 1000, availableGovernors);
        }
    }

    public static class GovernorStatus {
        private final Map<Integer, GovernorInfo> coreGovernors;
        private final int totalCores;
        private final int performanceCores;
        private final int powerSaveCores;
        private final boolean allCoresPerformance;

        public GovernorStatus(Map<Integer, GovernorInfo> coreGovernors) {
            this.coreGovernors = Collections.unmodifiableMap(new HashMap<>(coreGovernors));
            this.totalCores = coreGovernors.size();
            this.performanceCores = (int) coreGovernors.values().stream()
                .filter(info -> info.getCurrentGovernor() == GovernorMode.PERFORMANCE)
                .count();
            this.powerSaveCores = (int) coreGovernors.values().stream()
                .filter(info -> info.getCurrentGovernor() == GovernorMode.POWERSAVE)
                .count();
            this.allCoresPerformance = performanceCores == totalCores;
        }

        public Map<Integer, GovernorInfo> getCoreGovernors() { return coreGovernors; }
        public int getTotalCores() { return totalCores; }
        public int getPerformanceCores() { return performanceCores; }
        public int getPowerSaveCores() { return powerSaveCores; }
        public boolean isAllCoresPerformance() { return allCoresPerformance; }

        @Override
        public String toString() {
            return String.format("Governors: %d/%d performance, %d powersave, %d other",
                performanceCores, totalCores, powerSaveCores,
                totalCores - performanceCores - powerSaveCores);
        }
    }

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);

    // Cache original governors for restoration
    private final Map<Integer, GovernorMode> originalGovernors = new ConcurrentHashMap<>();
    private final Map<Integer, GovernorInfo> governorCache = new ConcurrentHashMap<>();

    public CPUGovernorManager(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        logger.info("Initializing CPUGovernorManager...");

        try {
            if (!config.isGovernorControlEnabled()) {
                logger.info("CPU governor control disabled in configuration");
                initialized.set(true);
                return;
            }

            // Check if platform supports governor control
            if (!platformProvider.supportsFeature("cpu_governor_control")) {
                logger.warn("CPU governor control not supported on this platform");
                initialized.set(true);
                return;
            }

            // Discover current governors and save originals
            discoverGovernors();
            available.set(true);
            initialized.set(true);

            logger.info("CPUGovernorManager initialized with {} cores", originalGovernors.size());

            // Auto-set to performance mode if configured
            if (config.isAutoSetPerformanceGovernor()) {
                logger.info("Auto-setting all cores to performance governor");
                OperationResult<Void> result = setAllCoresGovernor(GovernorMode.PERFORMANCE);
                if (result.isSuccess()) {
                    logger.info("Successfully set all cores to performance governor");
                } else {
                    logger.warn("Failed to set performance governor: {}", result.getError().getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to initialize CPUGovernorManager: {}", e.getMessage(), e);
            throw new ConfigurationException("initialize", "Failed to initialize CPU governor manager: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    public OperationResult<GovernorMode> getCurrentGovernor(int coreId) {
        try {
            checkInitialized();
            validateCoreId(coreId);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getCurrentGovernor",
                    "CPU governor control not available"));
            }

            GovernorMode governor = platformProvider.getCpuGovernor(coreId);
            if (governor == null) {
                throw new SystemCallException("getCurrentGovernor", "cpu_governor", null);
            }

            return OperationResult.success(governor);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getCurrentGovernor", "cpu_governor", e));
        }
    }

    public OperationResult<Void> setGovernor(int coreId, GovernorMode governor) {
        try {
            checkInitialized();
            validateCoreId(coreId);
            validateGovernor(governor);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setGovernor",
                    "CPU governor control not available"));
            }

            // Save original governor if this is the first change
            if (!originalGovernors.containsKey(coreId)) {
                GovernorMode original = platformProvider.getCpuGovernor(coreId);
                if (original != null) {
                    originalGovernors.put(coreId, original);
                }
            }

            int result = platformProvider.setCpuGovernor(coreId, governor);
            if (result != 0) {
                throw new SystemCallException("setGovernor", "cpu_governor", null);
            }

            // Invalidate cache for this core
            governorCache.remove(coreId);

            logger.debug("Set core {} governor to {}", coreId, governor);
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("setGovernor", "cpu_governor", e));
        }
    }

    public OperationResult<Void> setAllCoresGovernor(GovernorMode governor) {
        try {
            checkInitialized();
            validateGovernor(governor);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setAllCoresGovernor",
                    "CPU governor control not available"));
            }

            int cpuCount = platformProvider.getCpuCount();
            List<Integer> failedCores = new ArrayList<>();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                OperationResult<Void> result = setGovernor(coreId, governor);
                if (!result.isSuccess()) {
                    failedCores.add(coreId);
                    logger.warn("Failed to set governor for core {}: {}",
                        coreId, result.getError().getMessage());
                }
            }

            if (!failedCores.isEmpty()) {
                return OperationResult.failure(new SystemCallException("setAllCoresGovernor",
                    "Failed to set governor for cores: " + failedCores, null));
            }

            logger.info("Successfully set all {} cores to {} governor", cpuCount, governor);
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("setAllCoresGovernor", "cpu_governor", e));
        }
    }

    public OperationResult<GovernorStatus> getGovernorStatus() {
        try {
            checkInitialized();

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getGovernorStatus",
                    "CPU governor control not available"));
            }

            Map<Integer, GovernorInfo> coreGovernors = new HashMap<>();
            int cpuCount = platformProvider.getCpuCount();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                try {
                    GovernorInfo info = getGovernorInfo(coreId);
                    coreGovernors.put(coreId, info);
                } catch (Exception e) {
                    logger.debug("Failed to get governor info for core {}: {}", coreId, e.getMessage());
                }
            }

            GovernorStatus status = new GovernorStatus(coreGovernors);
            return OperationResult.success(status);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getGovernorStatus", "cpu_governor", e));
        }
    }

    public OperationResult<Void> restoreOriginalGovernors() {
        try {
            checkInitialized();

            if (!isAvailable() || originalGovernors.isEmpty()) {
                return OperationResult.success(null);
            }

            List<Integer> failedCores = new ArrayList<>();
            int restoredCount = 0;

            for (Map.Entry<Integer, GovernorMode> entry : originalGovernors.entrySet()) {
                int coreId = entry.getKey();
                GovernorMode originalGovernor = entry.getValue();

                OperationResult<Void> result = setGovernor(coreId, originalGovernor);
                if (result.isSuccess()) {
                    restoredCount++;
                } else {
                    failedCores.add(coreId);
                    logger.warn("Failed to restore governor for core {}: {}",
                        coreId, result.getError().getMessage());
                }
            }

            logger.info("Restored {} CPU governors, {} failed", restoredCount, failedCores.size());

            if (!failedCores.isEmpty()) {
                return OperationResult.failure(new SystemCallException("restoreOriginalGovernors",
                    "Failed to restore governors for cores: " + failedCores, null));
            }

            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("restoreOriginalGovernors", "cpu_governor", e));
        }
    }

    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            logger.info("Shutting down CPUGovernorManager...");

            // Restore original governors if configured
            if (config.isRestoreGovernorsOnShutdown()) {
                OperationResult<Void> result = restoreOriginalGovernors();
                if (result.isSuccess()) {
                    logger.info("Original CPU governors restored");
                } else {
                    logger.warn("Failed to restore some CPU governors: {}", result.getError().getMessage());
                }
            }

            originalGovernors.clear();
            governorCache.clear();
            available.set(false);
            logger.info("CPUGovernorManager shutdown complete");
        }
    }

    // Private helper methods

    private void discoverGovernors() {
        try {
            int cpuCount = platformProvider.getCpuCount();
            originalGovernors.clear();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                try {
                    GovernorMode currentGovernor = platformProvider.getCpuGovernor(coreId);
                    if (currentGovernor != null) {
                        originalGovernors.put(coreId, currentGovernor);
                    }
                } catch (Exception e) {
                    logger.debug("Failed to get governor for core {}: {}", coreId, e.getMessage());
                }
            }

            logger.debug("Discovered governors for {} cores", originalGovernors.size());

        } catch (Exception e) {
            logger.warn("Failed to discover CPU governors: {}", e.getMessage());
        }
    }

    private GovernorInfo getGovernorInfo(int coreId) throws Exception {
        GovernorInfo cached = governorCache.get(coreId);
        if (cached != null && config.isCachingEnabled()) {
            return cached;
        }

        GovernorMode currentGovernor = platformProvider.getCpuGovernor(coreId);
        GovernorMode originalGovernor = originalGovernors.get(coreId);
        List<GovernorMode> availableGovernors = platformProvider.getAvailableGovernors(coreId);
        long currentFrequency = platformProvider.getCpuFrequency(coreId);
        long minFrequency = platformProvider.getCpuMinFrequency(coreId);
        long maxFrequency = platformProvider.getCpuMaxFrequency(coreId);

        GovernorInfo info = new GovernorInfo(coreId, currentGovernor, originalGovernor,
            availableGovernors, currentFrequency, minFrequency, maxFrequency);

        if (config.isCachingEnabled()) {
            governorCache.put(coreId, info);
        }

        return info;
    }

    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("CPUGovernorManager not initialized");
        }
    }

    private void validateCoreId(int coreId) throws InvalidParameterException {
        if (coreId < 0 || coreId >= platformProvider.getCpuCount()) {
            throw new InvalidParameterException("validateCoreId",
                "Core ID " + coreId + " out of range [0, " + (platformProvider.getCpuCount() - 1) + "]");
        }
    }

    private void validateGovernor(GovernorMode governor) throws InvalidParameterException {
        if (governor == null) {
            throw new InvalidParameterException("validateGovernor", "Governor mode cannot be null");
        }
    }
}