package com.faster.affinity.factory;

import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.core.CPUGovernorManager;
import com.faster.affinity.core.IRQManager;
import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.topology.TopologyDetector;

/**
 * Main affinity library interface
 */
public interface AffinityLibrary {

    // Basic thread/process affinity
    OperationResult<Void> setCurrentThreadAffinity(java.util.BitSet cpuMask);
    OperationResult<java.util.BitSet> getCurrentThreadAffinity();
    OperationResult<Void> setThreadAffinity(long threadId, java.util.BitSet cpuMask);
    OperationResult<java.util.BitSet> getThreadAffinity(long threadId);
    OperationResult<Void> setProcessAffinity(int processId, java.util.BitSet cpuMask);
    OperationResult<java.util.BitSet> getProcessAffinity(int processId);

    // System information
    long getCurrentThreadId();
    int getCurrentProcessId();
    AffinityManager.SystemCapabilities getSystemCapabilities();

    // Topology discovery
    TopologyDetector.SystemTopology getSystemTopology();
    OperationResult<TopologyDetector.CoreInfo> getCoreInfo(int coreId);
    OperationResult<java.util.List<TopologyDetector.CoreInfo>> getAllCoreInfo();
    OperationResult<java.util.BitSet> getCacheLevelCores(int coreId, int cacheLevel);
    boolean isHyperThreadedCore(int coreId);

    // Performance monitoring
    OperationResult<Double> getCoreUtilization(int coreId);
    OperationResult<PerformanceMonitor.CorePerformanceSnapshot> getCorePerformanceSnapshot(int coreId);
    OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> getSystemPerformanceSnapshot();
    OperationResult<java.util.List<Integer>> getHighUtilizationCores(double threshold);

    // NUMA operations
    OperationResult<java.util.BitSet> getNumaNodeCpus(int nodeId);
    OperationResult<NUMAManager.NumaNodeMemoryInfo> getNumaNodeMemoryInfo(int nodeId);
    OperationResult<Long> getNumaNodeDistance(int node1, int node2);
    OperationResult<Void> setThreadNumaAffinity(long threadId, int nodeId);
    OperationResult<Long> allocateNumaMemory(int nodeId, long size);
    OperationResult<Void> freeNumaMemory(long address);

    // IRQ (Interrupt Request) management
    OperationResult<java.util.List<IRQManager.IRQInfo>> getAllIRQs();
    OperationResult<IRQManager.IRQInfo> getIRQInfo(int irqNumber);
    OperationResult<java.util.BitSet> getIRQAffinity(int irqNumber);
    OperationResult<Void> setIRQAffinity(int irqNumber, java.util.BitSet cpuMask);
    OperationResult<Void> setDefaultIRQAffinity(java.util.BitSet housekeepingCores);
    OperationResult<Void> isolateIRQsFromCores(java.util.BitSet tradingCores);
    OperationResult<IRQManager.IRQIsolationStatus> getIRQIsolationStatus();
    OperationResult<Void> restoreOriginalIRQAffinities();

    // CPU Governor control (HFT performance optimization)
    OperationResult<CPUGovernorManager.GovernorMode> getCurrentGovernor(int coreId);
    OperationResult<Void> setGovernor(int coreId, CPUGovernorManager.GovernorMode governor);
    OperationResult<Void> setAllCoresGovernor(CPUGovernorManager.GovernorMode governor);
    OperationResult<CPUGovernorManager.GovernorStatus> getGovernorStatus();
    OperationResult<Void> restoreOriginalGovernors();

    // Lifecycle
    void shutdown();
    boolean isInitialized();
}