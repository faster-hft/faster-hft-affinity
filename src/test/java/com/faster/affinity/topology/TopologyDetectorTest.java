package com.faster.affinity.topology;

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
class TopologyDetectorTest {

    @Mock
    private PlatformProvider mockPlatformProvider;

    @Mock
    private AffinityConfig mockConfig;

    private TopologyDetector topologyDetector;

    @BeforeAll
    void setUp() {
        MockitoAnnotations.openMocks(this);
        topologyDetector = new TopologyDetector(mockPlatformProvider, mockConfig);
    }

    @BeforeEach
    void setUpEach() {
        reset(mockPlatformProvider, mockConfig);

        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.isParameterValidationEnabled()).thenReturn(true);

        when(mockPlatformProvider.getCpuCount()).thenReturn(8);
        when(mockPlatformProvider.getSocketCount()).thenReturn(2);
        when(mockPlatformProvider.getCoresPerSocket()).thenReturn(4);
        when(mockPlatformProvider.getNumaNodeCount()).thenReturn(2);
        when(mockPlatformProvider.getMaxCacheLevel()).thenReturn(3);
    }

    @Test
    @DisplayName("Topology detector initialization")
    void testInitialization() throws Exception {
        assertDoesNotThrow(() -> topologyDetector.initialize());
        assertEquals(8, topologyDetector.getCpuCount());
        assertEquals(2, topologyDetector.getSocketCount());
        assertEquals(4, topologyDetector.getCoresPerSocket());
        assertEquals(2, topologyDetector.getNumaNodeCount());
    }

    @Test
    @DisplayName("System topology detection")
    void testSystemTopologyDetection() throws Exception {
        topologyDetector.initialize();

        TopologyDetector.SystemTopology topology = topologyDetector.getTopology();

        assertNotNull(topology);
        assertEquals(8, topology.getCpuCount());
        assertEquals(2, topology.getSocketCount());
        assertEquals(4, topology.getCoresPerSocket());
        assertEquals(2, topology.getNumaNodeCount());
    }

    @Test
    @DisplayName("Cache level detection")
    void testCacheLevelDetection() throws Exception {
        when(mockPlatformProvider.getMaxCacheLevel()).thenReturn(3);
        when(mockPlatformProvider.getCacheSize(1)).thenReturn(32768L);   // 32KB L1
        when(mockPlatformProvider.getCacheSize(2)).thenReturn(262144L);  // 256KB L2
        when(mockPlatformProvider.getCacheSize(3)).thenReturn(8388608L); // 8MB L3

        topologyDetector.initialize();

        assertEquals(3, topologyDetector.getMaxCacheLevel());

        OperationResult<Long> cache1Result = topologyDetector.getCacheSize(1);
        assertTrue(cache1Result.isSuccess());
        assertEquals(32768L, cache1Result.getValue());

        OperationResult<Long> cache2Result = topologyDetector.getCacheSize(2);
        assertTrue(cache2Result.isSuccess());
        assertEquals(262144L, cache2Result.getValue());

        OperationResult<Long> cache3Result = topologyDetector.getCacheSize(3);
        assertTrue(cache3Result.isSuccess());
        assertEquals(8388608L, cache3Result.getValue());
    }

    @Test
    @DisplayName("Cache line size detection")
    void testCacheLineSizeDetection() throws Exception {
        when(mockPlatformProvider.getCacheLineSize()).thenReturn(64L);

        topologyDetector.initialize();

        assertEquals(64L, topologyDetector.getCacheLineSize());
    }

    @Test
    @DisplayName("Cache level cores detection")
    void testCacheLevelCoresDetection() throws Exception {
        when(mockPlatformProvider.getCacheLevelCores(eq(0), eq(3), any(), anyInt())).thenAnswer(invocation -> {
            long[] maskArray = invocation.getArgument(2);
            maskArray[0] = 0x0F; // Cores 0-3 share L3 cache
            return 0;
        });

        topologyDetector.initialize();

        OperationResult<BitSet> result = topologyDetector.getCacheLevelCores(0, 3);
        assertTrue(result.isSuccess());

        BitSet sharedCores = result.getValue();
        assertTrue(sharedCores.get(0));
        assertTrue(sharedCores.get(1));
        assertTrue(sharedCores.get(2));
        assertTrue(sharedCores.get(3));
        assertFalse(sharedCores.get(4));
    }

    @Test
    @DisplayName("Hyperthreaded core detection")
    void testHyperthreadedCoreDetection() throws Exception {
        when(mockPlatformProvider.isHyperThreadedCore(0)).thenReturn(1); // Yes
        when(mockPlatformProvider.isHyperThreadedCore(1)).thenReturn(0); // No

        topologyDetector.initialize();

        assertTrue(topologyDetector.isHyperThreadedCore(0));
        assertFalse(topologyDetector.isHyperThreadedCore(1));
    }

    @Test
    @DisplayName("Invalid core ID handling")
    void testInvalidCoreId() throws Exception {
        topologyDetector.initialize();

        OperationResult<BitSet> result = topologyDetector.getCacheLevelCores(999, 1);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Platform provider error handling")
    void testPlatformProviderErrors() throws Exception {
        when(mockPlatformProvider.getCacheLevelCores(anyInt(), anyInt(), any(), anyInt()))
            .thenThrow(new RuntimeException("Hardware error"));

        topologyDetector.initialize();

        OperationResult<BitSet> result = topologyDetector.getCacheLevelCores(0, 1);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("Topology caching behavior")
    void testTopologyCaching() throws Exception {
        when(mockConfig.isCachingEnabled()).thenReturn(true);

        TopologyDetector cachedDetector = new TopologyDetector(mockPlatformProvider, mockConfig);
        cachedDetector.initialize();

        TopologyDetector.SystemTopology topology1 = cachedDetector.getTopology();
        assertNotNull(topology1);

        TopologyDetector.SystemTopology topology2 = cachedDetector.getTopology();
        assertNotNull(topology2);

        assertSame(topology1, topology2);
    }

    @Test
    @DisplayName("Concurrent topology queries")
    void testConcurrentTopologyQueries() throws Exception {
        topologyDetector.initialize();

        Thread[] threads = new Thread[10];
        boolean[] results = new boolean[10];

        for (int i = 0; i < 10; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    TopologyDetector.SystemTopology topology = topologyDetector.getTopology();
                    results[threadIndex] = topology != null && topology.getCpuCount() == 8;
                } catch (Exception e) {
                    results[threadIndex] = false;
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join(5000);
        }

        for (boolean result : results) {
            assertTrue(result);
        }
    }

    @AfterEach
    void cleanup() {
        // TopologyDetector doesn't have shutdown method
    }
}