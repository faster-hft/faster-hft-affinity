package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.faster.affinity.exceptions.ErrorCodes.*;

/**
 * IRQ (Interrupt Request) management for isolating interrupts from trading cores.
 * Critical for HFT systems requiring deterministic latency.
 */
public final class IRQManager {
    private static final Logger logger = LoggerFactory.getLogger(IRQManager.class);

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // IRQ state tracking
    private final ConcurrentHashMap<Integer, IRQInfo> irqCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BitSet> originalAffinities = new ConcurrentHashMap<>();
    private volatile long lastScanTime = 0;

    // IRQ classification patterns
    private static final Map<String, IRQType> IRQ_PATTERNS = new HashMap<>();
    static {
        // Network IRQs
        IRQ_PATTERNS.put("eth", IRQType.NETWORK);
        IRQ_PATTERNS.put("ens", IRQType.NETWORK);
        IRQ_PATTERNS.put("enp", IRQType.NETWORK);
        IRQ_PATTERNS.put("mlx", IRQType.NETWORK);
        IRQ_PATTERNS.put("i40e", IRQType.NETWORK);
        IRQ_PATTERNS.put("ixgbe", IRQType.NETWORK);

        // Storage IRQs
        IRQ_PATTERNS.put("nvme", IRQType.STORAGE);
        IRQ_PATTERNS.put("ahci", IRQType.STORAGE);
        IRQ_PATTERNS.put("sata", IRQType.STORAGE);

        // Timer IRQs
        IRQ_PATTERNS.put("timer", IRQType.TIMER);
        IRQ_PATTERNS.put("hpet", IRQType.TIMER);
        IRQ_PATTERNS.put("lapic", IRQType.TIMER);

        // USB/HID IRQs
        IRQ_PATTERNS.put("usb", IRQType.USB);
        IRQ_PATTERNS.put("hid", IRQType.USB);

        // Graphics IRQs
        IRQ_PATTERNS.put("nvidia", IRQType.GRAPHICS);
        IRQ_PATTERNS.put("amd", IRQType.GRAPHICS);
        IRQ_PATTERNS.put("intel_gfx", IRQType.GRAPHICS);
    }

    public IRQManager(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
    }

    public void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        try {
            logger.info("Initializing IRQManager...");

            // Check if IRQ management is supported and enabled
            if (!config.isIRQManagementEnabled()) {
                logger.info("IRQ management disabled in configuration");
                initialized.set(true);
                return;
            }

            if (!platformProvider.supportsFeature("irq_management")) {
                logger.warn("IRQ management not supported on this platform");
                initialized.set(true);
                return;
            }

            // Discover all IRQs
            discoverIRQs();

            initialized.set(true);
            logger.info("IRQManager initialized with {} IRQs", irqCache.size());

        } catch (Exception e) {
            throw new ConfigurationException("initialize", "Failed to initialize IRQManager: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return initialized.get() &&
               config.isIRQManagementEnabled() &&
               platformProvider.supportsFeature("irq_management");
    }

    // IRQ Discovery and Information

    public OperationResult<List<IRQInfo>> getAllIRQs() {
        try {
            checkInitialized();

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getAllIRQs",
                    "IRQ management not available"));
            }

            // Refresh IRQ list if needed
            refreshIRQsIfNeeded();

            List<IRQInfo> irqList = new ArrayList<>(irqCache.values());
            irqList.sort(Comparator.comparing(IRQInfo::getIrqNumber));

