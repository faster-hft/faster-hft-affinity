package com.faster.affinity.performance;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production-ready performance monitoring with hardware counters,
 * CPU utilization tracking, and cache miss analysis.
 * Fixed version with proper thread safety and resource management.
 */
public final class PerformanceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitor.class);

    private final PlatformProvider platformProvider;
    private final AffinityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    // Background monitoring with proper shutdown
    private volatile ScheduledExecutorService monitoringExecutor;
    private final ConcurrentHashMap<String, PerformanceMetric> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CorePerformanceState> coreStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ThreadPerformanceState> threadStates = new ConcurrentHashMap<>();

    // Performance counter update interval
    private final int updateIntervalMs;

    public PerformanceMonitor(PlatformProvider platformProvider, AffinityConfig config) {
        this.platformProvider = platformProvider;
        this.config = config;
        this.updateIntervalMs = config.getPerformanceCounterUpdateIntervalMs();
    }

    public void initialize() throws ConfigurationException {
        if (initialized.get()) {
            return;
        }

        try {
            logger.info("Initializing PerformanceMonitor...");

            // Check if performance counters are available
            if (!isAvailable()) {
                throw new PerformanceMonitorException("initialize", "Performance counters not available on this platform");
            }

            // Initialize core states
            int cpuCount = platformProvider.getCpuCount();
            for (int coreId = 0; coreId < cpuCount; coreId++) {
                coreStates.put(coreId, new CorePerformanceState(coreId));
            }

            // Start background monitoring if enabled
            if (config.isPerformanceCountersEnabled()) {
                startBackgroundMonitoring();
            }

            initialized.set(true);
            logger.info("PerformanceMonitor initialized with {} cores", cpuCount);

        } catch (Exception e) {
            throw new ConfigurationException("initialize", "Failed to initialize PerformanceMonitor: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            // Test if we can get basic performance data
            double utilization = platformProvider.getCoreUtilization(0);
            return utilization >= 0.0;
        } catch (Exception e) {
            logger.debug("Performance counters not available: {}", e.getMessage());
            return false;
        }
    }

    public void startMonitoring() {
        if (!initialized.get()) {
            throw new IllegalStateException("PerformanceMonitor not initialized");
        }

        if (monitoring.compareAndSet(false, true)) {
            logger.info("Starting performance monitoring");
            if (monitoringExecutor == null) {
                startBackgroundMonitoring();
            }
        }
    }

    public void stopMonitoring() {
        if (monitoring.compareAndSet(true, false)) {
            logger.info("Stopping performance monitoring");
            shutdownExecutor();
        }
    }

    // Core Performance Monitoring

    public OperationResult<Double> getCoreUtilization(int coreId) {
        try {
            validateCoreId(coreId);

            CorePerformanceState state = coreStates.get(coreId);
            if (state != null && monitoring.get()) {
                // Return cached value if monitoring is active
                return OperationResult.success(state.getUtilization());
            }

            // Get fresh value from platform
            double utilization = platformProvider.getCoreUtilization(coreId);
            if (utilization < 0) {
                throw new PerformanceMonitorException("getCoreUtilization", "core_utilization");
            }

            return OperationResult.success(utilization);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Long> getCoreCacheMisses(int coreId) {
        try {
            validateCoreId(coreId);

            CorePerformanceState state = coreStates.get(coreId);
            if (state != null && monitoring.get()) {
                return OperationResult.success(state.getCacheMisses());
            }

            long cacheMisses = platformProvider.getCoreCacheMisses(coreId);
            if (cacheMisses < 0) {
                throw new PerformanceMonitorException("getCoreCacheMisses", "cache_misses");
            }

            return OperationResult.success(cacheMisses);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<CorePerformanceSnapshot> getCorePerformanceSnapshot(int coreId) {
        try {
            validateCoreId(coreId);

            long timestamp = System.currentTimeMillis();

            OperationResult<Double> utilizationResult = getCoreUtilization(coreId);
            if (!utilizationResult.isSuccess()) {
                return OperationResult.failure(utilizationResult.getError());
            }

            OperationResult<Long> cacheMissResult = getCoreCacheMisses(coreId);
            long cacheMisses = cacheMissResult.isSuccess() ? cacheMissResult.getValue() : 0;

            CorePerformanceSnapshot snapshot = new CorePerformanceSnapshot(
                    coreId, timestamp, utilizationResult.getValue(), cacheMisses);

            return OperationResult.success(snapshot);

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getCorePerformanceSnapshot", "performance_query", e));
        }
    }

    // Thread Performance Monitoring

    // Add this to PerformanceMonitor.java starting from line 173:

    public OperationResult<Long> getThreadCacheMisses(long threadId) {
        try {
            validateThreadId(threadId);

            ThreadPerformanceState state = threadStates.get(threadId);
            if (state != null && monitoring.get()) {
                return OperationResult.success(state.getCacheMisses());
            }

            long cacheMisses = platformProvider.getThreadCacheMisses(threadId);
            if (cacheMisses < 0) {
                throw new PerformanceMonitorException("getThreadCacheMisses", "cache_misses");
            }

            return OperationResult.success(cacheMisses);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    public OperationResult<Long> getThreadContextSwitches(long threadId) {
        try {
            validateThreadId(threadId);

            ThreadPerformanceState state = threadStates.get(threadId);
            if (state != null && monitoring.get()) {
                return OperationResult.success(state.getContextSwitches());
            }

            long contextSwitches = platformProvider.getThreadContextSwitches(threadId);
            if (contextSwitches < 0) {
                throw new PerformanceMonitorException("getThreadContextSwitches", "context_switches");
            }

            return OperationResult.success(contextSwitches);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // System-wide Performance Monitoring

    public OperationResult<SystemPerformanceSnapshot> getSystemPerformanceSnapshot() {
        try {
            checkInitialized();

            SystemPerformanceSnapshot.Builder builder = new SystemPerformanceSnapshot.Builder();
            builder.timestamp(System.currentTimeMillis());

            // Collect CPU utilization
            double totalUtilization = 0.0;
            int cpuCount = platformProvider.getCpuCount();
            List<CorePerformanceSnapshot> coreSnapshots = new ArrayList<>();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                OperationResult<CorePerformanceSnapshot> coreResult = getCorePerformanceSnapshot(coreId);
                if (coreResult.isSuccess()) {
                    CorePerformanceSnapshot snapshot = coreResult.getValue();
                    coreSnapshots.add(snapshot);
                    totalUtilization += snapshot.getUtilization();
                }
            }

            builder.avgCpuUtilization(cpuCount > 0 ? totalUtilization / cpuCount : 0.0)
                    .coreSnapshots(coreSnapshots);

            // Add metrics if available
            metrics.forEach((key, metric) -> builder.addMetric(key, metric.getValue()));

            return OperationResult.success(builder.build());

        } catch (Exception e) {
            return OperationResult.failure(new SystemCallException("getSystemPerformanceSnapshot", "performance_query", e));
        }
    }

    public OperationResult<List<Integer>> getHighUtilizationCores(double threshold) {
        try {
            checkInitialized();
            validateThreshold(threshold);

            List<Integer> highUtilizationCores = new ArrayList<>();
            int cpuCount = platformProvider.getCpuCount();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                OperationResult<Double> utilizationResult = getCoreUtilization(coreId);
                if (utilizationResult.isSuccess() && utilizationResult.getValue() > threshold) {
                    highUtilizationCores.add(coreId);
                }
            }

            return OperationResult.success(highUtilizationCores);

        } catch (AffinityException e) {
            return OperationResult.failure(e);
        }
    }

    // Background monitoring

    private void startBackgroundMonitoring() {
        if (monitoringExecutor == null || monitoringExecutor.isShutdown()) {
            monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PerformanceMonitor-Background");
                t.setDaemon(true);
                return t;
            });

            monitoringExecutor.scheduleAtFixedRate(this::updatePerformanceMetrics,
                    0, updateIntervalMs, TimeUnit.MILLISECONDS);

            logger.info("Started background performance monitoring with {}ms interval", updateIntervalMs);
        }
    }

    private void updatePerformanceMetrics() {
        if (shutdownRequested.get()) {
            return;
        }

        try {
            int cpuCount = platformProvider.getCpuCount();

            for (int coreId = 0; coreId < cpuCount; coreId++) {
                CorePerformanceState state = coreStates.get(coreId);
                if (state != null) {
                    state.updateUtilization(platformProvider.getCoreUtilization(coreId));
                    state.updateCacheMisses(platformProvider.getCoreCacheMisses(coreId));
                }
            }

            // Update thread states if monitoring specific threads
            for (ThreadPerformanceState state : threadStates.values()) {
                state.updateCacheMisses(platformProvider.getThreadCacheMisses(state.getThreadId()));
                state.updateContextSwitches(platformProvider.getThreadContextSwitches(state.getThreadId()));
            }

        } catch (Exception e) {
            logger.debug("Error updating performance metrics: {}", e.getMessage());
        }
    }

    private void shutdownExecutor() {
        if (monitoringExecutor != null) {
            try {
                monitoringExecutor.shutdown();
                if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                monitoringExecutor.shutdownNow();
            }
            monitoringExecutor = null;
        }
    }

    // Validation

    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("PerformanceMonitor not initialized");
        }
    }

    private void validateCoreId(int coreId) throws InvalidParameterException {
        if (coreId < 0 || coreId >= platformProvider.getCpuCount()) {
            throw new InvalidParameterException("validateCoreId", "coreId", coreId);
        }
    }

    private void validateThreadId(long threadId) throws InvalidParameterException {
        if (threadId <= 0) {
            throw new InvalidParameterException("validateThreadId", "threadId", threadId);
        }
    }

    private void validateThreshold(double threshold) throws InvalidParameterException {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new InvalidParameterException("validateThreshold", "threshold", threshold);
        }
    }

    // Cleanup

    public void shutdown() {
        logger.info("Shutting down PerformanceMonitor...");
        shutdownRequested.set(true);
        stopMonitoring();
        shutdownExecutor();
        coreStates.clear();
        threadStates.clear();
        metrics.clear();
        initialized.set(false);
        logger.info("PerformanceMonitor shutdown complete");
    }

    // Helper classes

    private static class CorePerformanceState {
        private final int coreId;
        private final AtomicReference<Double> utilization = new AtomicReference<>(0.0);
        private final AtomicLong cacheMisses = new AtomicLong(0);
        private final AtomicLong lastUpdateTime = new AtomicLong(0);

        public CorePerformanceState(int coreId) {
            this.coreId = coreId;
        }

        public int getCoreId() { return coreId; }
        public double getUtilization() { return utilization.get(); }
        public long getCacheMisses() { return cacheMisses.get(); }
        public long getLastUpdateTime() { return lastUpdateTime.get(); }

        public void updateUtilization(double value) {
            utilization.set(Math.max(0.0, Math.min(1.0, value)));
            lastUpdateTime.set(System.currentTimeMillis());
        }

        public void updateCacheMisses(long value) {
            if (value >= 0) {
                cacheMisses.set(value);
            }
        }
    }

    private static class ThreadPerformanceState {
        private final long threadId;
        private final AtomicLong cacheMisses = new AtomicLong(0);
        private final AtomicLong contextSwitches = new AtomicLong(0);
        private final AtomicLong lastUpdateTime = new AtomicLong(0);

        public ThreadPerformanceState(long threadId) {
            this.threadId = threadId;
        }

        public long getThreadId() { return threadId; }
        public long getCacheMisses() { return cacheMisses.get(); }
        public long getContextSwitches() { return contextSwitches.get(); }
        public long getLastUpdateTime() { return lastUpdateTime.get(); }

        public void updateCacheMisses(long value) {
            if (value >= 0) {
                cacheMisses.set(value);
                lastUpdateTime.set(System.currentTimeMillis());
            }
        }

        public void updateContextSwitches(long value) {
            if (value >= 0) {
                contextSwitches.set(value);
                lastUpdateTime.set(System.currentTimeMillis());
            }
        }
    }

    private static class PerformanceMetric {
        private final String name;
        private final AtomicReference<Double> value = new AtomicReference<>(0.0);
        private final AtomicLong lastUpdateTime = new AtomicLong(0);

        public PerformanceMetric(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public double getValue() { return value.get(); }
        public long getLastUpdateTime() { return lastUpdateTime.get(); }

        public void setValue(double newValue) {
            value.set(newValue);
            lastUpdateTime.set(System.currentTimeMillis());
        }
    }

    public static class CorePerformanceSnapshot {
        private final int coreId;
        private final long timestamp;
        private final double utilization;
        private final long cacheMisses;

        public CorePerformanceSnapshot(int coreId, long timestamp, double utilization, long cacheMisses) {
            this.coreId = coreId;
            this.timestamp = timestamp;
            this.utilization = utilization;
            this.cacheMisses = cacheMisses;
        }

        public int getCoreId() { return coreId; }
        public long getTimestamp() { return timestamp; }
        public double getUtilization() { return utilization; }
        public long getCacheMisses() { return cacheMisses; }

        @Override
        public String toString() {
            return String.format("Core%d{util=%.1f%%, misses=%d}",
                    coreId, utilization * 100, cacheMisses);
        }
    }

    public static class SystemPerformanceSnapshot {
        private final long timestamp;
        private final double avgCpuUtilization;
        private final List<CorePerformanceSnapshot> coreSnapshots;
        private final Map<String, Double> metrics;

        private SystemPerformanceSnapshot(Builder builder) {
            this.timestamp = builder.timestamp;
            this.avgCpuUtilization = builder.avgCpuUtilization;
            this.coreSnapshots = Collections.unmodifiableList(new ArrayList<>(builder.coreSnapshots));
            this.metrics = Collections.unmodifiableMap(new HashMap<>(builder.metrics));
        }

        public long getTimestamp() { return timestamp; }
        public double getAvgCpuUtilization() { return avgCpuUtilization; }
        public List<CorePerformanceSnapshot> getCoreSnapshots() { return coreSnapshots; }
        public Map<String, Double> getMetrics() { return metrics; }

        @Override
        public String toString() {
            return String.format("System{avgUtil=%.1f%%, cores=%d, metrics=%d}",
                    avgCpuUtilization * 100, coreSnapshots.size(), metrics.size());
        }

        public static class Builder {
            private long timestamp;
            private double avgCpuUtilization;
            private List<CorePerformanceSnapshot> coreSnapshots = new ArrayList<>();
            private Map<String, Double> metrics = new HashMap<>();

            public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
            public Builder avgCpuUtilization(double util) { this.avgCpuUtilization = util; return this; }
            public Builder coreSnapshots(List<CorePerformanceSnapshot> snapshots) {
                this.coreSnapshots = snapshots;
                return this;
            }
            public Builder addMetric(String name, double value) {
                this.metrics.put(name, value);
                return this;
            }

            public SystemPerformanceSnapshot build() {
                return new SystemPerformanceSnapshot(this);
            }
        }
    }
}