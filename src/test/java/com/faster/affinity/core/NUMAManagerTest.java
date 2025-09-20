package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NUMAManagerTest {

    @Mock
    private PlatformProvider mockPlatformProvider;

    @Mock
    private AffinityConfig mockConfig;

    private NUMAManager numaManager;

    @BeforeAll
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockConfig.isParameterValidationEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);

        when(mockPlatformProvider.getNumaNodeCount()).thenReturn(2);
        when(mockPlatformProvider.getCpuCount()).thenReturn(8);

        numaManager = new NUMAManager(mockPlatformProvider, mockConfig);
    }

    @Test
    @DisplayName("NUMA manager initialization")
    void testInitialization() throws Exception {
        when(mockPlatformProvider.getNumaNodeCount()).thenReturn(2);

        assertDoesNotThrow(() -> numaManager.initialize());
        assertTrue(numaManager.isAvailable());
        assertEquals(2, numaManager.getNodeCount());
    }

    @Test
    @DisplayName("Get NUMA node CPUs")
    void testGetNumaNodeCpus() throws Exception {
        when(mockPlatformProvider.getNumaNodeCpus(eq(0), any(), anyInt())).thenAnswer(invocation -> {
            long[] mask = invocation.getArgument(1);
            mask[0] = 0x0F; // Cores 0-3
            return 0;
        });

        numaManager.initialize();

        OperationResult<BitSet> result = numaManager.getNodeCpus(0);
        assertTrue(result.isSuccess());

        BitSet cpus = result.getValue();
        assertTrue(cpus.get(0));
        assertTrue(cpus.get(1));
        assertTrue(cpus.get(2));
        assertTrue(cpus.get(3));
        assertFalse(cpus.get(4));
    }

    @Test
    @DisplayName("Set NUMA thread affinity")
    void testSetNumaAffinity() throws Exception {
        when(mockPlatformProvider.setNumaAffinity(anyLong(), eq(0))).thenReturn(0);
        when(mockPlatformProvider.getCurrentThreadId()).thenReturn(12345L);

        numaManager.initialize();

        OperationResult<Void> result = numaManager.setThreadNumaAffinity(12345L, 0);
        assertTrue(result.isSuccess());

        verify(mockPlatformProvider).setNumaAffinity(eq(12345L), eq(0));
    }

    @Test
    @DisplayName("Allocate NUMA memory")
    void testAllocateNumaMemory() throws Exception {
        long allocatedAddress = 0x12345678L;
        when(mockPlatformProvider.allocateNumaMemory(eq(0), eq(1024L))).thenReturn(allocatedAddress);

        numaManager.initialize();

        OperationResult<Long> result = numaManager.allocateMemory(0, 1024L);
        assertTrue(result.isSuccess());
        assertEquals(allocatedAddress, result.getValue());
    }

    @Test
    @DisplayName("Free NUMA memory")
    void testFreeNumaMemory() throws Exception {
        long address = 0x12345678L;
        long size = 1024L;

        // Setup allocation first
        when(mockPlatformProvider.allocateNumaMemory(eq(0), eq(size))).thenReturn(address);
        when(mockPlatformProvider.freeNumaMemory(eq(address), eq(size))).thenReturn(0);

        numaManager.initialize();

        // First allocate memory to track it internally
        OperationResult<Long> allocResult = numaManager.allocateMemory(0, size);
        assertTrue(allocResult.isSuccess());
        assertEquals(address, allocResult.getValue());

        // Now free the allocated memory
        OperationResult<Void> freeResult = numaManager.freeMemory(address);
        assertTrue(freeResult.isSuccess());

        verify(mockPlatformProvider).freeNumaMemory(eq(address), eq(size));
    }

    @Test
    @DisplayName("Get NUMA node memory info")
    void testGetNumaNodeMemoryInfo() throws Exception {
        when(mockPlatformProvider.getNumaNodeMemoryInfo(eq(0), any())).thenAnswer(invocation -> {
            long[] memInfo = invocation.getArgument(1);
            memInfo[0] = 8L * 1024 * 1024 * 1024; // 8GB total
            memInfo[1] = 2L * 1024 * 1024 * 1024; // 2GB free
            return 0;
        });

        numaManager.initialize();

        OperationResult<NUMAManager.NumaNodeMemoryInfo> result = numaManager.getNodeMemoryInfo(0);
        assertTrue(result.isSuccess());

        NUMAManager.NumaNodeMemoryInfo memInfo = result.getValue();
        assertEquals(8L * 1024 * 1024 * 1024, memInfo.getTotalBytes());
        assertEquals(2L * 1024 * 1024 * 1024, memInfo.getFreeBytes());
    }

    @Test
    @DisplayName("Get NUMA node distance")
    void testGetNumaNodeDistance() throws Exception {
        when(mockPlatformProvider.getNumaNodeDistance(0, 1)).thenReturn(20L);

        numaManager.initialize();

        OperationResult<Long> result = numaManager.getNodeDistance(0, 1);
        assertTrue(result.isSuccess());
        assertEquals(20L, result.getValue());
    }

    @Test
    @DisplayName("Invalid NUMA node handling")
    void testInvalidNumaNode() throws Exception {
        numaManager.initialize();

        OperationResult<BitSet> result = numaManager.getNodeCpus(999);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("NUMA memory allocation failure")
    void testMemoryAllocationFailure() throws Exception {
        when(mockPlatformProvider.allocateNumaMemory(anyInt(), anyLong())).thenReturn(0L);

        numaManager.initialize();

        OperationResult<Long> result = numaManager.allocateMemory(0, 1024L);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("NUMA not available")
    void testNumaNotAvailable() {
        when(mockPlatformProvider.getNumaNodeCount()).thenReturn(0);

        NUMAManager unavailableManager = new NUMAManager(mockPlatformProvider, mockConfig);
        assertFalse(unavailableManager.isAvailable());
    }

    @AfterEach
    void cleanup() {
        if (numaManager != null) {
            numaManager.shutdown();
        }
        // Reset all mocks for the next test
        reset(mockPlatformProvider, mockConfig);

        // Re-configure common mock behavior
        when(mockConfig.isParameterValidationEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);

        when(mockPlatformProvider.getNumaNodeCount()).thenReturn(2);
        when(mockPlatformProvider.getCpuCount()).thenReturn(8);
    }
}