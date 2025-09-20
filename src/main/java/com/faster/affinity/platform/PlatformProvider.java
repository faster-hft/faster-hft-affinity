package com.faster.affinity.platform;

/**
 * Platform abstraction interface for CPU affinity and system operations.
 * Provides a unified interface across Windows, Linux, and other platforms.
 */
public interface PlatformProvider {

    // Core affinity operations
    int setThreadAffinity(long tid, long[] cpuMask, int maskLength);
    int getThreadAffinity(long tid, long[] cpuMask, int maskLength);
    int setProcessAffinity(int pid, long[] cpuMask, int maskLength);
    int getProcessAffinity(int pid, long[] cpuMask, int maskLength);

    // System information
    long getCurrentThreadId();
    int getCurrentProcessId();
    String getPlatformInfo();
    String[] getSupportedFeatures();
    boolean isRealtimeSupported();

    // Topology discovery
    int getCpuCount();
    int getSocketCount();
    int getCoresPerSocket();
    int getMaxCacheLevel();
    long getCacheSize(int cacheLevel);
    long getCacheLineSize();
    int isHyperThreadedCore(int coreId);
    int getCacheLevelCores(int coreId, int cacheLevel, long[] cpuMask, int maxCores);

    // NUMA operations
    int getNumaNodeCount();
    int getNumaNodeCpus(int nodeId, long[] cpuMask, int maxCores);
    int getNumaNodeMemoryInfo(int nodeId, long[] memoryInfo);
    long getNumaNodeDistance(int node1, int node2);
    int setNumaAffinity(long tid, int nodeId);
    int setNumaNodeCoreAffinity(long tid, int nodeId, int coreId);
    long allocateNumaMemory(int nodeId, long size);
    int freeNumaMemory(long address, long size);
    int setNumaMemoryPolicy(int nodeId);
    int bindMemoryRange(long address, long size, int nodeId);

    // Performance monitoring
    double getCoreUtilization(int coreId);
    double getNumaNodeUtilization(int nodeId);
    long getThreadCacheMisses(long tid);
    long getThreadContextSwitches(long tid);
    long getCoreCacheMisses(int coreId);

    // IRQ (Interrupt Request) management
    int getIrqCount();
    int[] getAllIrqNumbers();
    String getIrqDescription(int irqNumber);
    int getIrqAffinity(int irqNumber, long[] cpuMask, int maskLength);
    int setIrqAffinity(int irqNumber, long[] cpuMask, int maskLength);
    int setDefaultIrqAffinity(long[] cpuMask, int maskLength);

    // CPU Governor Control (for HFT performance optimization)
    com.faster.affinity.core.CPUGovernorManager.GovernorMode getCpuGovernor(int coreId);
    int setCpuGovernor(int coreId, com.faster.affinity.core.CPUGovernorManager.GovernorMode governor);
    java.util.List<com.faster.affinity.core.CPUGovernorManager.GovernorMode> getAvailableGovernors(int coreId);
    long getCpuFrequency(int coreId);
    long getCpuMinFrequency(int coreId);
    long getCpuMaxFrequency(int coreId);

    // Platform-specific capabilities
    boolean supportsFeature(String feature);
    void initialize() throws Exception;
    void shutdown();
}