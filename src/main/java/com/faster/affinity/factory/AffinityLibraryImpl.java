package com.faster.affinity.factory;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.core.CPUGovernorManager;
import com.faster.affinity.core.IRQManager;
import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.topology.TopologyDetector;
import com.faster.affinity.validation.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of the affinity library interface
 */
class AffinityLibraryImpl implements AffinityLibrary {
    private static final Logger logger = LoggerFactory.getLogger(AffinityLibraryImpl.class);

    private final AffinityManager affinityManager;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public AffinityLibraryImpl(AffinityConfig config) throws ConfigurationException {
        try {
            logger.info("Creating AffinityLibrary with config: {}", config);
            this.affinityManager = AffinityManager.getInstance(config);

            // Verify that the AffinityManager is actually initialized
            if (!affinityManager.isInitialized()) {
                throw new ConfigurationException("AffinityLibraryImpl",
                    "AffinityManager was created but not properly initialized");
            }

            this.initialized.set(true);
            logger.info("AffinityLibrary initialized successfully");

        } catch (ConfigurationException e) {
            logger.error("Failed to create AffinityLibrary due to configuration error: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create AffinityLibrary due to unexpected error: {}", e.getMessage(), e);
            throw new ConfigurationException("AffinityLibraryImpl",
                "Unexpected error during initialization: " + e.getMessage(), e);
        }
    }

    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("AffinityLibrary has been shut down");
        }
    }

    @Override
    public OperationResult<Void> setCurrentThreadAffinity(java.util.BitSet cpuMask) {
        checkInitialized();
        return affinityManager.setThreadAffinity(getCurrentThreadId(), cpuMask);
    }

    @Override
    public OperationResult<Void> setCurrentThreadAffinity(int cpu) {
        checkInitialized();
        OperationResult<Void> invalid = validateSingleCpu("setCurrentThreadAffinity", cpu);
        if (invalid != null) {
            return invalid;
        }
        return setCurrentThreadAffinity(singleCpuMask(cpu));
    }

    @Override
    public OperationResult<java.util.BitSet> getCurrentThreadAffinity() {
        checkInitialized();
        return affinityManager.getThreadAffinity(getCurrentThreadId());
    }

    @Override
    public OperationResult<Void> setThreadAffinity(long threadId, java.util.BitSet cpuMask) {
        checkInitialized();
        return affinityManager.setThreadAffinity(threadId, cpuMask);
    }

    @Override
    public OperationResult<Void> setThreadAffinity(long threadId, int cpu) {
        checkInitialized();
        OperationResult<Void> invalid = validateSingleCpu("setThreadAffinity", cpu);
        if (invalid != null) {
            return invalid;
        }
        return setThreadAffinity(threadId, singleCpuMask(cpu));
    }

    private static java.util.BitSet singleCpuMask(int cpu) {
        java.util.BitSet cpuMask = new java.util.BitSet();
        cpuMask.set(cpu);
        return cpuMask;
    }

    private OperationResult<Void> validateSingleCpu(String operation, int cpu) {
        try {
            InputValidator.validateCpuIndex(cpu, operation);
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(new InvalidParameterException(operation, "cpu", cpu));
        }
        int detectedCpus = affinityManager.getSystemCapabilities().getCpuCount();
        if (detectedCpus > 0 && cpu >= detectedCpus) {
            return OperationResult.failure(new InvalidParameterException(operation, "cpu",
                    cpu + " (detected CPU count is " + detectedCpus + ")"));
        }
        return null;
    }

    @Override
    public OperationResult<java.util.BitSet> getThreadAffinity(long threadId) {
        checkInitialized();
        return affinityManager.getThreadAffinity(threadId);
    }

    @Override
    public OperationResult<Void> setProcessAffinity(int processId, java.util.BitSet cpuMask) {
        checkInitialized();
        return affinityManager.setProcessAffinity(processId, cpuMask);
    }

    @Override
    public OperationResult<java.util.BitSet> getProcessAffinity(int processId) {
        checkInitialized();
        return affinityManager.getProcessAffinity(processId);
    }

    @Override
    public long getCurrentThreadId() {
        checkInitialized();
        return affinityManager.getCurrentThreadId();
    }

    @Override
    public int getCurrentProcessId() {
        checkInitialized();
        return affinityManager.getCurrentProcessId();
    }

    @Override
    public AffinityManager.SystemCapabilities getSystemCapabilities() {
        checkInitialized();
        return affinityManager.getSystemCapabilities();
    }

    @Override
    public TopologyDetector.SystemTopology getSystemTopology() {
        checkInitialized();
        return affinityManager.getTopologyDetector().getTopology();
    }

    @Override
    public OperationResult<TopologyDetector.CoreInfo> getCoreInfo(int coreId) {
        checkInitialized();
        return affinityManager.getTopologyDetector().getCoreInfo(coreId);
    }

    @Override
    public OperationResult<java.util.List<TopologyDetector.CoreInfo>> getAllCoreInfo() {
        checkInitialized();
        return affinityManager.getTopologyDetector().getAllCoreInfo();
    }

    @Override
    public OperationResult<java.util.BitSet> getCacheLevelCores(int coreId, int cacheLevel) {
        checkInitialized();
        return affinityManager.getTopologyDetector().getCacheLevelCores(coreId, cacheLevel);
    }

    @Override
    public boolean isHyperThreadedCore(int coreId) {
        checkInitialized();
        return affinityManager.getTopologyDetector().isHyperThreadedCore(coreId);
    }

    @Override
    public OperationResult<Double> getCoreUtilization(int coreId) {
        checkInitialized();
        try {
            return affinityManager.getPerformanceMonitor().getCoreUtilization(coreId);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<PerformanceMonitor.CorePerformanceSnapshot> getCorePerformanceSnapshot(int coreId) {
        checkInitialized();
        try {
            return affinityManager.getPerformanceMonitor().getCorePerformanceSnapshot(coreId);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> getSystemPerformanceSnapshot() {
        checkInitialized();
        try {
            return affinityManager.getPerformanceMonitor().getSystemPerformanceSnapshot();
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<java.util.List<Integer>> getHighUtilizationCores(double threshold) {
        checkInitialized();
        try {
            return affinityManager.getPerformanceMonitor().getHighUtilizationCores(threshold);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public NUMAManager getNUMAManager() {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager();
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            throw new IllegalStateException("NUMA operations are not available: " + e.getMessage(), e);
        }
    }

    @Override
    public OperationResult<java.util.BitSet> getNumaNodeCpus(int nodeId) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().getNodeCpus(nodeId);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<NUMAManager.NumaNodeMemoryInfo> getNumaNodeMemoryInfo(int nodeId) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().getNodeMemoryInfo(nodeId);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Long> getNumaNodeDistance(int node1, int node2) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().getNodeDistance(node1, node2);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> setThreadNumaAffinity(long threadId, int nodeId) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().setThreadNumaAffinity(threadId, nodeId);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Long> allocateNumaMemory(int nodeId, long size) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().allocateMemory(nodeId, size);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> freeNumaMemory(long address) {
        checkInitialized();
        try {
            return affinityManager.getNUMAManager().freeMemory(address);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    // IRQ (Interrupt Request) management methods

    @Override
    public OperationResult<java.util.List<IRQManager.IRQInfo>> getAllIRQs() {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().getAllIRQs();
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<IRQManager.IRQInfo> getIRQInfo(int irqNumber) {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().getIRQInfo(irqNumber);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<java.util.BitSet> getIRQAffinity(int irqNumber) {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().getIRQAffinity(irqNumber);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> setIRQAffinity(int irqNumber, java.util.BitSet cpuMask) {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().setIRQAffinity(irqNumber, cpuMask);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> setDefaultIRQAffinity(java.util.BitSet housekeepingCores) {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().setDefaultIRQAffinity(housekeepingCores);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> isolateIRQsFromCores(java.util.BitSet tradingCores) {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().isolateIRQsFromCores(tradingCores);
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<IRQManager.IRQIsolationStatus> getIRQIsolationStatus() {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().getIRQIsolationStatus();
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    @Override
    public OperationResult<Void> restoreOriginalIRQAffinities() {
        checkInitialized();
        try {
            return affinityManager.getIRQManager().restoreOriginalIRQAffinities();
        } catch (com.faster.affinity.exceptions.UnsupportedOperationException e) {
            return OperationResult.failure(e);
        }
    }

    // CPU Governor control (HFT performance optimization) methods

    @Override
    public OperationResult<CPUGovernorManager.GovernorMode> getCurrentGovernor(int coreId) {
        checkInitialized();
        return affinityManager.getCurrentGovernor(coreId);
    }

    @Override
    public OperationResult<Void> setGovernor(int coreId, CPUGovernorManager.GovernorMode governor) {
        checkInitialized();
        return affinityManager.setGovernor(coreId, governor);
    }

    @Override
    public OperationResult<Void> setAllCoresGovernor(CPUGovernorManager.GovernorMode governor) {
        checkInitialized();
        return affinityManager.setAllCoresGovernor(governor);
    }

    @Override
    public OperationResult<CPUGovernorManager.GovernorStatus> getGovernorStatus() {
        checkInitialized();
        return affinityManager.getGovernorStatus();
    }

    @Override
    public OperationResult<Void> restoreOriginalGovernors() {
        checkInitialized();
        return affinityManager.restoreOriginalGovernors();
    }

    @Override
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            try {
                affinityManager.shutdown();
                logger.info("AffinityLibrary shutdown complete");
            } catch (Exception e) {
                logger.error("Error during AffinityLibrary shutdown: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }
}