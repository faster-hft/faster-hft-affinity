package com.faster.affinity.factory;

import com.faster.affinity.core.AffinityManager;
import com.faster.affinity.core.CPUGovernorManager;
import com.faster.affinity.core.IRQManager;
import com.faster.affinity.core.NUMAManager;
import com.faster.affinity.exceptions.OperationResult;
import com.faster.affinity.performance.PerformanceMonitor;
import com.faster.affinity.topology.TopologyDetector;

/**
 * Main interface for the HFT (High-Frequency Trading) Affinity Library.
 *
 * <p>This library provides comprehensive CPU affinity, NUMA, and performance monitoring
 * capabilities specifically designed for high-frequency trading applications that require
 * deterministic performance and minimal latency.</p>
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li><b>Thread/Process Affinity:</b> Pin threads and processes to specific CPU cores</li>
 *   <li><b>NUMA Operations:</b> Control Non-Uniform Memory Access for optimal memory placement</li>
 *   <li><b>IRQ Management:</b> Isolate interrupt handling from trading cores</li>
 *   <li><b>CPU Governor Control:</b> Set CPU frequency scaling for consistent performance</li>
 *   <li><b>Topology Discovery:</b> Understand CPU cache hierarchy and core relationships</li>
 *   <li><b>Performance Monitoring:</b> Real-time performance metrics and utilization tracking</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Initialize the library
 * AffinityLibrary affinity = AffinityLibraryFactory.createInstance();
 *
 * // Pin current thread to core 0
 * BitSet cpuMask = new BitSet();
 * cpuMask.set(0);
 * OperationResult<Void> result = affinity.setCurrentThreadAffinity(cpuMask);
 *
 * if (result.isSuccess()) {
 *     System.out.println("Thread pinned to core 0");
 * } else {
 *     System.err.println("Failed: " + result.getError().getMessage());
 * }
 *
 * // Set all cores to performance governor for maximum frequency
 * affinity.setAllCoresGovernor(CPUGovernorManager.GovernorMode.PERFORMANCE);
 *
 * // Clean up
 * affinity.shutdown();
 * }</pre>
 *
 * <h2>Thread Safety:</h2>
 * <p>All methods in this interface are thread-safe and can be called concurrently
 * from multiple threads.</p>
 *
 * <h2>Platform Support:</h2>
 * <ul>
 *   <li><b>Linux:</b> Full feature support including NUMA, IRQ management, and governor control</li>
 *   <li><b>Windows:</b> Basic affinity operations and performance monitoring</li>
 *   <li><b>macOS:</b> Limited affinity support (system restrictions)</li>
 * </ul>
 *
 * @author Amar Mond
 * @version 1.2.0
 * @since 1.0.0
 * @see AffinityLibraryFactory
 * @see OperationResult
 */
public interface AffinityLibrary {

    // ================================
    // Basic Thread/Process Affinity
    // ================================

