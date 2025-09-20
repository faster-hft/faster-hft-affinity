package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CPUGovernorManagerTest {

    @Mock
    private PlatformProvider mockPlatformProvider;

    @Mock
    private AffinityConfig mockConfig;

    private CPUGovernorManager governorManager;

    @BeforeAll
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Configure mock config
        when(mockConfig.isGovernorControlEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.isAutoSetPerformanceGovernor()).thenReturn(false);
        when(mockConfig.isRestoreGovernorsOnShutdown()).thenReturn(true);

        // Configure mock platform provider
        when(mockPlatformProvider.supportsFeature("cpu_governor_control")).thenReturn(true);
        when(mockPlatformProvider.getCpuCount()).thenReturn(2);

        governorManager = new CPUGovernorManager(mockPlatformProvider, mockConfig);
    }

    @Test
    @DisplayName("CPUGovernorManager initialization with governor support")
    void testInitialization() throws Exception {
        // Mock governor discovery
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.getCpuGovernor(1)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.getCpuGovernor(2)).thenReturn(CPUGovernorManager.GovernorMode.PERFORMANCE);

        assertDoesNotThrow(() -> governorManager.initialize());
        assertTrue(governorManager.isAvailable());
    }

    @Test
    @DisplayName("CPUGovernorManager initialization without governor support")
    void testInitializationWithoutSupport() throws Exception {
        when(mockPlatformProvider.supportsFeature("cpu_governor_control")).thenReturn(false);

        CPUGovernorManager unsupportedManager = new CPUGovernorManager(mockPlatformProvider, mockConfig);
        assertDoesNotThrow(() -> unsupportedManager.initialize());
        assertFalse(unsupportedManager.isAvailable());
    }

    @Test
    @DisplayName("Get current governor")
    void testGetCurrentGovernor() throws Exception {
        // Setup
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.getCpuGovernor(1)).thenReturn(CPUGovernorManager.GovernorMode.PERFORMANCE);

        governorManager.initialize();

        // Test getting governor for core 0
        OperationResult<CPUGovernorManager.GovernorMode> result0 = governorManager.getCurrentGovernor(0);
        assertTrue(result0.isSuccess());
        assertEquals(CPUGovernorManager.GovernorMode.ONDEMAND, result0.getValue());

        // Test getting governor for core 1
        OperationResult<CPUGovernorManager.GovernorMode> result1 = governorManager.getCurrentGovernor(1);
        assertTrue(result1.isSuccess());
        assertEquals(CPUGovernorManager.GovernorMode.PERFORMANCE, result1.getValue());
    }

    @Test
    @DisplayName("Set single core governor")
    void testSetGovernor() throws Exception {
        // Setup
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.setCpuGovernor(eq(0), eq(CPUGovernorManager.GovernorMode.PERFORMANCE)))
            .thenReturn(0); // SUCCESS

        governorManager.initialize();

        // Set governor to performance
        OperationResult<Void> result = governorManager.setGovernor(0, CPUGovernorManager.GovernorMode.PERFORMANCE);

        assertTrue(result.isSuccess());
        verify(mockPlatformProvider).setCpuGovernor(eq(0), eq(CPUGovernorManager.GovernorMode.PERFORMANCE));
    }

    @Test
    @DisplayName("Set all cores governor")
    void testSetAllCoresGovernor() throws Exception {
        // Setup
        when(mockPlatformProvider.getCpuGovernor(anyInt())).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.setCpuGovernor(anyInt(), eq(CPUGovernorManager.GovernorMode.PERFORMANCE)))
            .thenReturn(0); // SUCCESS

        governorManager.initialize();

        // Set all cores to performance
        OperationResult<Void> result = governorManager.setAllCoresGovernor(CPUGovernorManager.GovernorMode.PERFORMANCE);

        assertTrue(result.isSuccess());

        // Should have called setCpuGovernor for all 2 cores
        for (int core = 0; core < 2; core++) {
            verify(mockPlatformProvider).setCpuGovernor(eq(core), eq(CPUGovernorManager.GovernorMode.PERFORMANCE));
        }
    }

    @Test
    @DisplayName("Get governor status")
    void testGetGovernorStatus() throws Exception {
        // Setup
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.PERFORMANCE);
        when(mockPlatformProvider.getCpuGovernor(1)).thenReturn(CPUGovernorManager.GovernorMode.PERFORMANCE);

        // Mock available governors
        List<CPUGovernorManager.GovernorMode> availableGovernors = Arrays.asList(
            CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.ONDEMAND,
            CPUGovernorManager.GovernorMode.POWERSAVE
        );
        when(mockPlatformProvider.getAvailableGovernors(anyInt())).thenReturn(availableGovernors);

        // Mock frequency information
        when(mockPlatformProvider.getCpuFrequency(anyInt())).thenReturn(3000000000L); // 3 GHz
        when(mockPlatformProvider.getCpuMinFrequency(anyInt())).thenReturn(1000000000L); // 1 GHz
        when(mockPlatformProvider.getCpuMaxFrequency(anyInt())).thenReturn(4000000000L); // 4 GHz

        governorManager.initialize();

        OperationResult<CPUGovernorManager.GovernorStatus> result = governorManager.getGovernorStatus();

        assertTrue(result.isSuccess());
        CPUGovernorManager.GovernorStatus status = result.getValue();
        assertEquals(2, status.getTotalCores());
        assertEquals(2, status.getPerformanceCores()); // Cores 0, 1
        assertEquals(0, status.getPowerSaveCores());   // No cores
        assertTrue(status.isAllCoresPerformance());
    }

    @Test
    @DisplayName("Restore original governors")
    void testRestoreOriginalGovernors() throws Exception {
        // Setup - original governors
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.getCpuGovernor(1)).thenReturn(CPUGovernorManager.GovernorMode.CONSERVATIVE);
        when(mockPlatformProvider.setCpuGovernor(anyInt(), any())).thenReturn(0); // SUCCESS

        governorManager.initialize();

        // Change governors
        governorManager.setGovernor(0, CPUGovernorManager.GovernorMode.PERFORMANCE);
        governorManager.setGovernor(1, CPUGovernorManager.GovernorMode.PERFORMANCE);

        // Restore original governors
        OperationResult<Void> result = governorManager.restoreOriginalGovernors();

        assertTrue(result.isSuccess());
        verify(mockPlatformProvider).setCpuGovernor(eq(0), eq(CPUGovernorManager.GovernorMode.ONDEMAND));
        verify(mockPlatformProvider).setCpuGovernor(eq(1), eq(CPUGovernorManager.GovernorMode.CONSERVATIVE));
    }

    @Test
    @DisplayName("Auto-set performance governor on initialization")
    void testAutoSetPerformanceGovernor() throws Exception {
        // Configure auto-set performance mode
        when(mockConfig.isAutoSetPerformanceGovernor()).thenReturn(true);
        when(mockPlatformProvider.getCpuGovernor(anyInt())).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.setCpuGovernor(anyInt(), eq(CPUGovernorManager.GovernorMode.PERFORMANCE)))
            .thenReturn(0); // SUCCESS

        CPUGovernorManager autoManager = new CPUGovernorManager(mockPlatformProvider, mockConfig);
        autoManager.initialize();

        // Should have set all cores to performance mode
        for (int core = 0; core < 2; core++) {
            verify(mockPlatformProvider).setCpuGovernor(eq(core), eq(CPUGovernorManager.GovernorMode.PERFORMANCE));
        }
    }

    @Test
    @DisplayName("Disabled governor control")
    void testDisabledGovernorControl() throws Exception {
        when(mockConfig.isGovernorControlEnabled()).thenReturn(false);

        CPUGovernorManager disabledManager = new CPUGovernorManager(mockPlatformProvider, mockConfig);
        disabledManager.initialize();

        assertFalse(disabledManager.isAvailable());

        OperationResult<CPUGovernorManager.GovernorMode> result = disabledManager.getCurrentGovernor(0);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Invalid core ID handling")
    void testInvalidCoreId() throws Exception {
        governorManager.initialize();

        OperationResult<CPUGovernorManager.GovernorMode> result = governorManager.getCurrentGovernor(999);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Governor mode enum string conversion")
    void testGovernorModeConversion() {
        // Test valid conversions
        assertEquals(CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.fromString("performance"));
        assertEquals(CPUGovernorManager.GovernorMode.ONDEMAND,
            CPUGovernorManager.GovernorMode.fromString("ondemand"));
        assertEquals(CPUGovernorManager.GovernorMode.POWERSAVE,
            CPUGovernorManager.GovernorMode.fromString("powersave"));

        // Test invalid conversion
        assertThrows(IllegalArgumentException.class, () ->
            CPUGovernorManager.GovernorMode.fromString("invalid_governor"));
    }

    @Test
    @DisplayName("Governor info creation")
    void testGovernorInfo() {
        List<CPUGovernorManager.GovernorMode> availableGovernors = Arrays.asList(
            CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.ONDEMAND
        );

        CPUGovernorManager.GovernorInfo info = new CPUGovernorManager.GovernorInfo(
            0, // coreId
            CPUGovernorManager.GovernorMode.PERFORMANCE, // current
            CPUGovernorManager.GovernorMode.ONDEMAND,    // original
            availableGovernors,
            3000000L, // currentFreq
            1000000L, // minFreq
            4000000L  // maxFreq
        );

        assertEquals(0, info.getCoreId());
        assertEquals(CPUGovernorManager.GovernorMode.PERFORMANCE, info.getCurrentGovernor());
        assertEquals(CPUGovernorManager.GovernorMode.ONDEMAND, info.getOriginalGovernor());
        assertEquals(2, info.getAvailableGovernors().size());
        assertEquals(3000000L, info.getCurrentFrequency());
        assertEquals(1000000L, info.getMinFrequency());
        assertEquals(4000000L, info.getMaxFrequency());

        // Test toString
        String infoString = info.toString();
        assertTrue(infoString.contains("Core 0"));
        assertTrue(infoString.contains("PERFORMANCE"));
        assertTrue(infoString.contains("3,000") || infoString.contains("3000")); // Accept both formatted and unformatted
    }

    @Test
    @DisplayName("Governor status analysis")
    void testGovernorStatusAnalysis() {
        // Create mock governor info for 4 cores
        CPUGovernorManager.GovernorInfo[] infos = new CPUGovernorManager.GovernorInfo[4];
        List<CPUGovernorManager.GovernorMode> availableGovernors = Arrays.asList(
            CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.ONDEMAND,
            CPUGovernorManager.GovernorMode.POWERSAVE
        );

        // 2 performance cores, 1 powersave core, 1 ondemand core
        infos[0] = new CPUGovernorManager.GovernorInfo(0, CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.ONDEMAND, availableGovernors, 3000000L, 1000000L, 4000000L);
        infos[1] = new CPUGovernorManager.GovernorInfo(1, CPUGovernorManager.GovernorMode.PERFORMANCE,
            CPUGovernorManager.GovernorMode.ONDEMAND, availableGovernors, 3000000L, 1000000L, 4000000L);
        infos[2] = new CPUGovernorManager.GovernorInfo(2, CPUGovernorManager.GovernorMode.POWERSAVE,
            CPUGovernorManager.GovernorMode.ONDEMAND, availableGovernors, 1000000L, 1000000L, 4000000L);
        infos[3] = new CPUGovernorManager.GovernorInfo(3, CPUGovernorManager.GovernorMode.ONDEMAND,
            CPUGovernorManager.GovernorMode.ONDEMAND, availableGovernors, 2000000L, 1000000L, 4000000L);

        java.util.Map<Integer, CPUGovernorManager.GovernorInfo> coreGovernors = new java.util.HashMap<>();
        for (int i = 0; i < 4; i++) {
            coreGovernors.put(i, infos[i]);
        }

        CPUGovernorManager.GovernorStatus status = new CPUGovernorManager.GovernorStatus(coreGovernors);

        assertEquals(4, status.getTotalCores());
        assertEquals(2, status.getPerformanceCores());
        assertEquals(1, status.getPowerSaveCores());
        assertFalse(status.isAllCoresPerformance());

        // Test toString
        String statusString = status.toString();
        assertTrue(statusString.contains("2/4 performance"));
        assertTrue(statusString.contains("1 powersave"));
    }

    @Test
    @DisplayName("Error handling for platform failures")
    void testPlatformFailures() throws Exception {
        // Setup failure scenarios
        when(mockPlatformProvider.getCpuGovernor(0)).thenReturn(CPUGovernorManager.GovernorMode.ONDEMAND);
        when(mockPlatformProvider.setCpuGovernor(eq(0), any())).thenReturn(-1); // FAILURE

        governorManager.initialize();

        // Test set governor failure
        OperationResult<Void> result = governorManager.setGovernor(0, CPUGovernorManager.GovernorMode.PERFORMANCE);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @AfterEach
    void cleanup() {
        if (governorManager != null) {
            governorManager.shutdown();
        }
        // Reset all mocks for the next test
        reset(mockPlatformProvider, mockConfig);

        // Re-configure common mock behavior
        when(mockConfig.isGovernorControlEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.isAutoSetPerformanceGovernor()).thenReturn(false);
        when(mockConfig.isRestoreGovernorsOnShutdown()).thenReturn(true);

        when(mockPlatformProvider.supportsFeature("cpu_governor_control")).thenReturn(true);
        when(mockPlatformProvider.getCpuCount()).thenReturn(2);
    }
}