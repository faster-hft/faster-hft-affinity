package com.faster.affinity.factory;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.topology.TopologyDetector;
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
        this.affinityManager = AffinityManager.getInstance(config);
        this.initialized.set(true);
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