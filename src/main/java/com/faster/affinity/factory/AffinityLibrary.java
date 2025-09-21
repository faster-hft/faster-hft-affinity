package com.faster.affinity.factory;

import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.core.CPUGovernorManager;
import com.faster.affinity.core.IRQManager;
import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.topology.TopologyDetector;

/**
 * Main interface for the Faster Thread Affinity Library - providing comprehensive
 * CPU affinity, NUMA, topology, and performance management capabilities specifically
 * designed for high-frequency trading (HFT) applications.
 *
 * <p>This interface provides a complete API for:
 * <ul>
 *   <li><strong>Thread Affinity Management</strong> - Pin threads to specific CPU cores</li>
 *   <li><strong>NUMA Optimization</strong> - NUMA-aware memory allocation and thread placement</li>
 *   <li><strong>Performance Monitoring</strong> - Real-time CPU utilization and performance metrics</li>
 *   <li><strong>IRQ Management</strong> - Interrupt isolation for trading cores</li>
 *   <li><strong>CPU Governor Control</strong> - Performance governor management for maximum throughput</li>
 *   <li><strong>System Topology Discovery</strong> - CPU cache hierarchy and core relationships</li>
 * </ul>
 *
 * <h3>Usage Examples</h3>
 *
 * <h4>Basic Thread Affinity</h4>
 * <pre>{@code
 * // Create library instance
 * AffinityConfig config = new AffinityConfig.Builder()
 *     .enableCaching(true)
 *     .build();
 * AffinityLibrary library = AffinityLibraryFactory.create(config);
 *
 * // Pin current thread to CPU cores 2-3
 * BitSet cpuMask = new BitSet();
 * cpuMask.set(2, 4); // CPUs 2-3
 * OperationResult<Void> result = library.setCurrentThreadAffinity(cpuMask);
 * if (result.isSuccess()) {
 *     // Thread is now pinned to CPUs 2-3
 * }
 * }</pre>
 *
 * <h4>HFT Trading Setup</h4>
 * <pre>{@code
 * // Setup dedicated cores for trading threads
 * BitSet tradingCores = new BitSet();
 * tradingCores.set(4, 8); // CPUs 4-7 for trading
 *
 * // Isolate IRQs from trading cores
 * library.isolateIRQsFromCores(tradingCores);
 *
 * // Set performance governor for maximum throughput
 * library.setAllCoresGovernor(CPUGovernorManager.GovernorMode.PERFORMANCE);
 *
 * // Pin trading thread to dedicated cores
 * library.setCurrentThreadAffinity(tradingCores);
 * }</pre>
 *
 * <h4>NUMA-Aware Setup</h4>
 * <pre>{@code
 * // Get NUMA node for network interface locality
 * SystemTopology topology = library.getSystemTopology();
 * OperationResult<BitSet> nodeCpus = library.getNumaNodeCpus(0);
 *
 * if (nodeCpus.isSuccess()) {
 *     // Pin threads to same NUMA node as network interface
 *     library.setCurrentThreadAffinity(nodeCpus.getValue());
 *
 *     // Allocate memory on same NUMA node
 *     OperationResult<Long> memory = library.allocateNumaMemory(0, 1024 * 1024);
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> All methods in this interface are thread-safe and can be
 * called concurrently from multiple threads. However, for maximum performance in hot-path
 * scenarios, consider using the {@link com.faster.affinity.core.AffinityManager} for
 * lock-free operations.
 *
 * <p><strong>Error Handling:</strong> All operations return {@link OperationResult} objects
 * that encapsulate both success/failure status and detailed error information for robust
 * error handling in production environments.
 *
 * <p><strong>Platform Support:</strong> This interface provides cross-platform compatibility
 * with full feature support on Linux and core functionality on Windows.
 *
 * @author Amar Mond
 * @since 1.0.0
 * @version 1.0.0
 * @see AffinityLibraryFactory
 * @see com.faster.affinity.config.AffinityConfig
 * @see com.faster.affinity.core.AffinityManager
 */
public interface AffinityLibrary {

    // ========================================
    // Basic Thread/Process Affinity Operations
    // ========================================

