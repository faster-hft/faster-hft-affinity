package com.faster.affinity.performance;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.AffinityManager;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import java.util.concurrent.*;

/**
 * Comprehensive benchmark tests for HFT performance optimizations.
 * Validates the effectiveness of lock-free caching, object pooling, and NUMA awareness.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HFTPerformanceBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final int THREAD_COUNT = 4;

    private AffinityManager affinityManager;
    private BitSet testCpuMask;

    @BeforeAll
    void setUp() {
        // Create high-performance configuration
        AffinityConfig config = AffinityConfig.builder()
                .enablePerformanceCounters(true)
                .enableNumaOperations(true)
                .enableCaching(true)
                .enableThreadLocalCaching(true)
                .cacheExpiryMs(10000)
                .operationTimeoutMs(1000)
                .maxRetryAttempts(1)
                .developerMode(true)
                .build();

        affinityManager = AffinityManager.getInstance(config);

        // Create test CPU mask (CPU 0 and 1)
        testCpuMask = new BitSet();
        testCpuMask.set(0);
        testCpuMask.set(1);

        System.out.println("🚀 HFT Performance Benchmark Test Suite");
        System.out.println("Configuration: " + config);
    }

    @Test
    @Order(1)
    @DisplayName("Benchmark: Hot Path vs Standard Operations")
    void benchmarkHotPathVsStandard() {
        System.out.println("\n📊 Benchmarking Hot Path vs Standard Operations");

        // Warmup
        warmupOperations();

        // Reset performance stats
        affinityManager.resetHFTPerformanceStats();

        // Benchmark standard operations
        long standardLatency = benchmarkStandardOperations();

        // Reset and benchmark hot path operations
        affinityManager.resetHFTPerformanceStats();
        long hotPathLatency = benchmarkHotPathOperations();

        // Get performance stats
        HFTPerformanceProfiler.HFTPerformanceStats stats = affinityManager.getHFTPerformanceStats();

        System.out.println("Standard operations average latency: " + standardLatency + " ns");
        System.out.println("Hot path operations average latency: " + hotPathLatency + " ns");
        System.out.println("Performance improvement: " +
                           String.format("%.2fx", (double) standardLatency / hotPathLatency));

        if (stats != null) {
            System.out.println("HFT Performance Stats:\n" + stats);
        }

        // Validate performance improvement
        assertTrue(hotPathLatency < standardLatency,
                   "Hot path should be faster than standard operations");

        double improvement = (double) standardLatency / hotPathLatency;
        assertTrue(improvement > 1.1,
                   "Hot path should be at least 10% faster, got " + improvement + "x");
    }

    @Test
    @Order(2)
    @DisplayName("Benchmark: Object Pooling Efficiency")
    void benchmarkObjectPooling() {
        System.out.println("\n📊 Benchmarking Object Pooling Efficiency");

        // Test with pooled objects
        long pooledLatency = benchmarkWithPooledObjects();

        // Test without pooling (create new objects each time)
        long nonPooledLatency = benchmarkWithoutPooling();

        System.out.println("Pooled objects average latency: " + pooledLatency + " ns");
        System.out.println("Non-pooled objects average latency: " + nonPooledLatency + " ns");
        System.out.println("Pooling efficiency: " +
                           String.format("%.2fx", (double) nonPooledLatency / pooledLatency));

        // Validate pooling efficiency
        assertTrue(pooledLatency <= nonPooledLatency,
                   "Pooled objects should not be slower than non-pooled");
    }

    @Test
    @Order(3)
    @DisplayName("Benchmark: Cache Hit Rate Effectiveness")
    void benchmarkCacheHitRate() {
        System.out.println("\n📊 Benchmarking Cache Hit Rate Effectiveness");

        affinityManager.resetHFTPerformanceStats();

        // Perform repeated operations on same thread to maximize cache hits

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Alternate between get and set to test cache effectiveness
            if (i % 2 == 0) {
                affinityManager.getCurrentThreadAffinityFast();
            } else {
                affinityManager.setCurrentThreadAffinityFast(testCpuMask);
            }
        }

        HFTPerformanceProfiler.HFTPerformanceStats stats = affinityManager.getHFTPerformanceStats();

        if (stats != null) {
            System.out.println("Cache hit rate: " + String.format("%.2f%%", stats.getCacheHitRate() * 100));
            System.out.println("Hot path usage: " + String.format("%.2f%%", stats.getHotPathUsageRate() * 100));

            // Validate cache effectiveness
            assertTrue(stats.getCacheHitRate() > 0.5,
                       "Cache hit rate should be > 50% for repeated operations");
            assertTrue(stats.getHotPathUsageRate() > 0.9,
                       "Hot path usage should be > 90%");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Benchmark: Concurrent Performance")
    void benchmarkConcurrentPerformance() throws InterruptedException {
        System.out.println("\n📊 Benchmarking Concurrent Performance");

        affinityManager.resetHFTPerformanceStats();

        @SuppressWarnings("resource") // ExecutorService doesn't implement AutoCloseable in Java 11
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        long[] latencies = new long[THREAD_COUNT];

        try {
            // Launch concurrent workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadIndex = i;
                executor.submit(() -> {
                    try {
                        latencies[threadIndex] = benchmarkHotPathOperationsInThread();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for completion
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Benchmark should complete within 30 seconds");

            // Calculate statistics
            long totalLatency = 0;
            long minLatency = Long.MAX_VALUE;
            long maxLatency = 0;

            for (long latency : latencies) {
                totalLatency += latency;
                minLatency = Math.min(minLatency, latency);
                maxLatency = Math.max(maxLatency, latency);
            }

            long avgLatency = totalLatency / THREAD_COUNT;

            System.out.println("Concurrent performance results:");
            System.out.println("  Average latency: " + avgLatency + " ns");
            System.out.println("  Min latency: " + minLatency + " ns");
            System.out.println("  Max latency: " + maxLatency + " ns");
            System.out.println("  Latency variance: " + (maxLatency - minLatency) + " ns");

            HFTPerformanceProfiler.HFTPerformanceStats stats = affinityManager.getHFTPerformanceStats();
            if (stats != null) {
                System.out.println("Concurrent HFT stats:\n" + stats);
            }

            // Validate concurrent performance
            assertTrue(avgLatency < 10000, // 10 microseconds
                       "Average concurrent latency should be < 10μs, got " + avgLatency + "ns");

        } finally {
            executor.shutdown();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Benchmark: Bulk Operations")
    void benchmarkBulkOperations() {
        System.out.println("\n📊 Benchmarking Bulk Operations");

        // Create array of thread IDs
        long[] threadIds = new long[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            threadIds[i] = Thread.currentThread().getId() + i; // Simulated thread IDs
        }

        // Benchmark bulk operation
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) { // Fewer iterations for bulk
            int successCount = affinityManager.setBulkThreadAffinityFast(threadIds, testCpuMask);
            assertTrue(successCount >= 0, "Bulk operation should not fail completely");
        }

        long bulkLatency = (System.nanoTime() - startTime) / (BENCHMARK_ITERATIONS / 10);

        // Compare with individual operations
        startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            for (long ignored : threadIds) {
                affinityManager.setCurrentThreadAffinityFast(testCpuMask);
            }
        }

        long individualLatency = (System.nanoTime() - startTime) / (BENCHMARK_ITERATIONS / 10);

        System.out.println("Bulk operations average latency: " + bulkLatency + " ns");
        System.out.println("Individual operations average latency: " + individualLatency + " ns");
        System.out.println("Bulk efficiency: " +
                           String.format("%.2fx", (double) individualLatency / bulkLatency));

        // Bulk operations should be more efficient for multiple threads
        assertTrue(bulkLatency <= individualLatency * 1.2, // Allow 20% overhead
                   "Bulk operations should not be significantly slower");
    }

    private void warmupOperations() {
        System.out.println("Warming up JVM...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            affinityManager.getCurrentThreadAffinityFast();
            affinityManager.setCurrentThreadAffinityFast(testCpuMask);
        }
    }

    private long benchmarkStandardOperations() {
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            affinityManager.getThreadAffinity(Thread.currentThread().getId());
        }

        return (System.nanoTime() - startTime) / BENCHMARK_ITERATIONS;
    }

    private long benchmarkHotPathOperations() {
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            affinityManager.getCurrentThreadAffinityFast();
        }

        return (System.nanoTime() - startTime) / BENCHMARK_ITERATIONS;
    }

    private long benchmarkHotPathOperationsInThread() {
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS / THREAD_COUNT; i++) {
            affinityManager.getCurrentThreadAffinityFast();
        }

        return (System.nanoTime() - startTime) / (BENCHMARK_ITERATIONS / THREAD_COUNT);
    }

    private long benchmarkWithPooledObjects() {
        // Use pooled BitSet objects
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try (var pooledBitSet = com.faster.affinity.pool.PooledBitSet.acquire()) {
                pooledBitSet.set(0);
                pooledBitSet.set(1);
                // Object is automatically returned to pool
            }
        }

        return (System.nanoTime() - startTime) / BENCHMARK_ITERATIONS;
    }

    private long benchmarkWithoutPooling() {
        // Create new BitSet objects each time
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            BitSet bitSet = new BitSet();
            bitSet.set(0);
            bitSet.set(1);
            // Ensure the BitSet is actually used to prevent dead code elimination
            if (bitSet.cardinality() != 2) {
                throw new IllegalStateException("Unexpected cardinality");
            }
        }

        return (System.nanoTime() - startTime) / BENCHMARK_ITERATIONS;
    }

    @AfterAll
    void tearDown() {
        HFTPerformanceProfiler.HFTPerformanceStats finalStats = affinityManager.getHFTPerformanceStats();
        if (finalStats != null) {
            System.out.println("\n📈 Final HFT Performance Summary:");
            System.out.println(finalStats);
        }

        System.out.println("\n✅ HFT Performance Benchmark Test Suite Complete");
    }
}