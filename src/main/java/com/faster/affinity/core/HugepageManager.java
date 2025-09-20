package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HugepageManager provides control over transparent hugepages for HFT applications.
 * Transparent hugepages can significantly reduce TLB misses, improving performance
 * for memory-intensive workloads.
 */
public class HugepageManager {
    private static final Logger logger = LoggerFactory.getLogger(HugepageManager.class);

    // Hugepage modes
    public enum HugepageMode {
        ALWAYS("always"),         // Always use hugepages
        MADVISE("madvise"),      // Use hugepages only when explicitly requested
        NEVER("never");          // Never use hugepages

        private final String kernelValue;

        HugepageMode(String kernelValue) {
            this.kernelValue = kernelValue;
        }

        public String getKernelValue() {
            return kernelValue;
        }

        public static HugepageMode fromString(String value) {
            for (HugepageMode mode : values()) {
                if (mode.kernelValue.equals(value.toLowerCase())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown hugepage mode: " + value);
        }
    }

    // Hugepage allocation policies
    public enum AllocationPolicy {
        IMMEDIATE("immediate"),   // Allocate hugepages immediately
        DEFER("defer"),          // Defer hugepage allocation
        DEFER_PLUS_MADVISE("defer+madvise"); // Defer allocation + respect madvise

        private final String kernelValue;

        AllocationPolicy(String kernelValue) {
            this.kernelValue = kernelValue;
        }

        public String getKernelValue() {
            return kernelValue;
        }

        public static AllocationPolicy fromString(String value) {
            for (AllocationPolicy policy : values()) {
                if (policy.kernelValue.equals(value.toLowerCase())) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Unknown allocation policy: " + value);
        }
    }

    // Hugepage information
    public static class HugepageInfo {
        private final HugepageMode currentMode;
        private final HugepageMode originalMode;
        private final AllocationPolicy allocationPolicy;
        private final long totalHugepages;
        private final long freeHugepages;
        private final long hugepageSize;
        private final boolean defragEnabled;

        public HugepageInfo(HugepageMode currentMode, HugepageMode originalMode,
                           AllocationPolicy allocationPolicy, long totalHugepages,
                           long freeHugepages, long hugepageSize, boolean defragEnabled) {
            this.currentMode = currentMode;
            this.originalMode = originalMode;
            this.allocationPolicy = allocationPolicy;
            this.totalHugepages = totalHugepages;
            this.freeHugepages = freeHugepages;
            this.hugepageSize = hugepageSize;
            this.defragEnabled = defragEnabled;
        }

        public HugepageMode getCurrentMode() { return currentMode; }
        public HugepageMode getOriginalMode() { return originalMode; }
        public AllocationPolicy getAllocationPolicy() { return allocationPolicy; }
        public long getTotalHugepages() { return totalHugepages; }
        public long getFreeHugepages() { return freeHugepages; }
        public long getHugepageSize() { return hugepageSize; }
        public boolean isDefragEnabled() { return defragEnabled; }

        @Override
        public String toString() {
            return String.format("HugepageInfo{mode=%s->%s, policy=%s, total=%d, free=%d, size=%dKB, defrag=%s}",
                    originalMode, currentMode, allocationPolicy, totalHugepages, freeHugepages,
                    hugepageSize / 1024, defragEnabled);
        }
    }

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);

    private HugepageMode originalMode;
    private AllocationPolicy originalPolicy;
    private boolean originalDefragEnabled;

    public HugepageManager(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        try {
            logger.info("Initializing HugepageManager...");

            // Check if hugepage management is enabled in configuration
            if (!config.isHugepageManagementEnabled()) {
                logger.info("Hugepage management disabled in configuration");
                initialized.set(true);
                return;
            }

            // Check platform support
            if (!platformProvider.supportsFeature("hugepage_control")) {
                logger.warn("Hugepage control not supported on this platform");
                initialized.set(true);
                return;
            }

            // Store original settings for restoration
            originalMode = getCurrentMode().getValue();
            originalPolicy = getCurrentAllocationPolicy().getValue();
            originalDefragEnabled = isDefragmentationEnabled().getValue();

            // Auto-configure hugepages if enabled
            if (config.isAutoConfigureHugepages()) {
                logger.info("Auto-configuring hugepages for HFT workloads");
                configureForHFT();
            }

            available.set(true);
            initialized.set(true);
            logger.info("HugepageManager initialized successfully");

        } catch (Exception e) {
            throw new ConfigurationException("initialize", "Failed to initialize HugepageManager: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    /**
     * Get current hugepage mode
     */
    public OperationResult<HugepageMode> getCurrentMode() {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getCurrentMode",
                        "Hugepage control not available"));
            }

            String modeStr = platformProvider.getHugepageMode();
            HugepageMode mode = HugepageMode.fromString(modeStr);
            return OperationResult.success(mode);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Set hugepage mode
     */
    public OperationResult<Void> setMode(HugepageMode mode) {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setMode",
                        "Hugepage control not available"));
            }

            validateParameter(mode, "mode");

            int result = platformProvider.setHugepageMode(mode.getKernelValue());
            if (result != 0) {
                throw createExceptionForErrorCode(result, "setMode");
            }

