package com.faster.affinity.performance;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.factory.AffinityLibrary;
import com.faster.affinity.factory.AffinityLibraryFactory;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HFTPerformanceRegressionTest {

    private AffinityLibrary library;
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASUREMENT_ITERATIONS = 10000;
    private static final double MAX_LATENCY_MICROSECONDS = 50.0; // HFT requirement
    private static final double REGRESSION_THRESHOLD = 0.05; // 5% regression threshold

    // Performance baselines (in nanoseconds)
    private static final long BASELINE_AFFINITY_SET_NS = 10000; // 10μs baseline
    private static final long BASELINE_AFFINITY_GET_NS = 5000;  // 5μs baseline

    @BeforeAll
    void setUp() throws Exception {
        AffinityConfig config = new AffinityConfig.Builder()
            .enablePerformanceCounters(true)
            .enableGovernorControl(true)
            .enableCaching(false) // Disable caching for accurate performance measurement
            .developerMode(false) // Production-like settings
            .build();

        library = AffinityLibraryFactory.create(config);
        assertNotNull(library);
        assertTrue(library.isInitialized());
    }

    @Test
    @DisplayName("Thread affinity set latency - HFT requirement")
    void testThreadAffinitySetLatency() {
        BitSet affinity = new BitSet();
        affinity.set(0);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            library.setCurrentThreadAffinity(affinity);
        }

        // Measure latency
        long[] latencies = new long[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            OperationResult<Void> result = library.setCurrentThreadAffinity(affinity);
            long end = System.nanoTime();

            assertTrue(result.isSuccess(), "Affinity set should succeed");
            latencies[i] = end - start;
        }

        LatencyStats stats = calculateLatencyStats(latencies);
        reportLatencyResults("Thread Affinity Set", stats, BASELINE_AFFINITY_SET_NS);

        // HFT requirements
        assertTrue(stats.p99Micros < MAX_LATENCY_MICROSECONDS,
            String.format("P99 latency %.2fμs exceeds HFT requirement of %.2fμs",
                stats.p99Micros, MAX_LATENCY_MICROSECONDS));

        // Regression check
        double regressionRatio = stats.avgNanos / BASELINE_AFFINITY_SET_NS;
        assertTrue(regressionRatio < (1.0 + REGRESSION_THRESHOLD),
            String.format("Performance regression detected: %.2f%% slower than baseline",
                (regressionRatio - 1.0) * 100));
    }

    @Test
    @DisplayName("Thread affinity get latency - HFT requirement")
    void testThreadAffinityGetLatency() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            library.getCurrentThreadAffinity();
        }

        // Measure latency
        long[] latencies = new long[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            OperationResult<BitSet> result = library.getCurrentThreadAffinity();
            long end = System.nanoTime();

            assertTrue(result.isSuccess(), "Affinity get should succeed");
            latencies[i] = end - start;
        }

        LatencyStats stats = calculateLatencyStats(latencies);
        reportLatencyResults("Thread Affinity Get", stats, BASELINE_AFFINITY_GET_NS);

        // HFT requirements
        assertTrue(stats.p99Micros < MAX_LATENCY_MICROSECONDS,
            String.format("P99 latency %.2fμs exceeds HFT requirement of %.2fμs",
                stats.p99Micros, MAX_LATENCY_MICROSECONDS));

        // Regression check
        double regressionRatio = stats.avgNanos / BASELINE_AFFINITY_GET_NS;
        assertTrue(regressionRatio < (1.0 + REGRESSION_THRESHOLD),
            String.format("Performance regression detected: %.2f%% slower than baseline",
                (regressionRatio - 1.0) * 100));
    }

    @Test
    @DisplayName("System capabilities query performance")
    void testSystemCapabilitiesPerformance() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            library.getSystemCapabilities();
        }

        // Measure latency
        long[] latencies = new long[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            var capabilities = library.getSystemCapabilities();
            long end = System.nanoTime();

            assertNotNull(capabilities);
            latencies[i] = end - start;
        }

        LatencyStats stats = calculateLatencyStats(latencies);
        reportLatencyResults("System Capabilities Query", stats, 1000); // 1μs baseline

        // Should be very fast since it's cached
        assertTrue(stats.avgMicros < 10.0,
            String.format("System capabilities query too slow: %.2fμs average", stats.avgMicros));
    }

    @Test
    @DisplayName("Memory allocation pattern - HFT optimized")
    void testMemoryAllocationPattern() {
        // Test that operations don't cause excessive GC pressure
        long gcCountBefore = getGCCount();
        long memoryBefore = getUsedMemory();

        // Perform many operations
        BitSet affinity = new BitSet();
        affinity.set(0);

        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            library.setCurrentThreadAffinity(affinity);
            library.getCurrentThreadAffinity();
            library.getSystemCapabilities();
        }

        // Force GC to get accurate measurement
        System.gc();
        Thread.yield();

        long gcCountAfter = getGCCount();
        long memoryAfter = getUsedMemory();

        long gcEvents = gcCountAfter - gcCountBefore;
        long memoryIncrease = memoryAfter - memoryBefore;

        System.out.printf("Memory pattern - GC events: %d, Memory increase: %d KB%n",
            gcEvents, memoryIncrease / 1024);

        // HFT requirement: minimal GC pressure
        assertTrue(gcEvents < 5,
            String.format("Too many GC events: %d (should be < 5)", gcEvents));

        // Memory increase should be minimal
        assertTrue(memoryIncrease < 1024 * 1024, // < 1MB
            String.format("Excessive memory allocation: %d KB", memoryIncrease / 1024));
    }

    private LatencyStats calculateLatencyStats(long[] latencies) {
        Arrays.sort(latencies);

        double avgNanos = Arrays.stream(latencies).average().orElse(0.0);
        long p50Nanos = latencies[latencies.length / 2];
        long p95Nanos = latencies[(int)(latencies.length * 0.95)];
        long p99Nanos = latencies[(int)(latencies.length * 0.99)];
        long maxNanos = latencies[latencies.length - 1];

        return new LatencyStats(avgNanos, p50Nanos, p95Nanos, p99Nanos, maxNanos);
    }

    private void reportLatencyResults(String operation, LatencyStats stats, long baselineNanos) {
        System.out.printf("%s Latency Results:%n", operation);
        System.out.printf("  Average: %.2f μs%n", stats.avgMicros);
        System.out.printf("  P50:     %.2f μs%n", stats.p50Micros);
        System.out.printf("  P95:     %.2f μs%n", stats.p95Micros);
        System.out.printf("  P99:     %.2f μs%n", stats.p99Micros);
        System.out.printf("  Max:     %.2f μs%n", stats.maxMicros);

        if (baselineNanos > 0) {
            double regressionRatio = stats.avgNanos / baselineNanos;
            if (regressionRatio > 1.0) {
                System.out.printf("  Regression: %.1f%% slower than baseline%n", (regressionRatio - 1.0) * 100);
            } else {
                System.out.printf("  Improvement: %.1f%% faster than baseline%n", (1.0 - regressionRatio) * 100);
            }
        }
        System.out.println();
    }

    private long getGCCount() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .mapToLong(gcBean -> gcBean.getCollectionCount())
            .sum();
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static class LatencyStats {
        final double avgNanos;
        final long p50Nanos;
        final long p95Nanos;
        final long p99Nanos;
        final long maxNanos;

        final double avgMicros;
        final double p50Micros;
        final double p95Micros;
        final double p99Micros;
        final double maxMicros;

        LatencyStats(double avgNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {
            this.avgNanos = avgNanos;
            this.p50Nanos = p50Nanos;
            this.p95Nanos = p95Nanos;
            this.p99Nanos = p99Nanos;
            this.maxNanos = maxNanos;

            this.avgMicros = avgNanos / 1000.0;
            this.p50Micros = p50Nanos / 1000.0;
            this.p95Micros = p95Nanos / 1000.0;
            this.p99Micros = p99Nanos / 1000.0;
            this.maxMicros = maxNanos / 1000.0;
        }
    }

    @AfterAll
    void tearDown() {
        if (library != null) {
            library.shutdown();
        }
    }
}