    /**
     * Sets the CPU affinity mask for the current thread.
     *
     * <p>This method pins the current thread to the specified set of CPU cores,
     * ensuring that the thread will only execute on those cores. This is critical
     * for HFT applications to avoid context switches and ensure predictable latency.</p>
     *
     * @param cpuMask BitSet where each set bit represents a CPU core ID that the thread
     *                can be scheduled on. Core IDs start from 0.
     * @return OperationResult containing success/failure status and any error information
     * @throws IllegalArgumentException if cpuMask is null or empty
     * @see #getCurrentThreadAffinity()
     * @see #setThreadAffinity(long, java.util.BitSet)
     */
    OperationResult<Void> setCurrentThreadAffinity(java.util.BitSet cpuMask);
    /**
     * Pins the current thread to a single CPU core.
     *
     * <p>Convenience overload of {@link #setCurrentThreadAffinity(java.util.BitSet)}
     * for the common case of pinning a thread to exactly one core. Equivalent to
     * building a {@code BitSet} with only the bit {@code cpu} set and passing it
     * to the mask-based variant.</p>
     *
     * @param cpu the CPU core ID to pin the current thread to (0-based). Must be
     *            non-negative and less than the number of CPUs detected on the system.
     * @return OperationResult containing success/failure status; fails with an
     *         invalid-parameter error if {@code cpu} is negative or exceeds the
     *         detected CPU count
     * @since 1.2.0
     * @see #setCurrentThreadAffinity(java.util.BitSet)
     * @see #setThreadAffinity(long, int)
     */
    OperationResult<Void> setCurrentThreadAffinity(int cpu);
    /**
     * Retrieves the current CPU affinity mask for the calling thread.
     *
     * @return OperationResult containing the current thread's CPU affinity mask,
     *         or error information if the operation fails
     * @see #setCurrentThreadAffinity(java.util.BitSet)
     */
    OperationResult<java.util.BitSet> getCurrentThreadAffinity();
    /**
     * Sets the CPU affinity mask for a specific thread.
     *
     * @param threadId the system thread ID to modify affinity for
     * @param cpuMask BitSet representing the allowed CPU cores
     * @return OperationResult containing success/failure status
     * @see #getThreadAffinity(long)
     * @see #getCurrentThreadId()
     */
    OperationResult<Void> setThreadAffinity(long threadId, java.util.BitSet cpuMask);
    /**
     * Pins a specific thread to a single CPU core.
     *
     * <p>Convenience overload of {@link #setThreadAffinity(long, java.util.BitSet)}
     * for the common case of pinning a thread to exactly one core. Equivalent to
     * building a {@code BitSet} with only the bit {@code cpu} set and passing it
     * to the mask-based variant.</p>
     *
     * @param threadId the system thread ID to modify affinity for
     * @param cpu the CPU core ID to pin the thread to (0-based). Must be
     *            non-negative and less than the number of CPUs detected on the system.
     * @return OperationResult containing success/failure status; fails with an
     *         invalid-parameter error if {@code cpu} is negative or exceeds the
     *         detected CPU count
     * @since 1.2.0
     * @see #setThreadAffinity(long, java.util.BitSet)
     * @see #setCurrentThreadAffinity(int)
     */
    OperationResult<Void> setThreadAffinity(long threadId, int cpu);
    /**
     * Retrieves the CPU affinity mask for a specific thread.
     *
     * @param threadId the system thread ID to query
     * @return OperationResult containing the thread's CPU affinity mask
     */
    OperationResult<java.util.BitSet> getThreadAffinity(long threadId);
    /**
     * Sets the CPU affinity mask for an entire process.
     *
     * <p>This affects all threads within the specified process.</p>
     *
     * @param processId the process ID to modify
     * @param cpuMask BitSet representing the allowed CPU cores
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setProcessAffinity(int processId, java.util.BitSet cpuMask);
    /**
     * Retrieves the CPU affinity mask for a process.
     *
     * @param processId the process ID to query
     * @return OperationResult containing the process's CPU affinity mask
     */
    OperationResult<java.util.BitSet> getProcessAffinity(int processId);

    // ================================
    // System Information
    // ================================

    /**
     * Returns the current thread's system thread ID.
     *
     * <p>This ID can be used with other affinity operations and matches
     * the thread ID shown in system monitoring tools.</p>
     *
     * @return the current thread's system-level thread ID
     */
    long getCurrentThreadId();
    /**
     * Returns the current process's system process ID.
     *
     * @return the current process's system-level process ID
     */
    int getCurrentProcessId();
    /**
     * Retrieves detailed information about the system's affinity capabilities.
     *
     * <p>This includes supported features, available CPU cores, NUMA nodes,
     * and platform-specific limitations.</p>
     *
     * @return SystemCapabilities object describing what operations are supported
     */
    AffinityManager.SystemCapabilities getSystemCapabilities();

    // ================================
    // Topology Discovery
    // ================================

    /**
     * Discovers and returns the complete system CPU topology.
     *
     * <p>This includes information about CPU cores, cache hierarchy,
     * NUMA nodes, and core relationships (e.g., hyper-threading pairs).</p>
     *
     * @return SystemTopology object containing complete topology information
     */
    TopologyDetector.SystemTopology getSystemTopology();
    /**
     * Retrieves detailed information about a specific CPU core.
     *
     * @param coreId the CPU core ID to query (0-based)
     * @return OperationResult containing CoreInfo with cache levels, NUMA node, etc.
     */
    OperationResult<TopologyDetector.CoreInfo> getCoreInfo(int coreId);
    /**
     * Retrieves information about all CPU cores in the system.
     *
     * @return OperationResult containing a list of CoreInfo objects for all cores
     */
    OperationResult<java.util.List<TopologyDetector.CoreInfo>> getAllCoreInfo();
    /**
     * Finds all CPU cores that share a specific cache level with the given core.
     *
     * <p>This is useful for grouping threads that should share cache for performance.</p>
     *
     * @param coreId the reference CPU core ID
     * @param cacheLevel the cache level to check (1=L1, 2=L2, 3=L3)
     * @return OperationResult containing BitSet of cores sharing the cache level
     */
    OperationResult<java.util.BitSet> getCacheLevelCores(int coreId, int cacheLevel);
    /**
     * Checks if the specified core is a hyper-threaded (logical) core.
     *
     * @param coreId the CPU core ID to check
     * @return true if the core is a hyper-threaded logical core, false if physical
     */
    boolean isHyperThreadedCore(int coreId);

    // ================================
    // Performance Monitoring
    // ================================

