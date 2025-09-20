package com.faster.affinity.core;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.platform.PlatformProvider;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IRQManagerTest {

    @Mock
    private PlatformProvider mockPlatformProvider;

    @Mock
    private AffinityConfig mockConfig;

    private IRQManager irqManager;

    @BeforeAll
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Configure mock config
        when(mockConfig.isIRQManagementEnabled()).thenReturn(true);
        when(mockConfig.isCachingEnabled()).thenReturn(false);
        when(mockConfig.getIRQScanInterval()).thenReturn(30000L);
        when(mockConfig.isStrictIRQIsolation()).thenReturn(false);
        when(mockConfig.isRestoreIRQAffinitiesOnShutdown()).thenReturn(true);

        // Configure mock platform provider
        when(mockPlatformProvider.supportsFeature("irq_management")).thenReturn(true);
        when(mockPlatformProvider.getCpuCount()).thenReturn(8);

        irqManager = new IRQManager(mockPlatformProvider, mockConfig);
    }

    @Test
    @DisplayName("IRQManager initialization with IRQ support")
    void testInitialization() throws Exception {
        // Mock IRQ discovery
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24, 25, 26});
        when(mockPlatformProvider.getIrqDescription(24)).thenReturn("eth0-TxRx-0");
        when(mockPlatformProvider.getIrqDescription(25)).thenReturn("eth0-TxRx-1");
        when(mockPlatformProvider.getIrqDescription(26)).thenReturn("timer");

        assertDoesNotThrow(() -> irqManager.initialize());
        assertTrue(irqManager.isAvailable());
    }

    @Test
    @DisplayName("IRQManager initialization without IRQ support")
    void testInitializationWithoutSupport() throws Exception {
        when(mockPlatformProvider.supportsFeature("irq_management")).thenReturn(false);

        IRQManager unsupportedManager = new IRQManager(mockPlatformProvider, mockConfig);
        assertDoesNotThrow(() -> unsupportedManager.initialize());
        assertFalse(unsupportedManager.isAvailable());
    }

    @Test
    @DisplayName("Get all IRQs")
    void testGetAllIRQs() throws Exception {
        // Setup
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24, 25});
        when(mockPlatformProvider.getIrqDescription(24)).thenReturn("eth0-TxRx-0");
        when(mockPlatformProvider.getIrqDescription(25)).thenReturn("nvme0q1");

        // Mock affinity calls
        when(mockPlatformProvider.getIrqAffinity(eq(24), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0x01; // Core 0
                return 0; // SUCCESS
            });
        when(mockPlatformProvider.getIrqAffinity(eq(25), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0x02; // Core 1
                return 0; // SUCCESS
            });

        irqManager.initialize();
        OperationResult<List<IRQManager.IRQInfo>> result = irqManager.getAllIRQs();

        assertTrue(result.isSuccess());
        assertNotNull(result.getValue());
        assertEquals(2, result.getValue().size());

        IRQManager.IRQInfo networkIRQ = result.getValue().stream()
            .filter(irq -> irq.getIrqNumber() == 24)
            .findFirst()
            .orElse(null);
        assertNotNull(networkIRQ);
        assertEquals(IRQManager.IRQType.NETWORK, networkIRQ.getType());

        IRQManager.IRQInfo storageIRQ = result.getValue().stream()
            .filter(irq -> irq.getIrqNumber() == 25)
            .findFirst()
            .orElse(null);
        assertNotNull(storageIRQ);
        assertEquals(IRQManager.IRQType.STORAGE, storageIRQ.getType());
    }

    @Test
    @DisplayName("Set IRQ affinity")
    void testSetIRQAffinity() throws Exception {
        // Setup
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24});
        when(mockPlatformProvider.getIrqDescription(24)).thenReturn("eth0-TxRx-0");
        when(mockPlatformProvider.getIrqAffinity(eq(24), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0x01; // Current: Core 0
                return 0; // SUCCESS
            });
        when(mockPlatformProvider.setIrqAffinity(eq(24), any(long[].class), anyInt()))
            .thenReturn(0); // SUCCESS

        irqManager.initialize();

        // Set affinity to cores 4-5
        BitSet newMask = new BitSet();
        newMask.set(4);
        newMask.set(5);

        OperationResult<Void> result = irqManager.setIRQAffinity(24, newMask);

        assertTrue(result.isSuccess());
        verify(mockPlatformProvider).setIrqAffinity(eq(24), any(long[].class), anyInt());
    }

    @Test
    @DisplayName("Isolate IRQs from trading cores")
    void testIsolateIRQsFromCores() throws Exception {
        // Setup
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24, 25});
        when(mockPlatformProvider.getIrqDescription(24)).thenReturn("eth0-TxRx-0");
        when(mockPlatformProvider.getIrqDescription(25)).thenReturn("timer");
        when(mockPlatformProvider.getIrqAffinity(anyInt(), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0xFF; // All cores
                return 0; // SUCCESS
            });
        when(mockPlatformProvider.setIrqAffinity(anyInt(), any(long[].class), anyInt()))
            .thenReturn(0); // SUCCESS

        irqManager.initialize();

        // Isolate cores 0-3 for trading
        BitSet tradingCores = new BitSet();
        tradingCores.set(0, 4);

        OperationResult<Void> result = irqManager.isolateIRQsFromCores(tradingCores);

        assertTrue(result.isSuccess());

        // Should have moved network IRQ (24) but not timer IRQ (25)
        verify(mockPlatformProvider, times(1)).setIrqAffinity(eq(24), any(long[].class), anyInt());
        verify(mockPlatformProvider, never()).setIrqAffinity(eq(25), any(long[].class), anyInt());
    }

    @Test
    @DisplayName("Get IRQ isolation status")
    void testGetIRQIsolationStatus() throws Exception {
        // Setup
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24, 25});
        when(mockPlatformProvider.getIrqDescription(24)).thenReturn("eth0-TxRx-0");
        when(mockPlatformProvider.getIrqDescription(25)).thenReturn("nvme0q1");
        when(mockPlatformProvider.getIrqAffinity(eq(24), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0x10; // Core 4 only
                return 0; // SUCCESS
            });
        when(mockPlatformProvider.getIrqAffinity(eq(25), any(long[].class), anyInt()))
            .thenAnswer(invocation -> {
                long[] mask = invocation.getArgument(1);
                mask[0] = 0x0F; // Cores 0-3 (multiple cores)
                return 0; // SUCCESS
            });

        irqManager.initialize();

        OperationResult<IRQManager.IRQIsolationStatus> result = irqManager.getIRQIsolationStatus();

        assertTrue(result.isSuccess());
        IRQManager.IRQIsolationStatus status = result.getValue();
        assertEquals(2, status.getTotalIRQs());
        assertTrue(status.getIsolatedCores().contains(4)); // IRQ 24 isolated to core 4
        assertFalse(status.getNonIsolatedCores().isEmpty()); // IRQ 25 on multiple cores
        assertFalse(status.getConflicts().isEmpty()); // IRQ 25 should be flagged as conflict
    }

    @Test
    @DisplayName("Disabled IRQ management")
    void testDisabledIRQManagement() throws Exception {
        when(mockConfig.isIRQManagementEnabled()).thenReturn(false);

        IRQManager disabledManager = new IRQManager(mockPlatformProvider, mockConfig);
        disabledManager.initialize();

        assertFalse(disabledManager.isAvailable());

        OperationResult<List<IRQManager.IRQInfo>> result = disabledManager.getAllIRQs();
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Invalid IRQ number handling")
    void testInvalidIRQNumber() throws Exception {
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24});
        irqManager.initialize();

        OperationResult<IRQManager.IRQInfo> result = irqManager.getIRQInfo(999);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Empty CPU mask validation")
    void testEmptyCpuMaskValidation() throws Exception {
        when(mockPlatformProvider.getAllIrqNumbers()).thenReturn(new int[]{24});
        irqManager.initialize();

        BitSet emptyMask = new BitSet();
        OperationResult<Void> result = irqManager.setIRQAffinity(24, emptyMask);
        assertFalse(result.isSuccess());
    }

    @AfterEach
    void cleanup() {
        if (irqManager != null) {
            irqManager.shutdown();
        }
    }
}