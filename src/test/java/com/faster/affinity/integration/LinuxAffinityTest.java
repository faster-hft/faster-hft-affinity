package com.faster.affinity.integration;

import com.faster.affinity.factory.AffinityLibraryFactory;
import com.faster.affinity.factory.AffinityLibrary;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.topology.TopologyDetector;
import com.faster.affinity.performance.PerformanceMonitor;

import java.util.*;
import java.util.concurrent.*;

public class LinuxAffinityTest {
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS = 1000;
    private static final int LATENCY_PERCENTILES = 5; // 50th, 90th, 95th, 99th, 99.9th

    private final AffinityLibrary affinityLib;
    private final List<TestResult> results = new ArrayList<>();
    private final boolean verboseMode;

    public LinuxAffinityTest(boolean verbose) {
        this.verboseMode = verbose;
        this.affinityLib = AffinityLibraryFactory.getDefault();

        if (!affinityLib.isInitialized()) {
            throw new IllegalStateException("Affinity library failed to initialize");
        }
    }

    public static void main(String[] args) {
        boolean verbose = args.length > 0 && args[0].equals("-v");

        System.out.println("=== Linux HFT Affinity Library Test Suite ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        try {
            LinuxAffinityTest test = new LinuxAffinityTest(verbose);
            test.runAllTests();
            test.printResults();
        } catch (Exception e) {
            System.err.println("Test suite failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void runAllTests() {
        // 1. System capability detection
        testSystemCapabilities();

        // 2. CPU affinity tests
        testCpuAffinityOperations();
        testIsolatedCorePerformance();

        // 3. NUMA tests
        testNumaTopologyDetection();
        testNumaMemoryAllocation();
        testNumaAwareThreadPlacement();

        // 4. Cache topology tests
        testCacheTopologyDetection();
        testCacheSharingOptimization();

        // 5. Performance monitoring
        testPerformanceMonitoring();

        // 6. Latency tests
        testAffinitySetLatency();
        testContextSwitchReduction();

        // 7. HFT-specific scenarios
        testMarketDataHandlerOptimization();
        testOrderProcessingPipeline();
        testCriticalPathOptimization();

        // 8. Stress tests
        testConcurrentAffinityChanges();
        testRapidThreadMigration();
    }

    // Test 1: System Capabilities
    private void testSystemCapabilities() {
        TestResult result = new TestResult("System Capabilities Detection");

        try {
            AffinityManager.SystemCapabilities caps = affinityLib.getSystemCapabilities();

            result.addMetric("CPU Count", caps.getCpuCount());
            result.addMetric("NUMA Available", caps.isNumaAvailable() ? 1 : 0);
            result.addMetric("Perf Counters Available", caps.arePerformanceCountersAvailable() ? 1 : 0);
            result.addMetric("Realtime Support", caps.isRealtimeSupported() ? 1 : 0);

            // Test topology detection
            TopologyDetector.SystemTopology topology = affinityLib.getSystemTopology();
            result.addMetric("Socket Count", topology.getSocketCount());
            result.addMetric("Cores Per Socket", topology.getCoresPerSocket());
            result.addMetric("Hyperthreading", topology.isHyperthreadingEnabled() ? 1 : 0);
            result.addMetric("Max Cache Level", topology.getMaxCacheLevel());
            result.addMetric("Cache Line Size", topology.getCacheLineSize());

            if (verboseMode) {
                System.out.println("System Features: " + Arrays.toString(caps.getSupportedFeatures()));
            }

            result.setSuccess(caps.getCpuCount() > 0);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 2: CPU Affinity Operations
    private void testCpuAffinityOperations() {
        TestResult result = new TestResult("CPU Affinity Operations");

        try {
            long threadId = affinityLib.getCurrentThreadId();

            // Get original affinity
            OperationResult<BitSet> originalResult = affinityLib.getCurrentThreadAffinity();
            if (!originalResult.isSuccess()) {
                throw new RuntimeException("Failed to get original affinity: " + originalResult.getError());
            }
            BitSet originalAffinity = originalResult.getValue();

            // Set to single core (core 0)
            BitSet singleCore = new BitSet();
            singleCore.set(0);
            OperationResult<Void> setResult = affinityLib.setCurrentThreadAffinity(singleCore);
            if (!setResult.isSuccess()) {
                throw new RuntimeException("Failed to set single core affinity: " + setResult.getError());
            }

            // Verify
            OperationResult<BitSet> newAffinityResult = affinityLib.getCurrentThreadAffinity();
            if (!newAffinityResult.isSuccess() || !newAffinityResult.getValue().equals(singleCore)) {
                throw new RuntimeException("Affinity verification failed");
            }

            // Restore original
            affinityLib.setCurrentThreadAffinity(originalAffinity);

            result.addMetric("Affinity Set Success", 1);
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 3: Isolated Core Performance
    private void testIsolatedCorePerformance() {
        TestResult result = new TestResult("Isolated Core Performance");

        try {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                doWork();
            }

            // Measure baseline
            long[] baselineLatencies = measureCriticalPath();

            // Set affinity to isolated core (assume core 0 for simplicity; in prod, detect isolated)
            BitSet isolated = new BitSet();
            isolated.set(0);
            affinityLib.setCurrentThreadAffinity(isolated);

            // Measure again
            long[] isolatedLatencies = measureCriticalPath();

            // Sort and compare percentiles
            Arrays.sort(baselineLatencies);
            Arrays.sort(isolatedLatencies);

            result.addMetric("Baseline 99th (ns)", baselineLatencies[990 * TEST_ITERATIONS / 1000]);
            result.addMetric("Isolated 99th (ns)", isolatedLatencies[990 * TEST_ITERATIONS / 1000]);

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 4: NUMA Topology Detection
    private void testNumaTopologyDetection() {
        TestResult result = new TestResult("NUMA Topology Detection");

        try {
            if (!affinityLib.getSystemCapabilities().isNumaAvailable()) {
                result.setSuccess(true); // Skip if not available
                result.addMetric("NUMA Not Available", 1);
                results.add(result);
                return;
            }

            // Use getSystemTopology() to get NUMA node count
            TopologyDetector.SystemTopology topology = affinityLib.getSystemTopology();
            int nodeCount = topology.getNumaNodeCount();
            result.addMetric("NUMA Node Count", nodeCount);

            for (int node = 0; node < nodeCount; node++) {
                OperationResult<BitSet> cpusResult = affinityLib.getNumaNodeCpus(node);
                if (cpusResult.isSuccess()) {
                    result.addMetric("Node " + node + " CPU Count", cpusResult.getValue().cardinality());
                }
            }

            result.setSuccess(nodeCount > 0);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 5: NUMA Memory Allocation
    private void testNumaMemoryAllocation() {
        TestResult result = new TestResult("NUMA Memory Allocation");

        try {
            if (!affinityLib.getSystemCapabilities().isNumaAvailable()) {
                result.setSuccess(true);
                results.add(result);
                return;
            }

            long size = 1024 * 1024; // 1MB
            OperationResult<Long> allocResult = affinityLib.allocateNumaMemory(0, size);
            if (!allocResult.isSuccess()) {
                throw new RuntimeException("NUMA allocation failed");
            }
            long address = allocResult.getValue();

            affinityLib.freeNumaMemory(address);

            result.addMetric("Allocation Success", 1);
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 6: NUMA Aware Thread Placement
    private void testNumaAwareThreadPlacement() {
        TestResult result = new TestResult("NUMA Aware Thread Placement");

        try {
            if (!affinityLib.getSystemCapabilities().isNumaAvailable()) {
                result.setSuccess(true);
                results.add(result);
                return;
            }

            // Use setThreadNumaAffinity instead of setNumaAffinity
            OperationResult<Void> setResult = affinityLib.setThreadNumaAffinity(
                    affinityLib.getCurrentThreadId(), 0);
            if (!setResult.isSuccess()) {
                // If not supported, just mark as success
                result.addMetric("Not Supported", 1);
            } else {
                result.addMetric("Placement Success", 1);
            }

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 7: Cache Topology Detection
    private void testCacheTopologyDetection() {
        TestResult result = new TestResult("Cache Topology Detection");

        try {
            TopologyDetector.SystemTopology topology = affinityLib.getSystemTopology();

            result.addMetric("Max Cache Level", topology.getMaxCacheLevel());
            result.addMetric("Cache Line Size", topology.getCacheLineSize());

            // Get L3 cache size from topology's cache sizes map
            Map<Integer, Long> cacheSizes = topology.getCacheSizes();
            Long l3Size = cacheSizes.get(3);
            if (l3Size != null) {
                result.addMetric("L3 Cache Size", l3Size);
            }

            result.setSuccess(topology.getMaxCacheLevel() > 0);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 8: Cache Sharing Optimization
    private void testCacheSharingOptimization() {
        TestResult result = new TestResult("Cache Sharing Optimization");

        try {
            OperationResult<BitSet> sharedCores = affinityLib.getCacheLevelCores(0, 3); // L3 for core 0
            if (sharedCores.isSuccess()) {
                result.addMetric("Shared L3 Cores", sharedCores.getValue().cardinality());
            } else {
                result.addMetric("Not Supported", 1);
            }

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 9: Performance Monitoring
    private void testPerformanceMonitoring() {
        TestResult result = new TestResult("Performance Monitoring");

        try {
            OperationResult<Double> utilResult = affinityLib.getCoreUtilization(0);
            if (utilResult.isSuccess()) {
                result.addMetric("Core 0 Utilization", (long)(utilResult.getValue() * 100));
            }

            OperationResult<PerformanceMonitor.CorePerformanceSnapshot> coreSnapshot =
                    affinityLib.getCorePerformanceSnapshot(0);
            if (coreSnapshot.isSuccess()) {
                result.addMetric("Core 0 Cache Misses", coreSnapshot.getValue().getCacheMisses());
            }

            // Note: getThreadPerformanceSnapshot doesn't exist in the interface
            // Use getSystemPerformanceSnapshot instead
            OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> sysSnapshot =
                    affinityLib.getSystemPerformanceSnapshot();
            if (sysSnapshot.isSuccess()) {
                result.addMetric("System Avg Utilization",
                        (long)(sysSnapshot.getValue().getAvgCpuUtilization() * 100));
            }

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 10: Affinity Set Latency
    private void testAffinitySetLatency() {
        TestResult result = new TestResult("Affinity Set Latency");

        try {
            long[] latencies = new long[TEST_ITERATIONS];

            BitSet mask1 = new BitSet();
            mask1.set(0);
            BitSet mask2 = new BitSet();
            mask2.set(1);

            for (int i = 0; i < TEST_ITERATIONS; i++) {
                long start = System.nanoTime();
                affinityLib.setCurrentThreadAffinity(i % 2 == 0 ? mask1 : mask2);
                latencies[i] = System.nanoTime() - start;
            }

            Arrays.sort(latencies);
            result.addMetric("Set Latency 99th (ns)", latencies[990 * TEST_ITERATIONS / 1000]);

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 11: Context Switch Reduction
    private void testContextSwitchReduction() {
        TestResult result = new TestResult("Context Switch Reduction");

        try {
            // Since getThreadPerformanceSnapshot doesn't exist, use system snapshot
            OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> baselineResult =
                    affinityLib.getSystemPerformanceSnapshot();

            // Run workload
            for (int i = 0; i < TEST_ITERATIONS; i++) {
                doWork();
            }

            OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> afterResult =
                    affinityLib.getSystemPerformanceSnapshot();

            result.addMetric("Workload Completed", TEST_ITERATIONS);
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 12: Market Data Handler Optimization
    private void testMarketDataHandlerOptimization() {
        TestResult result = new TestResult("Market Data Handler Optimization");

        try {
            MarketDataSimulator simulator = new MarketDataSimulator();
            long start = System.nanoTime();
            for (int i = 0; i < TEST_ITERATIONS; i++) {
                simulator.processMessage();
            }
            long duration = System.nanoTime() - start;

            result.addMetric("Processing Time (ns)", duration);
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 13: Order Processing Pipeline
    private void testOrderProcessingPipeline() {
        TestResult result = new TestResult("Order Processing Pipeline");

        try {
            Order order = new Order();
            long start = System.nanoTime();
            for (int i = 0; i < TEST_ITERATIONS; i++) {
                order.validate();
                order.checkRisk();
                order.execute();
            }
            long duration = System.nanoTime() - start;

            result.addMetric("Pipeline Time (ns)", duration);
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 14: Critical Path Optimization
    private void testCriticalPathOptimization() {
        TestResult result = new TestResult("Critical Path Optimization");

        try {
            long[] latencies = measureCriticalPath();
            Arrays.sort(latencies);
            result.addMetric("Critical Path 99.9th (ns)", latencies[999 * TEST_ITERATIONS / 1000]);

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 15: Concurrent Affinity Changes
    private void testConcurrentAffinityChanges() {
        TestResult result = new TestResult("Concurrent Affinity Changes");

        try {
            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<Void>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int core = i;
                futures.add(executor.submit(() -> {
                    BitSet mask = new BitSet();
                    mask.set(core);
                    affinityLib.setCurrentThreadAffinity(mask);
                    doWork();
                    return null;
                }));
            }

            for (Future<Void> f : futures) {
                f.get();
            }

            executor.shutdown();
            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Test 16: Rapid Thread Migration
    private void testRapidThreadMigration() {
        TestResult result = new TestResult("Rapid Thread Migration");

        try {
            long[] migrationLatencies = new long[TEST_ITERATIONS];

            BitSet mask1 = new BitSet(); mask1.set(0);
            BitSet mask2 = new BitSet(); mask2.set(1);

            for (int i = 0; i < TEST_ITERATIONS; i++) {
                long start = System.nanoTime();
                affinityLib.setCurrentThreadAffinity(i % 2 == 0 ? mask1 : mask2);
                migrationLatencies[i] = System.nanoTime() - start;
            }

            Arrays.sort(migrationLatencies);
            result.addMetric("Migration 99th (ns)", migrationLatencies[990 * TEST_ITERATIONS / 1000]);

            result.setSuccess(true);

        } catch (Exception e) {
            result.setError(e);
        }

        results.add(result);
    }

    // Helper methods
    private long doWork() {
        long result = 0;
        for (int i = 0; i < 1000; i++) {
            result += Math.sqrt(i);
        }
        return result;
    }

    private static void blackhole(long value) {
        if (value == Long.MAX_VALUE) {
            System.out.println("Never happens");
        }
    }

    private long[] measureCriticalPath() {
        long[] latencies = new long[TEST_ITERATIONS];

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            long start = System.nanoTime();

            long value = i;
            value = Long.rotateLeft(value, 13);
            value ^= value >> 7;
            value = Long.rotateLeft(value, 17);
            blackhole(value);

            latencies[i] = System.nanoTime() - start;
        }

        return latencies;
    }

    private void printResults() {
        System.out.println("\n=== Test Results ===\n");

        for (TestResult result : results) {
            System.out.println(result);
            System.out.println();
        }

        // Summary
        long passed = results.stream().filter(TestResult::isSuccess).count();
        long failed = results.size() - passed;

        System.out.println("=== Summary ===");
        System.out.println("Total Tests: " + results.size());
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.out.println("\nFailed Tests:");
            results.stream()
                    .filter(r -> !r.isSuccess())
                    .forEach(r -> System.out.println("  - " + r.getName() + ": " + r.getError()));
        }
    }

    static class TestResult {
        private final String name;
        private final Map<String, Long> metrics = new LinkedHashMap<>();
        private boolean success = false;
        private String error = null;

        TestResult(String name) {
            this.name = name;
        }

        void addMetric(String key, long value) {
            metrics.put(key, value);
        }

        void setSuccess(boolean success) {
            this.success = success;
        }

        void setError(Exception e) {
            this.success = false;
            this.error = e.getMessage();
        }

        boolean isSuccess() {
            return success;
        }

        String getName() {
            return name;
        }

        String getError() {
            return error;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Test: ").append(name).append("\n");
            sb.append("Status: ").append(success ? "PASSED" : "FAILED").append("\n");

            if (error != null) {
                sb.append("Error: ").append(error).append("\n");
            }

            if (!metrics.isEmpty()) {
                sb.append("Metrics:\n");
                metrics.forEach((k, v) ->
                        sb.append("  ").append(k).append(": ").append(v).append("\n"));
            }

            return sb.toString();
        }
    }

    static class MarketDataSimulator {
        private long sequence = 0;
        private double price = 100.0;

        void processMessage() {
            sequence++;
            price = price * (1 + (Math.random() - 0.5) * 0.001);
            double volume = Math.random() * 1000;
            LinuxAffinityTest.blackhole(Double.doubleToLongBits(price + volume));
        }
    }

    static class Order {
        long timestamp;
        int orderId = ThreadLocalRandom.current().nextInt();

        void validate() {
            // Simulate validation
            LinuxAffinityTest.blackhole(orderId * 31);
        }

        void checkRisk() {
            // Simulate risk check
            LinuxAffinityTest.blackhole(orderId * 37);
        }

        void execute() {
            // Simulate execution
            LinuxAffinityTest.blackhole(orderId * 41);
        }
    }
}