    /**
     * Sets the CPU affinity mask for the current thread, restricting it to run only
     * on the specified CPU cores. This is essential for HFT applications to ensure
     * consistent thread placement and minimize latency jitter.
     *
     * <p>In HFT environments, pinning critical threads (order processing, market data
     * handling) to dedicated CPU cores eliminates OS scheduler interference and provides
     * predictable execution timing.
     *
     * @param cpuMask BitSet representing the CPU cores where the thread can execute.
     *                Bit position corresponds to CPU core number (0-based). Must not be
     *                null or empty.
     * @return OperationResult containing success/failure status. On failure, contains
     *         detailed error information including potential causes like insufficient
     *         privileges or invalid CPU mask.
     *
     * @throws IllegalArgumentException if cpuMask is null or empty
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<Void> setCurrentThreadAffinity(java.util.BitSet cpuMask);
    /**
     * Retrieves the current CPU affinity mask for the calling thread.
     *
     * <p>This method returns which CPU cores the current thread is allowed to execute on.
     * Useful for verifying affinity settings and monitoring thread placement in HFT systems.
     *
     * @return OperationResult containing the current thread's CPU affinity mask as a BitSet.
     *         Each set bit represents a CPU core the thread can execute on. On failure,
     *         contains error details.
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<java.util.BitSet> getCurrentThreadAffinity();
    /**
     * Sets the CPU affinity mask for a specific thread identified by thread ID.
     *
     * <p>Allows setting affinity for threads other than the current thread. Useful for
     * managing thread pools or background threads in HFT applications.
     *
     * @param threadId The system thread ID (not Java Thread.getId())
     * @param cpuMask BitSet representing allowed CPU cores
     * @return OperationResult indicating success/failure with detailed error information
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<Void> setThreadAffinity(long threadId, java.util.BitSet cpuMask);
    OperationResult<java.util.BitSet> getThreadAffinity(long threadId);
    OperationResult<Void> setProcessAffinity(int processId, java.util.BitSet cpuMask);
    OperationResult<java.util.BitSet> getProcessAffinity(int processId);

    // ========================================
    // System Information
    // ========================================

    /**
     * Gets the system thread ID for the current thread.
     *
     * <p>Returns the native system thread ID (not Java's Thread.getId()). This ID
     * can be used with other system-level thread operations.
     *
     * @return The native system thread ID
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    long getCurrentThreadId();
    int getCurrentProcessId();
    /**
     * Retrieves comprehensive system capabilities and feature availability.
     *
     * <p>Returns detailed information about what features are supported on the current
     * platform, including NUMA support, performance counters, governor control, etc.
     * Essential for HFT applications to adapt to different hardware configurations.
     *
     * @return SystemCapabilities object containing platform feature information
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    AffinityManager.SystemCapabilities getSystemCapabilities();

    // Topology discovery
    TopologyDetector.SystemTopology getSystemTopology();
    OperationResult<TopologyDetector.CoreInfo> getCoreInfo(int coreId);
    OperationResult<java.util.List<TopologyDetector.CoreInfo>> getAllCoreInfo();
    OperationResult<java.util.BitSet> getCacheLevelCores(int coreId, int cacheLevel);
    boolean isHyperThreadedCore(int coreId);

    // Performance monitoring
    OperationResult<Double> getCoreUtilization(int coreId);
    OperationResult<PerformanceMonitor.CorePerformanceSnapshot> getCorePerformanceSnapshot(int coreId);
    OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> getSystemPerformanceSnapshot();
    OperationResult<java.util.List<Integer>> getHighUtilizationCores(double threshold);

    // ========================================
    // NUMA Operations (HFT Performance Critical)
    // ========================================

    /**
     * Retrieves the CPU cores associated with a specific NUMA node.
     *
     * <p>NUMA (Non-Uniform Memory Access) awareness is crucial for HFT applications
     * to achieve optimal performance. This method returns which CPU cores belong to
     * a given NUMA node, enabling applications to place threads and allocate memory
     * on the same NUMA node for minimum latency.
     *
     * <p><strong>HFT Use Case:</strong> Pin trading threads to the same NUMA node as
     * the network interface to minimize memory access latency for market data processing.
     *
     * @param nodeId The NUMA node ID (0-based)
     * @return OperationResult containing a BitSet of CPU cores in the specified NUMA node.
     *         Each set bit represents a CPU core belonging to that node.
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<java.util.BitSet> getNumaNodeCpus(int nodeId);
    /**
     * Retrieves memory information for a specific NUMA node.
     *
     * <p>Provides detailed memory statistics including total, free, and used memory
     * on the specified NUMA node. Critical for HFT applications to monitor memory
     * pressure and make informed allocation decisions.
     *
     * @param nodeId The NUMA node ID
     * @return OperationResult containing memory information for the node
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<NUMAManager.NumaNodeMemoryInfo> getNumaNodeMemoryInfo(int nodeId);
    OperationResult<Long> getNumaNodeDistance(int node1, int node2);
    OperationResult<Void> setThreadNumaAffinity(long threadId, int nodeId);
    /**
     * Allocates memory on a specific NUMA node for optimal locality.
     *
     * <p>Forces memory allocation on the specified NUMA node to ensure threads
     * and their data are co-located for minimum access latency. Essential for
     * HFT applications processing large amounts of market data.
     *
     * @param nodeId The target NUMA node for allocation
     * @param size Memory size to allocate in bytes
     * @return OperationResult containing the memory address on success
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<Long> allocateNumaMemory(int nodeId, long size);
    OperationResult<Void> freeNumaMemory(long address);

    // ========================================
    // IRQ (Interrupt Request) Management - HFT Critical
    // ========================================

    /**
     * Retrieves information about all interrupt requests (IRQs) in the system.
     *
     * <p>IRQ management is critical for HFT applications to isolate trading threads
     * from interrupt processing overhead. This method provides a complete view of
     * system interrupts for analysis and optimization.
     *
     * @return OperationResult containing a list of all IRQ information objects
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<java.util.List<IRQManager.IRQInfo>> getAllIRQs();
    OperationResult<IRQManager.IRQInfo> getIRQInfo(int irqNumber);
    OperationResult<java.util.BitSet> getIRQAffinity(int irqNumber);
    OperationResult<Void> setIRQAffinity(int irqNumber, java.util.BitSet cpuMask);
    OperationResult<Void> setDefaultIRQAffinity(java.util.BitSet housekeepingCores);
    /**
     * Isolates the specified CPU cores from interrupt processing by moving all
     * IRQs away from these cores.
     *
     * <p><strong>HFT Critical:</strong> This is one of the most important optimizations
     * for trading applications. By isolating trading cores from interrupt processing,
     * you eliminate unpredictable latency spikes caused by hardware interrupts.
     *
     * <p>Example: If cores 4-7 are dedicated to order processing, this method will
     * ensure no hardware interrupts are processed on those cores, providing consistent
     * execution timing.
     *
     * @param tradingCores BitSet of CPU cores to isolate from interrupt processing
     * @return OperationResult indicating success/failure of the isolation operation
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<Void> isolateIRQsFromCores(java.util.BitSet tradingCores);
    OperationResult<IRQManager.IRQIsolationStatus> getIRQIsolationStatus();
    OperationResult<Void> restoreOriginalIRQAffinities();

    // ========================================
    // CPU Governor Control - HFT Performance Optimization
    // ========================================

    /**
     * Retrieves the current CPU frequency governor for the specified core.
     *
     * <p>CPU governors control how the CPU frequency scales based on system load.
     * For HFT applications, using the 'performance' governor ensures maximum
     * CPU frequency at all times, eliminating frequency scaling delays.
     *
     * @param coreId The CPU core ID to query
     * @return OperationResult containing the current governor mode
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<CPUGovernorManager.GovernorMode> getCurrentGovernor(int coreId);
    OperationResult<Void> setGovernor(int coreId, CPUGovernorManager.GovernorMode governor);
    /**
     * Sets the CPU frequency governor for all CPU cores in the system.
     *
     * <p><strong>HFT Optimization:</strong> Setting all cores to 'PERFORMANCE' mode
     * ensures maximum CPU frequency is maintained constantly, eliminating the latency
     * overhead of frequency scaling decisions during critical trading operations.
     *
     * <p>This is typically called during HFT application startup to ensure optimal
     * performance characteristics throughout the trading session.
     *
     * @param governor The governor mode to set for all cores
     * @return OperationResult indicating success/failure of the operation
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    OperationResult<Void> setAllCoresGovernor(CPUGovernorManager.GovernorMode governor);
    OperationResult<CPUGovernorManager.GovernorStatus> getGovernorStatus();
    OperationResult<Void> restoreOriginalGovernors();

    // ========================================
    // Lifecycle Management
    // ========================================

    /**
     * Shuts down the affinity library and releases all associated resources.
     *
     * <p>This method should be called when the library is no longer needed to ensure
     * proper cleanup of native resources, thread pools, and cached objects. In HFT
     * applications, this is typically called during application shutdown.
     *
     * <p><strong>Important:</strong> After calling shutdown(), no other methods should
     * be invoked on this library instance.
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    void shutdown();
    /**
     * Checks whether the affinity library has been properly initialized and is
     * ready for operations.
     *
     * <p>Returns true if the library initialization completed successfully and
     * all required native resources are available. HFT applications should verify
     * initialization before starting critical trading operations.
     *
     * @return true if the library is initialized and ready for use, false otherwise
     *
     * @since 1.0.0
     * @author Amar Mond
     */
    boolean isInitialized();
}