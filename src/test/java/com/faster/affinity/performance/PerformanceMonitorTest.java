package com.faster.affinity.performance;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceMonitorTest {

    @Mock
    private PlatformProvider mockPlatformProvider;

    @Mock
    private AffinityConfig mockConfig;

    private PerformanceMonitor performanceMonitor;

    @BeforeAll
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockConfig.isParameterValidationEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.isPerformanceCountersEnabled()).thenReturn(true);
        when(mockConfig.getPerformanceCounterUpdateIntervalMs()).thenReturn(100); // 100ms interval

        when(mockPlatformProvider.getCpuCount()).thenReturn(8);
        when(mockPlatformProvider.supportsFeature("performance_counters")).thenReturn(true);

        performanceMonitor = new PerformanceMonitor(mockPlatformProvider, mockConfig);
    }

    @Test
    @DisplayName("Performance monitor initialization")
    void testInitialization() throws Exception {
        assertDoesNotThrow(() -> performanceMonitor.initialize());
        assertTrue(performanceMonitor.isAvailable());
    }

    @Test
    @DisplayName("Core utilization monitoring")
    void testCoreUtilizationMonitoring() throws Exception {
        when(mockPlatformProvider.getCoreUtilization(0)).thenReturn(50.5);
        when(mockPlatformProvider.getCoreUtilization(1)).thenReturn(75.2);

        performanceMonitor.initialize();

        OperationResult<Double> core0Result = performanceMonitor.getCoreUtilization(0);
        assertTrue(core0Result.isSuccess());
        assertEquals(50.5, core0Result.getValue(), 0.01);

        OperationResult<Double> core1Result = performanceMonitor.getCoreUtilization(1);
        assertTrue(core1Result.isSuccess());
        assertEquals(75.2, core1Result.getValue(), 0.01);
    }

    @Test
    @DisplayName("Thread performance monitoring")
    void testThreadPerformanceMonitoring() throws Exception {
        long threadId = 12345L;
        when(mockPlatformProvider.getThreadCacheMisses(threadId)).thenReturn(1000L);
        when(mockPlatformProvider.getThreadContextSwitches(threadId)).thenReturn(50L);

        performanceMonitor.initialize();

        OperationResult<Long> cacheMissesResult = performanceMonitor.getThreadCacheMisses(threadId);
        assertTrue(cacheMissesResult.isSuccess());
        assertEquals(1000L, cacheMissesResult.getValue());

        OperationResult<Long> contextSwitchesResult = performanceMonitor.getThreadContextSwitches(threadId);
        assertTrue(contextSwitchesResult.isSuccess());
        assertEquals(50L, contextSwitchesResult.getValue());
    }

    @Test
    @DisplayName("Core cache miss monitoring")
    void testCoreCacheMissMonitoring() throws Exception {
        when(mockPlatformProvider.getCoreCacheMisses(0)).thenReturn(5000L);

        performanceMonitor.initialize();

        OperationResult<Long> result = performanceMonitor.getCoreCacheMisses(0);
        assertTrue(result.isSuccess());
        assertEquals(5000L, result.getValue());
    }

    @Test
    @DisplayName("Core performance snapshot")
    void testCorePerformanceSnapshot() throws Exception {
        when(mockPlatformProvider.getCoreUtilization(0)).thenReturn(65.5);
        when(mockPlatformProvider.getCoreCacheMisses(0)).thenReturn(1500L);

        performanceMonitor.initialize();

        OperationResult<PerformanceMonitor.CorePerformanceSnapshot> result =
            performanceMonitor.getCorePerformanceSnapshot(0);
        assertTrue(result.isSuccess());

        PerformanceMonitor.CorePerformanceSnapshot snapshot = result.getValue();
        assertEquals(0, snapshot.getCoreId());
        assertEquals(65.5, snapshot.getUtilization(), 0.01);
        assertEquals(1500L, snapshot.getCacheMisses());
    }

    @Test
    @DisplayName("Invalid core ID handling")
    void testInvalidCoreId() throws Exception {
        performanceMonitor.initialize();

        OperationResult<Double> result = performanceMonitor.getCoreUtilization(999);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Performance monitoring disabled")
    void testPerformanceMonitoringDisabled() throws Exception {
        // Create fresh mocks for this specific test
        PlatformProvider disabledPlatformProvider = mock(PlatformProvider.class);
        AffinityConfig disabledConfig = mock(AffinityConfig.class);

        when(disabledConfig.isPerformanceCountersEnabled()).thenReturn(false);
        when(disabledConfig.getPerformanceCounterUpdateIntervalMs()).thenReturn(100);
        when(disabledPlatformProvider.getCpuCount()).thenReturn(8);

        // Make isAvailable() return false by making getCoreUtilization throw exception
        when(disabledPlatformProvider.getCoreUtilization(0)).thenThrow(new RuntimeException("Performance counters disabled"));

        PerformanceMonitor disabledMonitor = new PerformanceMonitor(disabledPlatformProvider, disabledConfig);
        assertFalse(disabledMonitor.isAvailable());
    }

    @Test
    @DisplayName("Platform error handling")
    void testPlatformErrorHandling() throws Exception {
        // Create a monitor that properly initializes first, then setup error conditions
        performanceMonitor.initialize();

        // Setup platform to return negative value (indicates error)
        when(mockPlatformProvider.getCoreUtilization(anyInt())).thenReturn(-1.0);

        OperationResult<Double> result = performanceMonitor.getCoreUtilization(0);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Monitoring lifecycle")
    void testMonitoringLifecycle() throws Exception {
        performanceMonitor.initialize();

        assertDoesNotThrow(() -> performanceMonitor.startMonitoring());
        assertDoesNotThrow(() -> performanceMonitor.stopMonitoring());
    }

    @AfterEach
    void cleanup() {
        if (performanceMonitor != null) {
            performanceMonitor.shutdown();
        }
        // Reset all mocks for the next test
        reset(mockPlatformProvider, mockConfig);

        // Re-configure common mock behavior
        when(mockConfig.isParameterValidationEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.isPerformanceCountersEnabled()).thenReturn(true);
        when(mockConfig.getPerformanceCounterUpdateIntervalMs()).thenReturn(100); // 100ms interval

        when(mockPlatformProvider.getCpuCount()).thenReturn(8);
        when(mockPlatformProvider.supportsFeature("performance_counters")).thenReturn(true);
    }
}