    /**
     * Retrieves the current CPU utilization percentage for a specific core.
     *
     * <p>This provides real-time utilization data useful for load balancing
     * and identifying idle cores for thread placement.</p>
     *
     * @param coreId the CPU core ID to monitor
     * @return OperationResult containing utilization percentage (0.0 to 100.0)
     */
    OperationResult<Double> getCoreUtilization(int coreId);
    /**
     * Captures a comprehensive performance snapshot for a specific CPU core.
     *
     * <p>Includes metrics like cache hit/miss rates, instruction throughput,
     * branch prediction accuracy, and memory bandwidth utilization.</p>
     *
     * @param coreId the CPU core ID to snapshot
     * @return OperationResult containing detailed performance metrics
     */
    OperationResult<PerformanceMonitor.CorePerformanceSnapshot> getCorePerformanceSnapshot(int coreId);
    /**
     * Captures a comprehensive performance snapshot for the entire system.
     *
     * <p>Aggregates performance data across all CPU cores and provides
     * system-wide metrics for overall performance analysis.</p>
     *
     * @return OperationResult containing system-wide performance metrics
     */
    OperationResult<PerformanceMonitor.SystemPerformanceSnapshot> getSystemPerformanceSnapshot();
    /**
     * Identifies CPU cores with utilization above the specified threshold.
     *
     * <p>Useful for finding busy cores to avoid when placing latency-sensitive threads.</p>
     *
     * @param threshold utilization threshold percentage (0.0 to 100.0)
     * @return OperationResult containing list of core IDs above the threshold
     */
    OperationResult<java.util.List<Integer>> getHighUtilizationCores(double threshold);

    // ================================
    // NUMA (Non-Uniform Memory Access) Operations
    // ================================

