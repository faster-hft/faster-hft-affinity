package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import org.junit.jupiter.api.*;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AffinityManagerTest {

    private AffinityManager affinityManager;

    @BeforeAll
    void setUp() throws Exception {
        AffinityConfig config = new AffinityConfig.Builder()
            .enablePerformanceCounters(false)
            .enableNumaOperations(false)
            .enableIRQManagement(false)
            .enableGovernorControl(false)
            .enableCaching(false)
            .developerMode(true)
            .build();

        affinityManager = AffinityManager.getInstance(config);
        assertNotNull(affinityManager);
    }

    @Test
    @DisplayName("Affinity manager system information")
    void testSystemInformation() {
        assertTrue(affinityManager.getCurrentThreadId() > 0);
        assertTrue(affinityManager.getCurrentProcessId() > 0);

        var capabilities = affinityManager.getSystemCapabilities();
        assertNotNull(capabilities);
        assertTrue(capabilities.getCpuCount() > 0);
        assertNotNull(capabilities.getPlatformInfo());
    }

    @Test
    @DisplayName("Thread affinity operations")
    void testThreadAffinityOperations() {
        long currentThreadId = affinityManager.getCurrentThreadId();

        // Get current thread affinity
        OperationResult<BitSet> getCurrentResult = affinityManager.getThreadAffinity(currentThreadId);
        assertTrue(getCurrentResult.isSuccess());
        BitSet originalAffinity = getCurrentResult.getValue();
        assertNotNull(originalAffinity);
        assertFalse(originalAffinity.isEmpty());

        // Try to set affinity to first CPU only
        BitSet newAffinity = new BitSet();
        newAffinity.set(0);
        OperationResult<Void> setResult = affinityManager.setThreadAffinity(currentThreadId, newAffinity);

        if (setResult.isSuccess()) {
            // Verify the change
            OperationResult<BitSet> verifyResult = affinityManager.getThreadAffinity(currentThreadId);
            assertTrue(verifyResult.isSuccess());

            // Restore original affinity
            OperationResult<Void> restoreResult = affinityManager.setThreadAffinity(currentThreadId, originalAffinity);
            assertTrue(restoreResult.isSuccess());
        } else {
            // On some platforms, affinity setting may require elevated privileges
            System.out.println("Thread affinity setting failed (may require elevated privileges): " +
                setResult.getError().getMessage());
        }
    }

    @Test
    @DisplayName("Process affinity operations")
    void testProcessAffinityOperations() {
        int currentProcessId = affinityManager.getCurrentProcessId();

        // Get current process affinity
        OperationResult<BitSet> getCurrentResult = affinityManager.getProcessAffinity(currentProcessId);
        assertTrue(getCurrentResult.isSuccess());
        BitSet originalAffinity = getCurrentResult.getValue();
        assertNotNull(originalAffinity);
        assertFalse(originalAffinity.isEmpty());

        // Try to set process affinity
        BitSet newAffinity = new BitSet();
        newAffinity.set(0, Math.min(4, affinityManager.getSystemCapabilities().getCpuCount()));

        OperationResult<Void> setResult = affinityManager.setProcessAffinity(currentProcessId, newAffinity);

        if (setResult.isSuccess()) {
            // Restore original affinity
            OperationResult<Void> restoreResult = affinityManager.setProcessAffinity(currentProcessId, originalAffinity);
            assertTrue(restoreResult.isSuccess());
        } else {
            System.out.println("Process affinity setting failed (may require elevated privileges): " +
                setResult.getError().getMessage());
        }
    }

    @Test
    @DisplayName("Invalid affinity mask handling")
    void testInvalidAffinityMask() {
        long currentThreadId = affinityManager.getCurrentThreadId();

        // Test empty affinity mask
        BitSet emptyAffinity = new BitSet();
        OperationResult<Void> result = affinityManager.setThreadAffinity(currentThreadId, emptyAffinity);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Invalid thread ID handling")
    void testInvalidThreadId() {
        BitSet validAffinity = new BitSet();
        validAffinity.set(0);

        // Test with invalid thread ID
        OperationResult<Void> result = affinityManager.setThreadAffinity(-1L, validAffinity);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Topology detector access")
    void testTopologyDetectorAccess() {
        var topologyDetector = affinityManager.getTopologyDetector();
        assertNotNull(topologyDetector);
        assertTrue(topologyDetector.getCpuCount() > 0);
    }

    @Test
    @DisplayName("Manager access with disabled features")
    void testDisabledFeatures() {
        // Performance monitor should be disabled
        assertThrows(Exception.class, () -> affinityManager.getPerformanceMonitor());

        // NUMA manager should be disabled
        assertThrows(Exception.class, () -> affinityManager.getNUMAManager());

        // IRQ manager should be disabled
        assertThrows(Exception.class, () -> affinityManager.getIRQManager());

        // CPU governor manager should be disabled
        assertThrows(Exception.class, () -> affinityManager.getCPUGovernorManager());
    }

    @AfterAll
    void tearDown() {
        if (affinityManager != null) {
            affinityManager.shutdown();
        }
    }
}