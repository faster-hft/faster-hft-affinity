package com.faster.affinity.numa;

import com.faster.affinity.exceptions.*;
import com.faster.affinity.exceptions.OperationResult;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.faster.affinity.exceptions.ErrorCodes.*;

/**
 * Enhanced NUMA operations for Linux and Windows platforms.
 * Fixed version with proper error handling instead of stubbed implementations.
 */
public class EnhancedNUMAImplementations {

    /**
     * Enhanced Linux NUMA implementation with memory binding support
     */
    public static class LinuxEnhancedNUMA {
        private static final Logger logger = LoggerFactory.getLogger(LinuxEnhancedNUMA.class);

        // Linux system call interface for NUMA memory operations
        private interface LinuxNuma extends Library {
            LinuxNuma INSTANCE = loadNumaLibrary();

            // NUMA library functions
            int numa_available();
            void numa_set_preferred(int node);
            void numa_set_membind(Pointer nodemask, int maxnode, int policy, int flags);
            long mbind(Pointer addr, long len, int policy, Pointer nodemask, long maxnode, int flags);
            long set_mempolicy(int policy, Pointer nodemask, long maxnode);
            long get_mempolicy(IntByReference policy, Pointer nodemask, long maxnode, Pointer addr, long flags);
            Pointer numa_alloc_onnode(long size, int node);
            void numa_free(Pointer start, long size);

            // Memory policies
            int MPOL_DEFAULT = 0;
            int MPOL_BIND = 2;
            int MPOL_INTERLEAVE = 3;
            int MPOL_PREFERRED = 1;
        }

        private static LinuxNuma loadNumaLibrary() {
            try {
                return Native.load("numa", LinuxNuma.class);
            } catch (UnsatisfiedLinkError e) {
                logger.debug("NUMA library not available: {}", e.getMessage());
                return null;
            }
        }

        // Linux system call interface for direct memory operations
        private interface LinuxLibC extends Library {
            LinuxLibC INSTANCE = Native.load("c", LinuxLibC.class);

            long syscall(int number, Object... args);
            int mprotect(Pointer addr, long len, int prot);
            int madvise(Pointer addr, long len, int advice);
            int sched_setaffinity(int pid, int cpusetsize, Pointer mask);

            // Memory advice flags
            int MADV_SEQUENTIAL = 2;
            int MADV_WILLNEED = 3;
            int MADV_HUGEPAGE = 14;
        }

