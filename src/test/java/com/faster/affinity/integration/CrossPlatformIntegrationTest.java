package com.faster.affinity.integration;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.core.CPUGovernorManager;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.factory.AffinityLibrary;
import com.faster.affinity.factory.AffinityLibraryFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossPlatformIntegrationTest {

    private AffinityLibrary library;
    private AffinityConfig config;

    @BeforeAll
    void setUp() throws Exception {
        config = new AffinityConfig.Builder()
            .testMode(true) // Disable rate limiting for high-frequency tests
            .enablePerformanceCounters(true)
            .enableNumaOperations(true)
            .enableIRQManagement(true)
            .enableGovernorControl(true)
            .enableCaching(false)
            .developerMode(true)
            .build();

        library = AffinityLibraryFactory.create(config);
        assertNotNull(library);
        assertTrue(library.isInitialized());
    }

    @Test
    @DisplayName("Cross-platform system capabilities")
    void testSystemCapabilities() {
        var capabilities = library.getSystemCapabilities();

        assertNotNull(capabilities);
        assertTrue(capabilities.getCpuCount() > 0);
        assertTrue(capabilities.getCpuCount() <= 128);
        assertNotNull(capabilities.getPlatformInfo());
        assertTrue(capabilities.getSupportedFeatures().length > 0);

        System.out.println("Platform: " + capabilities.getPlatformInfo());
        System.out.println("CPU Count: " + capabilities.getCpuCount());
        System.out.println("NUMA Available: " + capabilities.isNumaAvailable());
        System.out.println("Performance Counters: " + capabilities.arePerformanceCountersAvailable());
        System.out.println("Governor Control: " + capabilities.isGovernorControlSupported());
        System.out.println("Realtime Support: " + capabilities.isRealtimeSupported());
    }

    @Test
    @DisplayName("Cross-platform thread affinity operations")
    void testThreadAffinityOperations() {
        long currentThread = library.getCurrentThreadId();
        assertTrue(currentThread > 0);

        OperationResult<BitSet> getCurrentResult = library.getCurrentThreadAffinity();
        assertTrue(getCurrentResult.isSuccess());
        BitSet originalAffinity = getCurrentResult.getValue();
        assertNotNull(originalAffinity);
        assertFalse(originalAffinity.isEmpty());

        BitSet newAffinity = new BitSet();
        newAffinity.set(0);
        OperationResult<Void> setResult = library.setCurrentThreadAffinity(newAffinity);

        if (setResult.isSuccess()) {
            OperationResult<BitSet> verifyResult = library.getCurrentThreadAffinity();
            assertTrue(verifyResult.isSuccess());

            OperationResult<Void> restoreResult = library.setCurrentThreadAffinity(originalAffinity);
            assertTrue(restoreResult.isSuccess());
        } else {
            System.out.println("Thread affinity setting failed (may require elevated privileges): " +
                setResult.getError().getMessage());
        }
    }

    @Test
    @DisplayName("Cross-platform system topology")
    void testSystemTopology() {
        var topology = library.getSystemTopology();

        assertNotNull(topology);
        assertTrue(topology.getCpuCount() > 0);
        assertTrue(topology.getSocketCount() > 0);
        assertTrue(topology.getCoresPerSocket() > 0);
        assertTrue(topology.getNumaNodeCount() > 0);

        System.out.println("Topology - CPUs: " + topology.getCpuCount() +
            ", Sockets: " + topology.getSocketCount() +
            ", Cores/Socket: " + topology.getCoresPerSocket() +
            ", NUMA Nodes: " + topology.getNumaNodeCount());
    }

    @Test
    @DisplayName("CPU governor control - Cross-platform")
    void testCpuGovernorControl() {
        var capabilities = library.getSystemCapabilities();

        if (!capabilities.isGovernorControlSupported()) {
            System.out.println("CPU governor control not supported on this platform");
            return;
        }

        OperationResult<CPUGovernorManager.GovernorStatus> statusResult = library.getGovernorStatus();
        assertTrue(statusResult.isSuccess());

        CPUGovernorManager.GovernorStatus status = statusResult.getValue();
        assertEquals(capabilities.getCpuCount(), status.getTotalCores());
        assertTrue(status.getPerformanceCores() >= 0);
        assertTrue(status.getPerformanceCores() <= status.getTotalCores());

        System.out.println("Governor Status - Total: " + status.getTotalCores() +
            ", Performance: " + status.getPerformanceCores() +
            ", All Performance: " + status.isAllCoresPerformance());

        OperationResult<CPUGovernorManager.GovernorMode> govResult = library.getCurrentGovernor(0);
        if (govResult.isSuccess()) {
            System.out.println("Core 0 governor: " + govResult.getValue());
        } else {
            System.out.println("Cannot read governor for core 0: " + govResult.getError().getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux-specific CPU governor operations")
    void testLinuxGovernorOperations() {
        OperationResult<CPUGovernorManager.GovernorMode> originalResult = library.getCurrentGovernor(0);
        assertTrue(originalResult.isSuccess());

        CPUGovernorManager.GovernorMode originalGovernor = originalResult.getValue();

        OperationResult<Void> setResult = library.setGovernor(0, CPUGovernorManager.GovernorMode.PERFORMANCE);

        if (setResult.isSuccess()) {
            System.out.println("✓ Successfully set core 0 to performance governor");

            OperationResult<Void> restoreResult = library.setGovernor(0, originalGovernor);
            assertTrue(restoreResult.isSuccess());
            System.out.println("✓ Restored original governor");
        } else {
            System.out.println("Governor setting failed (requires root): " + setResult.getError().getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows-specific governor operations")
    void testWindowsGovernorOperations() {
        OperationResult<CPUGovernorManager.GovernorMode> govResult = library.getCurrentGovernor(0);

        if (!govResult.isSuccess()) {
            System.out.println("Windows governor control not available (expected): " +
                govResult.getError().getMessage());
        }
    }

    @Test
    @DisplayName("NUMA operations - Cross-platform")
    void testNumaOperations() {
        var capabilities = library.getSystemCapabilities();
        var topology = library.getSystemTopology();

        if (!capabilities.isNumaAvailable()) {
            System.out.println("NUMA not available on this platform");
            return;
        }

        for (int nodeId = 0; nodeId < topology.getNumaNodeCount(); nodeId++) {
            OperationResult<BitSet> nodeCpusResult = library.getNumaNodeCpus(nodeId);
            if (nodeCpusResult.isSuccess()) {
                BitSet nodeCpus = nodeCpusResult.getValue();
                System.out.println("NUMA Node " + nodeId + " CPUs: " + nodeCpus);
                assertFalse(nodeCpus.isEmpty());
            }

            var memoryResult = library.getNumaNodeMemoryInfo(nodeId);
            if (memoryResult.isSuccess()) {
                var memInfo = memoryResult.getValue();
                System.out.println("NUMA Node " + nodeId + " Memory: " +
                    (memInfo.getTotalBytes() / 1024 / 1024) + "MB total, " +
                    (memInfo.getFreeBytes() / 1024 / 1024) + "MB free");
                assertTrue(memInfo.getTotalBytes() > 0);
                assertTrue(memInfo.getFreeBytes() >= 0);
            }
        }
    }

    @Test
    @DisplayName("Performance monitoring - Cross-platform")
    void testPerformanceMonitoring() {
        var capabilities = library.getSystemCapabilities();

        if (!capabilities.arePerformanceCountersAvailable()) {
            System.out.println("Performance counters not available on this platform");
            return;
        }

        for (int core = 0; core < Math.min(4, capabilities.getCpuCount()); core++) {
            var utilResult = library.getCoreUtilization(core);
            if (utilResult.isSuccess()) {
                double utilization = utilResult.getValue();
                System.out.println("Core " + core + " utilization: " + String.format("%.2f%%", utilization));
                assertTrue(utilization >= 0.0);
                assertTrue(utilization <= 100.0);
            }
        }
    }

    @Test
    @DisplayName("Error handling and edge cases")
    void testErrorHandling() {
        OperationResult<Double> invalidCoreResult = library.getCoreUtilization(9999);
        assertFalse(invalidCoreResult.isSuccess());
        assertNotNull(invalidCoreResult.getError());

        OperationResult<Void> nullAffinityResult = library.setCurrentThreadAffinity(null);
        assertFalse(nullAffinityResult.isSuccess());
        assertNotNull(nullAffinityResult.getError());

        BitSet emptyMask = new BitSet();
        OperationResult<Void> emptyMaskResult = library.setCurrentThreadAffinity(emptyMask);
        assertFalse(emptyMaskResult.isSuccess());
        assertNotNull(emptyMaskResult.getError());
    }

    @Test
    @DisplayName("Feature compatibility matrix")
    void testFeatureCompatibilityMatrix() {
        var capabilities = library.getSystemCapabilities();
        String os = System.getProperty("os.name").toLowerCase();

        System.out.println("\n=== Feature Compatibility Matrix ===");
        System.out.println("Platform: " + os);
        System.out.println("Thread Affinity: ✓ (Universal)");
        System.out.println("Process Affinity: ✓ (Universal)");
        System.out.println("NUMA Support: " + (capabilities.isNumaAvailable() ? "✓" : "✗"));
        System.out.println("Performance Counters: " + (capabilities.arePerformanceCountersAvailable() ? "✓" : "✗"));
        System.out.println("Governor Control: " + (capabilities.isGovernorControlSupported() ? "✓" : "✗"));
        System.out.println("Realtime Support: " + (capabilities.isRealtimeSupported() ? "✓" : "✗"));

        if (os.contains("linux")) {
            assertTrue(capabilities.isGovernorControlSupported(), "Linux should support governor control");
        } else if (os.contains("windows")) {
            System.out.println("Windows governor control: Limited/Not available (expected)");
        }
    }

    @Test
    @DisplayName("Concurrent operations safety")
    void testConcurrentOperations() throws InterruptedException {
        int threadCount = Math.min(4, Runtime.getRuntime().availableProcessors());
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    var capabilities = library.getSystemCapabilities();
                    var topology = library.getSystemTopology();

                    if (capabilities.arePerformanceCountersAvailable()) {
                        var perfResult = library.getCoreUtilization(0);
                        results[threadIndex] = perfResult.isSuccess();
                    } else {
                        results[threadIndex] = true;
                    }
                } catch (Exception e) {
                    System.err.println("Thread " + threadIndex + " failed: " + e.getMessage());
                    results[threadIndex] = false;
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join(10000);
        }

        for (int i = 0; i < threadCount; i++) {
            assertTrue(results[i], "Thread " + i + " should have succeeded");
        }

        System.out.println("✓ Concurrent operations completed successfully with " + threadCount + " threads");
    }

    @AfterAll
    void tearDown() {
        if (library != null) {
            library.shutdown();
        }
    }
}