            logger.info("Set hugepage mode to: {}", mode);
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Get current allocation policy
     */
    public OperationResult<AllocationPolicy> getCurrentAllocationPolicy() {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getCurrentAllocationPolicy",
                        "Hugepage control not available"));
            }

            String policyStr = platformProvider.getHugepageAllocationPolicy();
            AllocationPolicy policy = AllocationPolicy.fromString(policyStr);
            return OperationResult.success(policy);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Set allocation policy
     */
    public OperationResult<Void> setAllocationPolicy(AllocationPolicy policy) {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setAllocationPolicy",
                        "Hugepage control not available"));
            }

            validateParameter(policy, "policy");

            int result = platformProvider.setHugepageAllocationPolicy(policy.getKernelValue());
            if (result != 0) {
                throw createExceptionForErrorCode(result, "setAllocationPolicy");
            }

            logger.info("Set hugepage allocation policy to: {}", policy);
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Check if defragmentation is enabled
     */
    public OperationResult<Boolean> isDefragmentationEnabled() {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("isDefragmentationEnabled",
                        "Hugepage control not available"));
            }

            boolean enabled = platformProvider.isHugepageDefragmentationEnabled();
            return OperationResult.success(enabled);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Enable or disable defragmentation
     */
    public OperationResult<Void> setDefragmentationEnabled(boolean enabled) {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setDefragmentationEnabled",
                        "Hugepage control not available"));
            }

            int result = platformProvider.setHugepageDefragmentationEnabled(enabled);
            if (result != 0) {
                throw createExceptionForErrorCode(result, "setDefragmentationEnabled");
            }

            logger.info("Set hugepage defragmentation to: {}", enabled);
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Get comprehensive hugepage information
     */
    public OperationResult<HugepageInfo> getHugepageInfo() {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getHugepageInfo",
                        "Hugepage control not available"));
            }

            HugepageMode currentMode = getCurrentMode().getValue();
            AllocationPolicy policy = getCurrentAllocationPolicy().getValue();
            boolean defragEnabled = isDefragmentationEnabled().getValue();

            long totalHugepages = platformProvider.getTotalHugepages();
            long freeHugepages = platformProvider.getFreeHugepages();
            long hugepageSize = platformProvider.getHugepageSize();

            HugepageInfo info = new HugepageInfo(currentMode, originalMode, policy,
                    totalHugepages, freeHugepages, hugepageSize, defragEnabled);

            return OperationResult.success(info);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Configure hugepages optimally for HFT workloads
     */
    public OperationResult<Void> configureForHFT() {
        try {
            checkInitialized();
            if (!available.get()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("configureForHFT",
                        "Hugepage control not available"));
            }

            logger.info("Configuring hugepages for HFT workloads");

            // Set mode to madvise for predictable behavior
            OperationResult<Void> modeResult = setMode(HugepageMode.MADVISE);
            if (!modeResult.isSuccess()) {
                return modeResult;
            }

            // Set allocation policy to immediate for low latency
            OperationResult<Void> policyResult = setAllocationPolicy(AllocationPolicy.IMMEDIATE);
            if (!policyResult.isSuccess()) {
                return policyResult;
            }

            // Disable defragmentation to avoid latency spikes
            OperationResult<Void> defragResult = setDefragmentationEnabled(false);
            if (!defragResult.isSuccess()) {
                return defragResult;
            }

            logger.info("HFT hugepage configuration completed successfully");
            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new OperationFailedException("operation", e.getMessage()));
        }
    }

    /**
     * Restore original hugepage settings
     */
    public OperationResult<Void> restoreOriginalSettings() {
        try {
            checkInitialized();
            if (!available.get() || originalMode == null) {
                return OperationResult.success(null);
            }

            logger.info("Restoring original hugepage settings");

            // Restore mode
            OperationResult<Void> modeResult = setMode(originalMode);
            if (!modeResult.isSuccess()) {
                logger.warn("Failed to restore hugepage mode: {}", modeResult.getError().getMessage());
            }

            // Restore policy
            OperationResult<Void> policyResult = setAllocationPolicy(originalPolicy);
            if (!policyResult.isSuccess()) {
                logger.warn("Failed to restore allocation policy: {}", policyResult.getError().getMessage());
            }

            // Restore defragmentation
            OperationResult<Void> defragResult = setDefragmentationEnabled(originalDefragEnabled);
            if (!defragResult.isSuccess()) {
                logger.warn("Failed to restore defragmentation setting: {}", defragResult.getError().getMessage());
            }

            logger.info("Original hugepage settings restored");
            return OperationResult.success(null);

        } catch (Exception e) {
            logger.error("Error restoring hugepage settings", e);
            return OperationResult.failure(new OperationFailedException("restoreOriginalSettings", e.getMessage()));
        }
    }

    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            logger.info("Shutting down HugepageManager...");

            // Restore original settings if configured to do so
            if (config.isRestoreHugepageSettingsOnShutdown()) {
                restoreOriginalSettings();
            }

            logger.info("HugepageManager shutdown complete");

        } catch (Exception e) {
            logger.error("Error during HugepageManager shutdown", e);
        } finally {
            initialized.set(false);
            available.set(false);
        }
    }

    // Utility methods

    private void checkInitialized() throws IllegalStateException {
        if (!initialized.get()) {
            throw new IllegalStateException("HugepageManager not initialized");
        }
    }

    private void validateParameter(Object param, String paramName) throws InvalidParameterException {
        if (!config.isParameterValidationEnabled()) {
            return;
        }

        if (param == null) {
            throw new InvalidParameterException("validate", paramName, "null");
        }
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation) {
        switch (errorCode) {
            case -1:
                return new InvalidParameterException(operation, "parameter", "Invalid parameter");
            case -2:
                return new PermissionDeniedException(operation, "Insufficient permissions");
            case -3:
                return new com.faster.affinity.exceptions.UnsupportedOperationException(operation, "Operation not supported");
            default:
                return new SystemCallException(operation, "system_call", errorCode);
        }
    }
}