    /**
     * Returns the NUMA manager for direct access to NUMA operations.
     *
     * <p>The returned manager exposes the full NUMA API — node discovery,
     * thread-to-node affinity, and node-local memory allocation — for callers
     * that prefer working with the manager directly rather than through the
     * delegating methods on this interface (such as
     * {@link #setThreadNumaAffinity(long, int)} and
     * {@link #allocateNumaMemory(int, long)}).</p>
     *
     * @return the NUMAManager instance backing this library's NUMA operations
     * @throws IllegalStateException if the library has been shut down or NUMA
     *         operations are disabled in the configuration
     * @since 1.2.0
     * @see NUMAManager
     */
    NUMAManager getNUMAManager();
    /**
     * Retrieves the set of CPU cores belonging to a specific NUMA node.
     *
     * <p>NUMA-aware thread placement ensures threads access local memory
     * for optimal performance in multi-socket systems.</p>
     *
     * @param nodeId the NUMA node ID to query
     * @return OperationResult containing BitSet of CPU cores in the NUMA node
     */
    OperationResult<java.util.BitSet> getNumaNodeCpus(int nodeId);
    /**
     * Retrieves memory information for a specific NUMA node.
     *
     * <p>Includes total memory, available memory, and allocation statistics.</p>
     *
     * @param nodeId the NUMA node ID to query
     * @return OperationResult containing memory information for the node
     */
    OperationResult<NUMAManager.NumaNodeMemoryInfo> getNumaNodeMemoryInfo(int nodeId);
    /**
     * Gets the NUMA distance between two nodes.
     *
     * <p>Lower distances indicate faster memory access between nodes.
     * Used for optimal thread and memory placement decisions.</p>
     *
     * @param node1 the first NUMA node ID
     * @param node2 the second NUMA node ID
     * @return OperationResult containing the distance metric between nodes
     */
    OperationResult<Long> getNumaNodeDistance(int node1, int node2);
    /**
     * Sets NUMA affinity for a thread to a specific node.
     *
     * <p>The thread will preferentially access memory from the specified node.</p>
     *
     * @param threadId the thread ID to modify
     * @param nodeId the target NUMA node ID
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setThreadNumaAffinity(long threadId, int nodeId);
    /**
     * Allocates memory on a specific NUMA node.
     *
     * <p>Ensures memory is physically located on the specified node
     * for optimal access performance.</p>
     *
     * @param nodeId the NUMA node to allocate memory on
     * @param size the amount of memory to allocate in bytes
     * @return OperationResult containing the memory address or error
     */
    OperationResult<Long> allocateNumaMemory(int nodeId, long size);
    /**
     * Frees NUMA-allocated memory.
     *
     * @param address the memory address returned by allocateNumaMemory
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> freeNumaMemory(long address);

    // ================================
    // IRQ (Interrupt Request) Management
    // ================================

    /**
     * Retrieves information about all IRQ (interrupt) sources in the system.
     *
     * <p>Critical for HFT applications to understand and control interrupt
     * distribution to avoid disrupting trading threads.</p>
     *
     * @return OperationResult containing list of all IRQ information
     */
    OperationResult<java.util.List<IRQManager.IRQInfo>> getAllIRQs();
    /**
     * Retrieves detailed information about a specific IRQ.
     *
     * @param irqNumber the IRQ number to query
     * @return OperationResult containing IRQ information (device, affinity, etc.)
     */
    OperationResult<IRQManager.IRQInfo> getIRQInfo(int irqNumber);
    /**
     * Gets the current CPU affinity for a specific IRQ.
     *
     * @param irqNumber the IRQ number to query
     * @return OperationResult containing BitSet of CPUs handling this IRQ
     */
    OperationResult<java.util.BitSet> getIRQAffinity(int irqNumber);
    /**
     * Sets the CPU affinity for a specific IRQ.
     *
     * <p>Directs interrupt handling to specific CPU cores, allowing
     * isolation of trading cores from interrupt processing.</p>
     *
     * @param irqNumber the IRQ number to modify
     * @param cpuMask BitSet of CPUs that should handle this IRQ
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setIRQAffinity(int irqNumber, java.util.BitSet cpuMask);
    /**
     * Sets default IRQ affinity to a set of housekeeping cores.
     *
     * <p>Moves all IRQ handling to designated housekeeping cores,
     * freeing up other cores for dedicated trading workloads.</p>
     *
     * @param housekeepingCores BitSet of cores designated for IRQ handling
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setDefaultIRQAffinity(java.util.BitSet housekeepingCores);
    /**
     * Isolates specified cores from all IRQ handling.
     *
     * <p>Ensures trading cores are completely free from interrupt
     * processing for maximum deterministic performance.</p>
     *
     * @param tradingCores BitSet of cores to isolate from IRQ handling
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> isolateIRQsFromCores(java.util.BitSet tradingCores);
    /**
     * Gets the current IRQ isolation status.
     *
     * <p>Shows which cores are isolated and the current IRQ distribution.</p>
     *
     * @return OperationResult containing IRQ isolation status information
     */
    OperationResult<IRQManager.IRQIsolationStatus> getIRQIsolationStatus();
    /**
     * Restores all IRQ affinities to their original system defaults.
     *
     * <p>Used during cleanup to return the system to its original state.</p>
     *
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> restoreOriginalIRQAffinities();

    // ================================
    // CPU Governor Control (HFT Performance Optimization)
    // ================================

    /**
     * Gets the current CPU frequency governor for a specific core.
     *
     * <p>CPU governors control frequency scaling behavior. For HFT applications,
     * the PERFORMANCE governor is typically preferred to maintain maximum
     * frequency for consistent latency.</p>
     *
     * @param coreId the CPU core ID to query
     * @return OperationResult containing the current governor mode
     */
    OperationResult<CPUGovernorManager.GovernorMode> getCurrentGovernor(int coreId);
    /**
     * Sets the CPU frequency governor for a specific core.
     *
     * <p>Available governors include:
     * <ul>
     *   <li>PERFORMANCE - Maximum frequency (recommended for HFT)</li>
     *   <li>POWERSAVE - Minimum frequency</li>
     *   <li>ONDEMAND - Dynamic scaling based on load</li>
     *   <li>CONSERVATIVE - Gradual frequency changes</li>
     * </ul></p>
     *
     * @param coreId the CPU core ID to modify
     * @param governor the governor mode to set
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setGovernor(int coreId, CPUGovernorManager.GovernorMode governor);
    /**
     * Sets the CPU frequency governor for all cores in the system.
     *
     * <p>Convenient method for applying the same governor policy
     * across all CPU cores simultaneously.</p>
     *
     * @param governor the governor mode to set for all cores
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> setAllCoresGovernor(CPUGovernorManager.GovernorMode governor);
    /**
     * Gets comprehensive governor status for all CPU cores.
     *
     * <p>Provides overview of governor settings across the entire system.</p>
     *
     * @return OperationResult containing system-wide governor status
     */
    OperationResult<CPUGovernorManager.GovernorStatus> getGovernorStatus();
    /**
     * Restores all CPU governors to their original system defaults.
     *
     * <p>Used during cleanup to return frequency scaling to the
     * original system configuration.</p>
     *
     * @return OperationResult containing success/failure status
     */
    OperationResult<Void> restoreOriginalGovernors();

    // ================================
    // Lifecycle Management
    // ================================

    /**
     * Shuts down the affinity library and cleans up all resources.
     *
     * <p>This method:
     * <ul>
     *   <li>Restores original IRQ affinities (if configured)</li>
     *   <li>Restores original CPU governors (if configured)</li>
     *   <li>Frees allocated NUMA memory</li>
     *   <li>Releases native resources</li>
     * </ul></p>
     *
     * <p><b>Important:</b> Always call this method before application exit
     * to properly clean up system modifications.</p>
     */
    void shutdown();
    /**
     * Checks if the affinity library is properly initialized.
     *
     * @return true if the library is initialized and ready for use
     */
    boolean isInitialized();
}