            return OperationResult.success(Collections.unmodifiableList(irqList));

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getAllIRQs", "irq_discovery", e));
        }
    }

    public OperationResult<IRQInfo> getIRQInfo(int irqNumber) {
        try {
            checkInitialized();
            validateIRQNumber(irqNumber);

            IRQInfo info = irqCache.get(irqNumber);
            if (info == null) {
                return OperationResult.failure(new InvalidParameterException("getIRQInfo",
                    "IRQ not found", irqNumber));
            }

            return OperationResult.success(info);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<BitSet> getIRQAffinity(int irqNumber) {
        try {
            checkInitialized();
            validateIRQNumber(irqNumber);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getIRQAffinity",
                    "IRQ management not available"));
            }

            long[] maskArray = new long[16];
            int result = platformProvider.getIrqAffinity(irqNumber, maskArray, platformProvider.getCpuCount());

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getIRQAffinity");
            }

            BitSet affinity = AffinityManager.longArrayToBitSet(maskArray, platformProvider.getCpuCount());
            return OperationResult.success(affinity);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // IRQ Affinity Control

    public OperationResult<Void> setIRQAffinity(int irqNumber, BitSet cpuMask) {
        try {
            checkInitialized();
            validateIRQNumber(irqNumber);
            validateCpuMask(cpuMask);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setIRQAffinity",
                    "IRQ management not available"));
            }

            // Store original affinity if not already stored
            if (!originalAffinities.containsKey(irqNumber)) {
                OperationResult<BitSet> originalResult = getIRQAffinity(irqNumber);
                if (originalResult.isSuccess()) {
                    originalAffinities.put(irqNumber, originalResult.getValue());
                }
            }

            long[] maskArray = AffinityManager.bitSetToLongArray(cpuMask);
            int result = platformProvider.setIrqAffinity(irqNumber, maskArray, maskArray.length);

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "setIRQAffinity");
            }

            // Update cached info
            IRQInfo info = irqCache.get(irqNumber);
            if (info != null) {
                IRQInfo updatedInfo = new IRQInfo(info.getIrqNumber(), info.getDescription(),
                    cpuMask, info.getType(), info.getDevice());
                irqCache.put(irqNumber, updatedInfo);
            }

            logger.debug("Set IRQ {} affinity to cores: {}", irqNumber, formatCpuMask(cpuMask));
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Void> setDefaultIRQAffinity(BitSet housekeepingCores) {
        try {
            checkInitialized();
            validateCpuMask(housekeepingCores);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("setDefaultIRQAffinity",
                    "IRQ management not available"));
            }

            long[] maskArray = AffinityManager.bitSetToLongArray(housekeepingCores);
            int result = platformProvider.setDefaultIrqAffinity(maskArray, maskArray.length);

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "setDefaultIRQAffinity");
            }

            logger.info("Set default IRQ affinity to housekeeping cores: {}", formatCpuMask(housekeepingCores));
            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // High-Level IRQ Isolation

    public OperationResult<Void> isolateIRQsFromCores(BitSet tradingCores) {
        try {
            checkInitialized();
            validateCpuMask(tradingCores);

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("isolateIRQsFromCores",
                    "IRQ management not available"));
            }

            // Calculate housekeeping cores (all cores except trading cores)
            BitSet allCores = new BitSet();
            allCores.set(0, platformProvider.getCpuCount());
            allCores.andNot(tradingCores);

            if (allCores.isEmpty()) {
                return OperationResult.failure(new InvalidParameterException("isolateIRQsFromCores",
                    "No housekeeping cores available"));
            }

            List<IRQInfo> allIRQs = getAllIRQs().getValue();
            List<Integer> failedIRQs = new ArrayList<>();
            List<Integer> succeededIRQs = new ArrayList<>();

            // Move each IRQ to housekeeping cores
            for (IRQInfo irq : allIRQs) {
                // Skip certain critical IRQs that might need special handling
                if (shouldSkipIRQ(irq)) {
                    logger.debug("Skipping IRQ {} ({}): {}", irq.getIrqNumber(), irq.getType(), irq.getDescription());
                    continue;
                }

                OperationResult<Void> result = setIRQAffinity(irq.getIrqNumber(), allCores);
                if (result.isSuccess()) {
                    succeededIRQs.add(irq.getIrqNumber());
                } else {
                    failedIRQs.add(irq.getIrqNumber());
                    logger.warn("Failed to isolate IRQ {}: {}", irq.getIrqNumber(), result.getError().getMessage());
                }
            }

            logger.info("IRQ isolation complete: {} succeeded, {} failed. Trading cores {} isolated.",
                succeededIRQs.size(), failedIRQs.size(), formatCpuMask(tradingCores));

            if (!failedIRQs.isEmpty() && config.isStrictIRQIsolation()) {
                return OperationResult.failure(new SystemCallException("isolateIRQsFromCores",
                    "irq_isolation", null));
            }

            return OperationResult.success(null);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<IRQIsolationStatus> getIRQIsolationStatus() {
        try {
            checkInitialized();

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getIRQIsolationStatus",
                    "IRQ management not available"));
            }

            List<IRQInfo> allIRQs = getAllIRQs().getValue();
            Map<IRQType, List<IRQInfo>> irqsByType = allIRQs.stream()
                .collect(Collectors.groupingBy(IRQInfo::getType));

            Set<Integer> isolatedCores = new HashSet<>();
            Set<Integer> nonIsolatedCores = new HashSet<>();
            List<IRQConflict> conflicts = new ArrayList<>();

            // Analyze each IRQ's current affinity
            for (IRQInfo irq : allIRQs) {
                OperationResult<BitSet> affinityResult = getIRQAffinity(irq.getIrqNumber());
                if (affinityResult.isSuccess()) {
                    BitSet affinity = affinityResult.getValue();
                    for (int core = affinity.nextSetBit(0); core >= 0; core = affinity.nextSetBit(core + 1)) {
                        if (affinity.cardinality() == 1) {
                            isolatedCores.add(core);
                        } else {
                            nonIsolatedCores.add(core);
                        }
                    }

                    // Check for conflicts (high-priority IRQs on multiple cores)
                    if (isHighPriorityIRQ(irq) && affinity.cardinality() > 1) {
                        conflicts.add(new IRQConflict(irq.getIrqNumber(), irq.getDescription(),
                            IRQConflict.ConflictType.SHARED_HIGH_PRIORITY, affinity));
                    }
                }
            }

            IRQIsolationStatus status = new IRQIsolationStatus(
                allIRQs.size(),
                irqsByType,
                isolatedCores,
                nonIsolatedCores,
                conflicts
            );

            return OperationResult.success(status);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getIRQIsolationStatus", "irq_status", e));
        }
    }

    // IRQ Restoration

    public OperationResult<Void> restoreOriginalIRQAffinities() {
        try {
            checkInitialized();

            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("restoreOriginalIRQAffinities",
                    "IRQ management not available"));
            }

            List<Integer> failedRestores = new ArrayList<>();
            int restoredCount = 0;

            for (Map.Entry<Integer, BitSet> entry : originalAffinities.entrySet()) {
                int irqNumber = entry.getKey();
                BitSet originalAffinity = entry.getValue();

                OperationResult<Void> result = setIRQAffinity(irqNumber, originalAffinity);
                if (result.isSuccess()) {
                    restoredCount++;
                } else {
                    failedRestores.add(irqNumber);
                    logger.warn("Failed to restore IRQ {} to original affinity: {}",
                        irqNumber, result.getError().getMessage());
                }
            }

            logger.info("Restored {} IRQ affinities, {} failed", restoredCount, failedRestores.size());

            if (!failedRestores.isEmpty()) {
                return OperationResult.failure(new SystemCallException("restoreOriginalIRQAffinities",
                    "irq_restore", null));
            }

            return OperationResult.success(null);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("restoreOriginalIRQAffinities", "irq_restore", e));
        }
    }

    // Private helper methods

    private OperationResult<BitSet> getIRQAffinityDirect(int irqNumber) {
        try {
            if (!isAvailable()) {
                return OperationResult.failure(new com.faster.affinity.exceptions.UnsupportedOperationException("getIRQAffinityDirect",
                    "IRQ management not available"));
            }

            long[] maskArray = new long[16];
            int result = platformProvider.getIrqAffinity(irqNumber, maskArray, platformProvider.getCpuCount());

            if (result != SUCCESS) {
                throw createExceptionForErrorCode(result, "getIRQAffinityDirect");
            }

            BitSet affinity = AffinityManager.longArrayToBitSet(maskArray, platformProvider.getCpuCount());
            return OperationResult.success(affinity);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getIRQAffinityDirect", "irq_affinity", e));
        }
    }

    private void discoverIRQs() {
        try {
            int[] irqNumbers = platformProvider.getAllIrqNumbers();
            irqCache.clear();

            for (int irqNumber : irqNumbers) {
                try {
                    String description = platformProvider.getIrqDescription(irqNumber);
                    OperationResult<BitSet> affinityResult = getIRQAffinityDirect(irqNumber);
                    BitSet affinity = affinityResult.isSuccess() ? affinityResult.getValue() : new BitSet();

                    IRQType type = classifyIRQ(description);
                    String device = extractDevice(description);

                    IRQInfo info = new IRQInfo(irqNumber, description, affinity, type, device);
                    irqCache.put(irqNumber, info);

                } catch (Exception e) {
                    logger.debug("Failed to get info for IRQ {}: {}", irqNumber, e.getMessage());
                }
            }

            lastScanTime = System.currentTimeMillis();
            logger.debug("Discovered {} IRQs", irqCache.size());

        } catch (Exception e) {
            logger.warn("Failed to discover IRQs: {}", e.getMessage());
        }
    }

    private void refreshIRQsIfNeeded() {
        long scanInterval = config.getIRQScanInterval();
        if (scanInterval > 0 && System.currentTimeMillis() - lastScanTime > scanInterval) {
            discoverIRQs();
        }
    }

    private IRQType classifyIRQ(String description) {
        if (description == null) {
            return IRQType.UNKNOWN;
        }

        String lowerDesc = description.toLowerCase();
        for (Map.Entry<String, IRQType> entry : IRQ_PATTERNS.entrySet()) {
            if (lowerDesc.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return IRQType.UNKNOWN;
    }

    private String extractDevice(String description) {
        if (description == null) {
            return "unknown";
        }

        // Extract device name from common IRQ description patterns
        if (description.contains(":")) {
            String[] parts = description.split(":");
            return parts[parts.length - 1].trim();
        }

        return description.trim();
    }

    private boolean shouldSkipIRQ(IRQInfo irq) {
        // Skip certain critical system IRQs that might cause issues if moved
        switch (irq.getType()) {
            case TIMER:
                // Some timer IRQs are critical and shouldn't be moved
                return irq.getDescription().contains("lapic") || irq.getDescription().contains("hpet");
            case UNKNOWN:
                // Be conservative with unknown IRQs
                return true;
            default:
                return false;
        }
    }

    private boolean isHighPriorityIRQ(IRQInfo irq) {
        return irq.getType() == IRQType.NETWORK || irq.getType() == IRQType.STORAGE;
    }

    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("IRQManager not initialized");
        }
    }

    private void validateIRQNumber(int irqNumber) throws InvalidParameterException {
        if (irqNumber < 0) {
            throw new InvalidParameterException("validateIRQNumber", "irqNumber", irqNumber);
        }
    }

    private void validateCpuMask(BitSet cpuMask) throws InvalidParameterException {
        if (cpuMask == null || cpuMask.isEmpty()) {
            throw new InvalidParameterException("validateCpuMask", "cpuMask", "empty or null");
        }

        int maxCore = cpuMask.length() - 1;
        int systemCores = platformProvider.getCpuCount();
        if (maxCore >= systemCores) {
            throw new InvalidParameterException("validateCpuMask",
                String.format("Core %d exceeds system core count %d", maxCore, systemCores));
        }
    }

    private AffinityException createExceptionForErrorCode(int errorCode, String operation) {
        switch (errorCode) {
            case ERROR_NOT_SUPPORTED:
                return new com.faster.affinity.exceptions.UnsupportedOperationException(operation, "IRQ operation not supported");
            case ERROR_PERMISSION_DENIED:
                return new PermissionDeniedException(operation, "IRQ operations require elevated privileges", null);
            case ERROR_INVALID_PARAMETER:
                return new InvalidParameterException(operation, "Invalid IRQ parameter");
            default:
                return new SystemCallException(operation, "irq_operation", null);
        }
    }

    private String formatCpuMask(BitSet cpuMask) {
        List<Integer> cores = new ArrayList<>();
        for (int core = cpuMask.nextSetBit(0); core >= 0; core = cpuMask.nextSetBit(core + 1)) {
            cores.add(core);
        }
        return cores.toString();
    }

    public void shutdown() {
        logger.info("Shutting down IRQManager...");

        // Optionally restore original IRQ affinities
        if (config.isRestoreIRQAffinitiesOnShutdown() && !originalAffinities.isEmpty()) {
            logger.info("Restoring original IRQ affinities...");
            restoreOriginalIRQAffinities();
        }

        irqCache.clear();
        originalAffinities.clear();
        initialized.set(false);

        logger.info("IRQManager shutdown complete");
    }

    // Enums and Data Classes

    public enum IRQType {
        NETWORK,
        STORAGE,
        TIMER,
        USB,
        GRAPHICS,
        SOUND,
        UNKNOWN
    }

    public static class IRQInfo {
        private final int irqNumber;
        private final String description;
        private final BitSet currentAffinity;
        private final IRQType type;
        private final String device;

        public IRQInfo(int irqNumber, String description, BitSet currentAffinity, IRQType type, String device) {
            this.irqNumber = irqNumber;
            this.description = description;
            this.currentAffinity = (BitSet) currentAffinity.clone();
            this.type = type;
            this.device = device;
        }

        public int getIrqNumber() { return irqNumber; }
        public String getDescription() { return description; }
        public BitSet getCurrentAffinity() { return (BitSet) currentAffinity.clone(); }
        public IRQType getType() { return type; }
        public String getDevice() { return device; }

        @Override
        public String toString() {
            return String.format("IRQ{num=%d, type=%s, device='%s', affinity=%s}",
                irqNumber, type, device, currentAffinity);
        }
    }

    public static class IRQIsolationStatus {
        private final int totalIRQs;
        private final Map<IRQType, List<IRQInfo>> irqsByType;
        private final Set<Integer> isolatedCores;
        private final Set<Integer> nonIsolatedCores;
        private final List<IRQConflict> conflicts;

        public IRQIsolationStatus(int totalIRQs, Map<IRQType, List<IRQInfo>> irqsByType,
                                Set<Integer> isolatedCores, Set<Integer> nonIsolatedCores,
                                List<IRQConflict> conflicts) {
            this.totalIRQs = totalIRQs;
            this.irqsByType = new HashMap<>(irqsByType);
            this.isolatedCores = new HashSet<>(isolatedCores);
            this.nonIsolatedCores = new HashSet<>(nonIsolatedCores);
            this.conflicts = new ArrayList<>(conflicts);
        }

        public int getTotalIRQs() { return totalIRQs; }
        public Map<IRQType, List<IRQInfo>> getIrqsByType() { return irqsByType; }
        public Set<Integer> getIsolatedCores() { return isolatedCores; }
        public Set<Integer> getNonIsolatedCores() { return nonIsolatedCores; }
        public List<IRQConflict> getConflicts() { return conflicts; }
        public boolean hasConflicts() { return !conflicts.isEmpty(); }

        @Override
        public String toString() {
            return String.format("IRQStatus{total=%d, isolated=%d cores, conflicts=%d}",
                totalIRQs, isolatedCores.size(), conflicts.size());
        }
    }

    public static class IRQConflict {
        public enum ConflictType {
            SHARED_HIGH_PRIORITY,
            OVERLAPPING_AFFINITY,
            MISSING_ISOLATION
        }

        private final int irqNumber;
        private final String description;
        private final ConflictType type;
        private final BitSet affectedCores;

        public IRQConflict(int irqNumber, String description, ConflictType type, BitSet affectedCores) {
            this.irqNumber = irqNumber;
            this.description = description;
            this.type = type;
            this.affectedCores = (BitSet) affectedCores.clone();
        }

        public int getIrqNumber() { return irqNumber; }
        public String getDescription() { return description; }
        public ConflictType getType() { return type; }
        public BitSet getAffectedCores() { return (BitSet) affectedCores.clone(); }

        @Override
        public String toString() {
            return String.format("IRQConflict{irq=%d, type=%s, cores=%s}",
                irqNumber, type, affectedCores);
        }
    }
}