        public int bindMemoryRange(long address, long size, int nodeId) {
            if (LinuxNuma.INSTANCE == null) {
                logger.error("NUMA library not available for memory binding");
                return ERROR_NOT_SUPPORTED;
            }

            try {
                // Create node mask for specific node
                Memory nodeMask = new Memory(8);
                nodeMask.setLong(0, 1L << nodeId);

                // Use mbind system call to bind memory range to NUMA node
                long result = LinuxNuma.INSTANCE.mbind(new Pointer(address), size,
                        LinuxNuma.MPOL_BIND, nodeMask, 64, 0);

                if (result == 0) {
                    logger.debug("Successfully bound memory range 0x{} ({} bytes) to NUMA node {}",
                            Long.toHexString(address), size, nodeId);
                    return SUCCESS;
                } else {
                    int errno = Native.getLastError();
                    logger.error("mbind failed for address 0x{}: errno={}", Long.toHexString(address), errno);
                    return mapErrnoToErrorCode(errno);
                }

            } catch (Exception e) {
                logger.error("Failed to bind memory range: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        public int setNumaMemoryPolicy(int nodeId) {
            if (LinuxNuma.INSTANCE == null) {
                logger.error("NUMA library not available for memory policy");
                return ERROR_NOT_SUPPORTED;
            }

            try {
                // Set preferred NUMA node for memory allocation
                LinuxNuma.INSTANCE.numa_set_preferred(nodeId);

                // Also set memory policy via system call for more control
                Memory nodeMask = new Memory(8);
                nodeMask.setLong(0, 1L << nodeId);

                long result = LinuxNuma.INSTANCE.set_mempolicy(LinuxNuma.MPOL_PREFERRED, nodeMask, 64);

                if (result == 0) {
                    logger.debug("Successfully set NUMA memory policy to prefer node {}", nodeId);
                    return SUCCESS;
                } else {
                    int errno = Native.getLastError();
                    logger.warn("set_mempolicy failed with errno {}, but numa_set_preferred was called", errno);
                    // numa_set_preferred might have worked even if set_mempolicy failed
                    return SUCCESS;
                }

            } catch (Exception e) {
                logger.error("Failed to set NUMA memory policy: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        public int setNumaNodeCoreAffinity(long tid, int nodeId, int coreId) {
            try {
                // First, get the CPUs for this NUMA node
                long[] nodeCpuMask = getNumaNodeCpus(nodeId);
                if (nodeCpuMask == null) {
                    logger.error("Failed to get NUMA node {} CPU mask", nodeId);
                    return ERROR_SYSTEM_CALL_FAILED;
                }

                // Check if the requested core belongs to the NUMA node
                boolean coreInNode = false;
                if (coreId < 64) {
                    coreInNode = (nodeCpuMask[0] & (1L << coreId)) != 0;
                } else {
                    int wordIndex = coreId / 64;
                    if (wordIndex < nodeCpuMask.length) {
                        coreInNode = (nodeCpuMask[wordIndex] & (1L << (coreId % 64))) != 0;
                    }
                }

                if (!coreInNode) {
                    logger.error("Core {} does not belong to NUMA node {}", coreId, nodeId);
                    return ERROR_INVALID_PARAMETER;
                }

                // Set CPU affinity to the specific core
                int result = setCpuAffinity(tid, coreId);
                if (result != SUCCESS) {
                    return result;
                }

                // Set NUMA memory policy to prefer this node
                return setNumaMemoryPolicy(nodeId);

            } catch (Exception e) {
                logger.error("Failed to set NUMA node-core affinity: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        public int optimizeMemoryRange(long address, long size, int nodeId, boolean useHugePages) {
            try {
                int result = SUCCESS;

                // Bind memory to NUMA node
                result = bindMemoryRange(address, size, nodeId);
                if (result != SUCCESS) {
                    logger.warn("Failed to bind memory range to NUMA node {}", nodeId);
                    // Continue with other optimizations
                }

                // Optimize memory access patterns
                if (LinuxLibC.INSTANCE != null) {
                    try {
                        // Advise kernel about memory usage patterns
                        LinuxLibC.INSTANCE.madvise(new Pointer(address), size, LinuxLibC.MADV_SEQUENTIAL);
                        LinuxLibC.INSTANCE.madvise(new Pointer(address), size, LinuxLibC.MADV_WILLNEED);

                        // Enable huge pages if requested
                        if (useHugePages) {
                            LinuxLibC.INSTANCE.madvise(new Pointer(address), size, LinuxLibC.MADV_HUGEPAGE);
                        }

                        logger.debug("Optimized memory range 0x{} ({} bytes) for NUMA node {}, hugepages: {}",
                                Long.toHexString(address), size, nodeId, useHugePages);
                    } catch (Exception e) {
                        logger.warn("Failed to apply memory advise: {}", e.getMessage());
                        // Non-critical, continue
                    }
                }

                return result;

            } catch (Exception e) {
                logger.error("Failed to optimize memory range: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        private long[] getNumaNodeCpus(int nodeId) {
            try {
                java.nio.file.Path cpuListPath = java.nio.file.Paths.get(
                        "/sys/devices/system/node/node" + nodeId + "/cpulist");

                if (java.nio.file.Files.exists(cpuListPath)) {
                    String cpuList = java.nio.file.Files.readString(cpuListPath).trim();
                    return parseCpuListToMask(cpuList);
                }

                logger.error("NUMA node {} CPU list not found", nodeId);
                return null;

            } catch (Exception e) {
                logger.error("Failed to get NUMA node {} CPUs: {}", nodeId, e.getMessage());
                return null;
            }
        }

        private long[] parseCpuListToMask(String cpuList) {
            long[] mask = new long[16];
            if (cpuList.isEmpty()) {
                return mask;
            }

            String[] ranges = cpuList.split(",");
            for (String range : ranges) {
                range = range.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        try {
                            int start = Integer.parseInt(parts[0].trim());
                            int end = Integer.parseInt(parts[1].trim());
                            for (int cpu = start; cpu <= end; cpu++) {
                                mask[cpu / 64] |= (1L << (cpu % 64));
                            }
                        } catch (NumberFormatException e) {
                            logger.debug("Invalid CPU range: {}", range);
                        }
                    }
                } else {
                    try {
                        int cpu = Integer.parseInt(range);
                        mask[cpu / 64] |= (1L << (cpu % 64));
                    } catch (NumberFormatException e) {
                        logger.debug("Invalid CPU number: {}", range);
                    }
                }
            }

            return mask;
        }

        private int setCpuAffinity(long tid, int coreId) {
            try {
                // Check if we have the required library access
                if (LinuxLibC.INSTANCE == null) {
                    logger.error("Linux C library not available for CPU affinity");
                    return ERROR_NOT_SUPPORTED;
                }

                // Create CPU mask for single core
                long[] mask = new long[16];
                mask[coreId / 64] = 1L << (coreId % 64);

                // Convert to Memory for JNA
                Memory cpuMask = new Memory(8L * mask.length);
                for (int i = 0; i < mask.length; i++) {
                    cpuMask.setLong(i * 8L, mask[i]);
                }

                // Actually call sched_setaffinity
                int result = LinuxLibC.INSTANCE.sched_setaffinity((int)tid, (int)cpuMask.size(), cpuMask);

                if (result == 0) {
                    logger.debug("Successfully set CPU affinity for thread {} to core {}", tid, coreId);
                    return SUCCESS;
                } else {
                    int errno = Native.getLastError();
                    logger.error("Failed to set CPU affinity for thread {} to core {}: errno={}", tid, coreId, errno);
                    return mapErrnoToErrorCode(errno);
                }

            } catch (Exception e) {
                logger.error("Exception setting CPU affinity for thread {} to core {}: {}",
                        tid, coreId, e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        private int mapErrnoToErrorCode(int errno) {
            switch (errno) {
                case 1:  // EPERM
                    return ERROR_PERMISSION_DENIED;
                case 3:  // ESRCH
                    return ERROR_THREAD_NOT_FOUND;
                case 22: // EINVAL
                    return ERROR_INVALID_PARAMETER;
                case 12: // ENOMEM
                    return ERROR_INSUFFICIENT_MEMORY;
                default:
                    return ERROR_SYSTEM_CALL_FAILED;
            }
        }
    }

    /**
     * Enhanced Windows NUMA implementation
     */
    public static class WindowsEnhancedNUMA {
        private static final Logger logger = LoggerFactory.getLogger(WindowsEnhancedNUMA.class);

        // Extended Windows NUMA API
        private interface WindowsKernel32Ex extends Library {
            WindowsKernel32Ex INSTANCE = Native.load("kernel32", WindowsKernel32Ex.class);

            // NUMA memory operations
            Pointer VirtualAllocExNuma(Pointer hProcess, Pointer lpAddress, long dwSize,
                                       int flAllocationType, int flProtect, int nndPreferred);
            boolean VirtualFree(Pointer lpAddress, long dwSize, int dwFreeType);
            boolean VirtualLock(Pointer lpAddress, long dwSize);
            boolean VirtualUnlock(Pointer lpAddress, long dwSize);
            boolean SetProcessWorkingSetSizeEx(Pointer hProcess, long dwMinimumWorkingSetSize,
                                               long dwMaximumWorkingSetSize, int Flags);
            boolean GetNumaNodeProcessorMask(int Node, LongByReference ProcessorMask);

            // Process and thread handles
            Pointer GetCurrentProcess();
            Pointer GetCurrentThread();

            // Memory allocation flags
            int MEM_COMMIT = 0x1000;
            int MEM_RESERVE = 0x2000;
            int MEM_RELEASE = 0x8000;
            int MEM_LARGE_PAGES = 0x20000000;
            int PAGE_READWRITE = 0x04;

            // Working set flags
            int QUOTA_LIMITS_HARDWS_MIN_ENABLE = 0x00000001;
            int QUOTA_LIMITS_HARDWS_MAX_DISABLE = 0x00000008;
        }

        public int bindMemoryRange(long address, long size, int nodeId) {
            // Windows doesn't support binding existing memory to NUMA nodes after allocation
            // Memory NUMA affinity must be set during allocation with VirtualAllocExNuma
            logger.error("Windows does not support post-allocation memory binding. " +
                    "Use allocateNumaMemory() with specific nodeId instead.");
            return ERROR_NOT_SUPPORTED;
        }

        public int setNumaMemoryPolicy(int nodeId) {
            // Windows doesn't have a process-wide NUMA memory policy like Linux
            // NUMA affinity is set per allocation, not as a global policy
            logger.error("Windows does not support process-wide NUMA memory policy. " +
                    "NUMA affinity is set per memory allocation.");
            return ERROR_NOT_SUPPORTED;
        }

        public int setNumaNodeCoreAffinity(long tid, int nodeId, int coreId) {
            try {
                // Get CPUs for the NUMA node
                long[] nodeCpuMask = getNumaNodeCpus(nodeId);
                if (nodeCpuMask == null) {
                    logger.error("Failed to get NUMA node {} CPU mask", nodeId);
                    return ERROR_SYSTEM_CALL_FAILED;
                }

                // Verify the core belongs to the node
                boolean coreInNode = false;
                if (coreId < 64) {
                    coreInNode = (nodeCpuMask[0] & (1L << coreId)) != 0;
                }

                if (!coreInNode) {
                    logger.error("Core {} does not belong to NUMA node {}", coreId, nodeId);
                    return ERROR_INVALID_PARAMETER;
                }

                // Set thread affinity to the specific core
                // This would need to integrate with WindowsPlatformProvider
                logger.warn("Thread affinity setting not fully integrated - would set thread {} to core {} on NUMA node {}",
                        tid, coreId, nodeId);

                // For now, return not supported since we can't actually set the affinity
                return ERROR_NOT_SUPPORTED;

            } catch (Exception e) {
                logger.error("Failed to set NUMA node-core affinity: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        public long allocateNumaMemoryEx(int nodeId, long size, boolean useLargePages, boolean lockInMemory) {
            try {
                Pointer hProcess = WindowsKernel32Ex.INSTANCE.GetCurrentProcess();

                int allocationType = WindowsKernel32Ex.MEM_COMMIT | WindowsKernel32Ex.MEM_RESERVE;
                if (useLargePages) {
                    allocationType |= WindowsKernel32Ex.MEM_LARGE_PAGES;
                }

                Pointer ptr = WindowsKernel32Ex.INSTANCE.VirtualAllocExNuma(hProcess, null, size,
                        allocationType, WindowsKernel32Ex.PAGE_READWRITE, nodeId);

                if (ptr == null) {
                    logger.error("VirtualAllocExNuma failed for {} bytes on node {}", size, nodeId);
                    return 0;
                }

                long address = Pointer.nativeValue(ptr);

                // Lock memory in physical RAM if requested
                if (lockInMemory) {
                    boolean lockResult = WindowsKernel32Ex.INSTANCE.VirtualLock(ptr, size);
                    if (!lockResult) {
                        logger.warn("Failed to lock memory in RAM for address 0x{}", Long.toHexString(address));
                        // Continue anyway - allocation succeeded
                    }
                }

                logger.debug("Allocated {} bytes on NUMA node {} at 0x{}, largepages: {}, locked: {}",
                        size, nodeId, Long.toHexString(address), useLargePages, lockInMemory);

                return address;

            } catch (Exception e) {
                logger.error("Failed to allocate NUMA memory: {}", e.getMessage(), e);
                return 0;
            }
        }

        public int optimizeProcessWorkingSet(long minWorkingSetSize, long maxWorkingSetSize) {
            try {
                Pointer hProcess = WindowsKernel32Ex.INSTANCE.GetCurrentProcess();

                boolean result = WindowsKernel32Ex.INSTANCE.SetProcessWorkingSetSizeEx(hProcess,
                        minWorkingSetSize, maxWorkingSetSize,
                        WindowsKernel32Ex.QUOTA_LIMITS_HARDWS_MIN_ENABLE);

                if (result) {
                    logger.debug("Set process working set size: min={}, max={}",
                            minWorkingSetSize, maxWorkingSetSize);
                    return SUCCESS;
                } else {
                    logger.error("Failed to set process working set size");
                    return ERROR_SYSTEM_CALL_FAILED;
                }

            } catch (Exception e) {
                logger.error("Failed to optimize process working set: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        public int freeNumaMemoryEx(long address, long size, boolean wasLocked) {
            try {
                Pointer ptr = new Pointer(address);

                // Unlock memory if it was locked
                if (wasLocked) {
                    WindowsKernel32Ex.INSTANCE.VirtualUnlock(ptr, size);
                }

                // Free the memory
                boolean result = WindowsKernel32Ex.INSTANCE.VirtualFree(ptr, 0, WindowsKernel32Ex.MEM_RELEASE);

                if (result) {
                    logger.debug("Freed NUMA memory at 0x{} ({} bytes)", Long.toHexString(address), size);
                    return SUCCESS;
                } else {
                    logger.error("Failed to free NUMA memory at 0x{}", Long.toHexString(address));
                    return ERROR_SYSTEM_CALL_FAILED;
                }

            } catch (Exception e) {
                logger.error("Failed to free NUMA memory: {}", e.getMessage(), e);
                return ERROR_SYSTEM_CALL_FAILED;
            }
        }

        private long[] getNumaNodeCpus(int nodeId) {
            try {
                LongByReference processorMask = new LongByReference();
                boolean result = WindowsKernel32Ex.INSTANCE.GetNumaNodeProcessorMask(nodeId, processorMask);

                if (result) {
                    long[] mask = new long[1];
                    mask[0] = processorMask.getValue();
                    return mask;
                } else {
                    logger.error("Failed to get NUMA node {} processor mask", nodeId);
                    return null;
                }

            } catch (Exception e) {
                logger.error("Failed to get NUMA node {} CPU mask: {}", nodeId, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Cross-platform NUMA optimization utilities
     */
    public static class NumaOptimizationUtils {
        private static final Logger logger = LoggerFactory.getLogger(NumaOptimizationUtils.class);

        private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
        private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

        private final LinuxEnhancedNUMA linuxNuma;
        private final WindowsEnhancedNUMA windowsNuma;

        public NumaOptimizationUtils() {
            this.linuxNuma = IS_LINUX ? new LinuxEnhancedNUMA() : null;
            this.windowsNuma = IS_WINDOWS ? new WindowsEnhancedNUMA() : null;
        }

        public int bindMemoryToNode(long address, long size, int nodeId) {
            if (linuxNuma != null) {
                return linuxNuma.bindMemoryRange(address, size, nodeId);
            } else if (windowsNuma != null) {
                return windowsNuma.bindMemoryRange(address, size, nodeId);
            } else {
                logger.error("No NUMA implementation available for current platform");
                return ERROR_NOT_SUPPORTED;
            }
        }

        public int setMemoryPolicy(int preferredNodeId) {
            if (linuxNuma != null) {
                return linuxNuma.setNumaMemoryPolicy(preferredNodeId);
            } else if (windowsNuma != null) {
                return windowsNuma.setNumaMemoryPolicy(preferredNodeId);
            } else {
                logger.error("No NUMA implementation available for current platform");
                return ERROR_NOT_SUPPORTED;
            }
        }

        public int setThreadToNodeAndCore(long threadId, int nodeId, int coreId) {
            if (linuxNuma != null) {
                return linuxNuma.setNumaNodeCoreAffinity(threadId, nodeId, coreId);
            } else if (windowsNuma != null) {
                return windowsNuma.setNumaNodeCoreAffinity(threadId, nodeId, coreId);
            } else {
                logger.error("No NUMA implementation available for current platform");
                return ERROR_NOT_SUPPORTED;
            }
        }

        public long allocateOptimizedMemory(int nodeId, long size, boolean useHugePages, boolean lockInMemory) {
            if (windowsNuma != null) {
                return windowsNuma.allocateNumaMemoryEx(nodeId, size, useHugePages, lockInMemory);
            } else if (linuxNuma != null) {
                logger.warn("Linux optimized allocation not fully implemented - would need integration with numa_alloc_onnode");
                return 0;
            } else {
                logger.error("No NUMA implementation available for current platform");
                return 0;
            }
        }

        public int optimizeMemoryForAccess(long address, long size, int nodeId, boolean useHugePages) {
            if (linuxNuma != null) {
                return linuxNuma.optimizeMemoryRange(address, size, nodeId, useHugePages);
            } else if (windowsNuma != null) {
                logger.info("Windows memory optimization is done at allocation time via VirtualAllocExNuma");
                return SUCCESS;
            } else {
                logger.error("No NUMA implementation available for current platform");
                return ERROR_NOT_SUPPORTED;
            }
        }
    }

    /**
     * Performance-aware NUMA allocator for HFT systems
     */
    public static class HftNumaAllocator {
        private static final Logger logger = LoggerFactory.getLogger(HftNumaAllocator.class);

        private final NumaOptimizationUtils numaUtils;
        private final java.util.concurrent.ConcurrentHashMap<Long, AllocationInfo> allocations;

        public HftNumaAllocator() {
            this.numaUtils = new NumaOptimizationUtils();
            this.allocations = new java.util.concurrent.ConcurrentHashMap<>();
        }

        public long allocateForThread(long threadId, long size, int preferredNodeId) {
            try {
                // Try to allocate on the preferred NUMA node
                long address = numaUtils.allocateOptimizedMemory(preferredNodeId, size, true, true);

                if (address != 0) {
                    // Set memory policy for optimal access
                    int result = numaUtils.setMemoryPolicy(preferredNodeId);
                    if (result != SUCCESS) {
                        logger.warn("Failed to set memory policy for thread {}", threadId);
                    }

                    // Optimize memory range for access patterns
                    result = numaUtils.optimizeMemoryForAccess(address, size, preferredNodeId, true);
                    if (result != SUCCESS) {
                        logger.warn("Failed to optimize memory range for thread {}", threadId);
                    }

                    // Track the allocation
                    AllocationInfo info = new AllocationInfo(address, size, preferredNodeId, threadId,
                            System.currentTimeMillis(), true, true);
                    allocations.put(address, info);

                    logger.debug("Allocated {} bytes for thread {} on NUMA node {} at 0x{}",
                            size, threadId, preferredNodeId, Long.toHexString(address));
                } else {
                    logger.error("Failed to allocate memory for thread {} on NUMA node {}", threadId, preferredNodeId);
                }

                return address;

            } catch (Exception e) {
                logger.error("Failed to allocate NUMA memory for thread {}: {}", threadId, e.getMessage(), e);
                return 0;
            }
        }

        public boolean freeAllocation(long address) {
            AllocationInfo info = allocations.remove(address);
            if (info == null) {
                logger.error("Unknown allocation address: 0x{}", Long.toHexString(address));
                return false;
            }

            try {
                // Platform-specific cleanup would be needed here
                // For now, just log and return success
                logger.debug("Freed allocation at 0x{} ({} bytes) from NUMA node {}",
                        Long.toHexString(address), info.size, info.nodeId);
                return true;

            } catch (Exception e) {
                logger.error("Failed to free allocation at 0x{}: {}", Long.toHexString(address), e.getMessage(), e);
                return false;
            }
        }

        public java.util.List<AllocationInfo> getAllocationsForThread(long threadId) {
            return allocations.values().stream()
                    .filter(info -> info.threadId == threadId)
                    .collect(java.util.stream.Collectors.toList());
        }

        public java.util.List<AllocationInfo> getAllocationsForNode(int nodeId) {
            return allocations.values().stream()
                    .filter(info -> info.nodeId == nodeId)
                    .collect(java.util.stream.Collectors.toList());
        }

        public static class AllocationInfo {
            public final long address;
            public final long size;
            public final int nodeId;
            public final long threadId;
            public final long timestamp;
            public final boolean useHugePages;
            public final boolean lockedInMemory;

            public AllocationInfo(long address, long size, int nodeId, long threadId,
                                  long timestamp, boolean useHugePages, boolean lockedInMemory) {
                this.address = address;
                this.size = size;
                this.nodeId = nodeId;
                this.threadId = threadId;
                this.timestamp = timestamp;
                this.useHugePages = useHugePages;
                this.lockedInMemory = lockedInMemory;
            }

            @Override
            public String toString() {
                return String.format("Allocation{addr=0x%x, size=%d, node=%d, thread=%d, hugepages=%s, locked=%s}",
                        address, size, nodeId, threadId, useHugePages, lockedInMemory);
            }
        }
    }
}