package com.faster.affinity.platform;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.CPUGovernorManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Mock platform provider for unsupported platforms (e.g., macOS).
 * Used in developer/testing mode to allow graceful degradation.
 */
public class MockPlatformProvider implements PlatformProvider {
    private static final Logger logger = LoggerFactory.getLogger(MockPlatformProvider.class);

    private final AffinityConfig config;
    private final int mockCpuCount;
    private volatile boolean initialized = false;

    public MockPlatformProvider(AffinityConfig config) {
        this.config = config;
        this.mockCpuCount = Runtime.getRuntime().availableProcessors();
        logger.info("MockPlatformProvider created for unsupported platform with {} CPU cores", mockCpuCount);
    }

    @Override
    public void initialize() throws Exception {
        logger.info("Initializing MockPlatformProvider (no-op)");
        initialized = true;
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down MockPlatformProvider (no-op)");
        initialized = false;
    }

    // Core affinity operations - all return success with no actual operation
    @Override
    public int setThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        logger.debug("Mock setThreadAffinity: tid={}, mask={}", tid, Arrays.toString(cpuMask));
        return 0; // Success
    }

    @Override
    public int getThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        logger.debug("Mock getThreadAffinity: tid={}", tid);
        // Fill with all CPUs available
        Arrays.fill(cpuMask, 0, Math.min(maskLength, cpuMask.length), (1L << mockCpuCount) - 1);
        return 0;
    }

    @Override
    public int setProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        logger.debug("Mock setProcessAffinity: pid={}, mask={}", pid, Arrays.toString(cpuMask));
        return 0;
    }

    @Override
    public int getProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        logger.debug("Mock getProcessAffinity: pid={}", pid);
        Arrays.fill(cpuMask, 0, Math.min(maskLength, cpuMask.length), (1L << mockCpuCount) - 1);
        return 0;
    }

    // System information
    @Override
    public long getCurrentThreadId() {
        return Thread.currentThread().getId();
    }

    @Override
    public int getCurrentProcessId() {
        return (int) ProcessHandle.current().pid();
    }

    @Override
    public String getPlatformInfo() {
        return "Mock Platform Provider - " + System.getProperty("os.name") +
               " (unsupported platform in developer mode)";
    }

    @Override
    public String[] getSupportedFeatures() {
        return new String[]{"basic_mock_functionality"};
    }

    @Override
    public boolean isRealtimeSupported() {
        return false;
    }

    // Topology discovery
    @Override
    public int getCpuCount() {
        return mockCpuCount;
    }

    @Override
    public int getSocketCount() {
        return 1; // Mock single socket
    }

    @Override
    public int getCoresPerSocket() {
        return mockCpuCount;
    }

    @Override
    public int getMaxCacheLevel() {
        return 3; // Mock L1, L2, L3
    }

    @Override
    public long getCacheSize(int cacheLevel) {
        switch (cacheLevel) {
            case 1: return 32 * 1024; // 32KB L1
            case 2: return 256 * 1024; // 256KB L2
            case 3: return 8 * 1024 * 1024; // 8MB L3
            default: return 0;
        }
    }

    @Override
    public long getCacheLineSize() {
        return 64; // Common cache line size
    }

    @Override
    public int isHyperThreadedCore(int coreId) {
        return 0; // No hyperthreading in mock
    }

    @Override
    public int getCacheLevelCores(int coreId, int cacheLevel, long[] cpuMask, int maxCores) {
        Arrays.fill(cpuMask, 0, Math.min(maxCores, cpuMask.length), 1);
        return 1;
    }

    // NUMA operations - all mock single node
    @Override
    public int getNumaNodeCount() {
        return 1;
    }

    @Override
    public int getNumaNodeCpus(int nodeId, long[] cpuMask, int maxCores) {
        if (nodeId == 0) {
            Arrays.fill(cpuMask, 0, Math.min(maxCores, cpuMask.length), (1L << mockCpuCount) - 1);
            return mockCpuCount;
        }
        return 0;
    }

    @Override
    public int getNumaNodeMemoryInfo(int nodeId, long[] memoryInfo) {
        if (nodeId == 0 && memoryInfo.length > 0) {
            memoryInfo[0] = Runtime.getRuntime().maxMemory();
            return 0;
        }
        return -1;
    }

    @Override
    public long getNumaNodeDistance(int node1, int node2) {
        return node1 == node2 ? 10 : 20; // Mock NUMA distances
    }

    @Override
    public int setNumaAffinity(long tid, int nodeId) {
        logger.debug("Mock setNumaAffinity: tid={}, nodeId={}", tid, nodeId);
        return 0;
    }

    @Override
    public int setNumaNodeCoreAffinity(long tid, int nodeId, int coreId) {
        logger.debug("Mock setNumaNodeCoreAffinity: tid={}, nodeId={}, coreId={}", tid, nodeId, coreId);
        return 0;
    }

    @Override
    public long allocateNumaMemory(int nodeId, long size) {
        logger.debug("Mock allocateNumaMemory: nodeId={}, size={}", nodeId, size);
        return 0; // Mock allocation failure
    }

    @Override
    public int freeNumaMemory(long address, long size) {
        logger.debug("Mock freeNumaMemory: address={}, size={}", address, size);
        return 0;
    }

    @Override
    public int setNumaMemoryPolicy(int nodeId) {
        logger.debug("Mock setNumaMemoryPolicy: nodeId={}", nodeId);
        return 0;
    }

    @Override
    public int bindMemoryRange(long address, long size, int nodeId) {
        logger.debug("Mock bindMemoryRange: address={}, size={}, nodeId={}", address, size, nodeId);
        return 0;
    }

    // Performance monitoring - all return mock values
    @Override
    public double getCoreUtilization(int coreId) {
        return Math.random() * 100; // Random utilization
    }

    @Override
    public double getNumaNodeUtilization(int nodeId) {
        return Math.random() * 100;
    }

    @Override
    public long getThreadCacheMisses(long tid) {
        return (long) (Math.random() * 1000);
    }

    @Override
    public long getThreadContextSwitches(long tid) {
        return (long) (Math.random() * 100);
    }

    @Override
    public long getCoreCacheMisses(int coreId) {
        return (long) (Math.random() * 1000);
    }

    // IRQ management - not supported on mock
    @Override
    public int getIrqCount() {
        return 0;
    }

    @Override
    public int[] getAllIrqNumbers() {
        return new int[0];
    }

    @Override
    public String getIrqDescription(int irqNumber) {
        return "Mock IRQ " + irqNumber;
    }

    @Override
    public int getIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        return -1; // Not supported
    }

    @Override
    public int setIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        return -1; // Not supported
    }

    @Override
    public int setDefaultIrqAffinity(long[] cpuMask, int maskLength) {
        return -1; // Not supported
    }

    // CPU Governor Control - not supported on mock
    @Override
    public CPUGovernorManager.GovernorMode getCpuGovernor(int coreId) {
        return CPUGovernorManager.GovernorMode.UNKNOWN;
    }

    @Override
    public int setCpuGovernor(int coreId, CPUGovernorManager.GovernorMode governor) {
        return -1; // Not supported
    }

    @Override
    public List<CPUGovernorManager.GovernorMode> getAvailableGovernors(int coreId) {
        return Arrays.asList(CPUGovernorManager.GovernorMode.UNKNOWN);
    }

    @Override
    public long getCpuFrequency(int coreId) {
        return 2000000000L; // Mock 2GHz
    }

    @Override
    public long getCpuMinFrequency(int coreId) {
        return 1000000000L; // Mock 1GHz
    }

    @Override
    public long getCpuMaxFrequency(int coreId) {
        return 3000000000L; // Mock 3GHz
    }

    // Hugepage Control - not supported on mock
    @Override
    public String getHugepageMode() {
        return "never";
    }

    @Override
    public int setHugepageMode(String mode) {
        return -1; // Not supported
    }

    @Override
    public String getHugepageAllocationPolicy() {
        return "none";
    }

    @Override
    public int setHugepageAllocationPolicy(String policy) {
        return -1; // Not supported
    }

    @Override
    public boolean isHugepageDefragmentationEnabled() {
        return false;
    }

    @Override
    public int setHugepageDefragmentationEnabled(boolean enabled) {
        return -1; // Not supported
    }

    @Override
    public long getTotalHugepages() {
        return 0;
    }

    @Override
    public long getFreeHugepages() {
        return 0;
    }

    @Override
    public long getHugepageSize() {
        return 0;
    }

    // Memory Prefetching - not supported on mock
    @Override
    public int prefetchMemory(long address, int prefetchType) {
        return -1; // Not supported
    }

    @Override
    public int prefetchMemoryRange(long startAddress, long endAddress, int prefetchType, int stride) {
        return -1; // Not supported
    }

    @Override
    public boolean isMemoryAligned(long address, int alignment) {
        return (address % alignment) == 0;
    }

    @Override
    public long alignMemoryAddress(long address, int alignment) {
        return (address + alignment - 1) & ~(alignment - 1);
    }

    // Platform-specific capabilities
    @Override
    public boolean supportsFeature(String feature) {
        return "basic_mock_functionality".equals(feature);
    }
}