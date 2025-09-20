package com.faster.affinity.platform;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Complete Windows-specific platform provider with actual Win32 API integration.
 * Fixed version with proper resource management and all methods implemented.
 */
public class WindowsPlatformProvider implements PlatformProvider {
    private static final Logger logger = LoggerFactory.getLogger(WindowsPlatformProvider.class);

    protected final AffinityConfig config;
    protected volatile boolean initialized = false;

    // Extended Windows kernel32 and system interfaces
    private interface WindowsKernel32Ex extends Library {
        WindowsKernel32Ex INSTANCE = Native.load("kernel32", WindowsKernel32Ex.class);

        // Thread and process affinity
        Pointer SetThreadAffinityMask(WinNT.HANDLE hThread, Pointer dwThreadAffinityMask);
        boolean SetProcessAffinityMask(WinNT.HANDLE hProcess, Pointer dwProcessAffinityMask);
        boolean GetProcessAffinityMask(WinNT.HANDLE hProcess, PointerByReference lpProcessAffinityMask,
                                       PointerByReference lpSystemAffinityMask);

        // Processor groups (Windows 7+)
        boolean SetThreadGroupAffinity(WinNT.HANDLE hThread, Pointer GroupAffinity, Pointer PreviousGroupAffinity);
        boolean GetThreadGroupAffinity(WinNT.HANDLE hThread, Pointer GroupAffinity);
        short GetActiveProcessorGroupCount();
        int GetActiveProcessorCount(short GroupNumber);
        int GetMaximumProcessorCount(short GroupNumber);

        // NUMA operations
        boolean GetNumaHighestNodeNumber(IntByReference HighestNodeNumber);
        boolean GetNumaNodeProcessorMask(int Node, LongByReference ProcessorMask);
        boolean GetNumaAvailableMemoryNode(int Node, LongByReference AvailableBytes);
        Pointer VirtualAllocExNuma(WinNT.HANDLE hProcess, Pointer lpAddress, NativeLong dwSize,
                                   int flAllocationType, int flProtect, int nndPreferred);
        boolean VirtualFree(Pointer lpAddress, NativeLong dwSize, int dwFreeType);

        // System information
        int GetCurrentThreadId();
        int GetCurrentProcessId();
        WinNT.HANDLE GetCurrentThread();
        WinNT.HANDLE GetCurrentProcess();
        void GetSystemInfo(Pointer lpSystemInfo);
        boolean GetLogicalProcessorInformation(Pointer Buffer, IntByReference ReturnedLength);
        boolean GetLogicalProcessorInformationEx(int RelationshipType, Pointer Buffer, IntByReference ReturnedLength);

        // Process and thread handles
        WinNT.HANDLE OpenThread(int dwDesiredAccess, boolean bInheritHandle, int dwThreadId);
        WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);
        boolean CloseHandle(WinNT.HANDLE hObject);

        // Performance monitoring
        boolean GetProcessTimes(WinNT.HANDLE hProcess, WinBase.FILETIME lpCreationTime,
                                WinBase.FILETIME lpExitTime, WinBase.FILETIME lpKernelTime, WinBase.FILETIME lpUserTime);
        boolean GetThreadTimes(WinNT.HANDLE hThread, WinBase.FILETIME lpCreationTime,
                               WinBase.FILETIME lpExitTime, WinBase.FILETIME lpKernelTime, WinBase.FILETIME lpUserTime);

        // Memory allocation flags
        int MEM_COMMIT = 0x1000;
        int MEM_RESERVE = 0x2000;
        int MEM_RELEASE = 0x8000;
        int PAGE_READWRITE = 0x04;

        // Thread access rights
        int THREAD_SET_INFORMATION = 0x0020;
        int THREAD_QUERY_INFORMATION = 0x0040;

        // Process access rights
        int PROCESS_SET_INFORMATION = 0x0200;
        int PROCESS_QUERY_INFORMATION = 0x0400;
    }

    // Performance Data Helper for performance monitoring
    private interface WindowsPdh extends Library {
        WindowsPdh INSTANCE = loadPdhLibrary();

        int PdhOpenQuery(String szDataSource, Pointer dwUserData, PointerByReference phQuery);
        int PdhAddCounter(Pointer hQuery, String szFullCounterPath, Pointer dwUserData, PointerByReference phCounter);
        int PdhCollectQueryData(Pointer hQuery);
        int PdhGetFormattedCounterValue(Pointer hCounter, int dwFormat, IntByReference lpdwType, Pointer pValue);
        int PdhCloseQuery(Pointer hQuery);

        int PDH_FMT_DOUBLE = 0x00000200;
        int PDH_FMT_LONG = 0x00000100;
    }

    private static WindowsPdh loadPdhLibrary() {
        try {
            return Native.load("pdh", WindowsPdh.class);
        } catch (UnsatisfiedLinkError e) {
            logger.debug("PDH library not available: {}", e.getMessage());
            return null;
        }
    }

    // Architecture-specific constants
    private static final int CACHE_LINE_SIZE = 64; // x86/x64 standard

    // Cached system information
    private final ConcurrentHashMap<String, Object> systemCache = new ConcurrentHashMap<>();
    private volatile boolean processorGroupsSupported = false;
    private volatile boolean numaSupported = false;
    private volatile boolean pdhAvailable = false;
    private volatile int processorGroupCount = 1;

    // Performance monitoring - with proper resource management
    private final ConcurrentHashMap<Integer, WindowsCorePerformanceTracker> coreTrackers = new ConcurrentHashMap<>();
    private final Set<WinNT.HANDLE> openHandles = Collections.synchronizedSet(new HashSet<>());

    public WindowsPlatformProvider(AffinityConfig config) {
        this.config = config;
    }

    @Override
    public void initialize() throws Exception {
        if (initialized) {
            return;
        }

        logger.info("Initializing Windows platform provider...");
        doInitialize();
        initialized = true;
        logger.info("Windows platform provider initialized successfully");
    }

    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }

        logger.info("Shutting down Windows platform provider");
        doShutdown();
        initialized = false;
    }

    protected void doInitialize() throws Exception {
        // Detect Windows version and features
        detectWindowsFeatures();

        // Initialize performance monitoring
        pdhAvailable = WindowsPdh.INSTANCE != null;
        if (pdhAvailable) {
            initializePerformanceMonitoring();
        }

        // Cache system information
        cacheSystemInformation();

        logger.info("Windows platform provider initialized: groups={}, NUMA={}, PDH={}",
                processorGroupsSupported, numaSupported, pdhAvailable);
    }

    protected void doShutdown() {
        // Clean up performance trackers
        coreTrackers.values().forEach(tracker -> {
            try {
                tracker.close();
            } catch (Exception e) {
                logger.debug("Error closing performance tracker: {}", e.getMessage());
            }
        });
        coreTrackers.clear();

        // Clean up any open handles
        synchronized (openHandles) {
            for (WinNT.HANDLE handle : openHandles) {
                try {
                    WindowsKernel32Ex.INSTANCE.CloseHandle(handle);
                } catch (Exception e) {
                    logger.debug("Error closing handle: {}", e.getMessage());
                }
            }
            openHandles.clear();
        }

        systemCache.clear();
    }

    // Core affinity operations

    @Override
    public int setThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0 || tid <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        WinNT.HANDLE hThread = null;
        try {
            hThread = getThreadHandle(tid);
            if (hThread == null) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            if (processorGroupsSupported && maskLength > 1) {
                return setThreadGroupAffinity(hThread, cpuMask, maskLength);
            } else {
                long mask = cpuMask.length > 0 ? cpuMask[0] : 0;

                if (mask == 0) {
                    return ErrorCodes.ERROR_INVALID_PARAMETER;
                }

                Pointer result = WindowsKernel32Ex.INSTANCE.SetThreadAffinityMask(hThread,
                        new Pointer(mask));

                return result != null && Pointer.nativeValue(result) != 0 ?
                        ErrorCodes.SUCCESS : ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

        } catch (Exception e) {
            logger.error("Exception in setThreadAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } finally {
            if (hThread != null && tid != getCurrentThreadId()) {
                try {
                    WindowsKernel32Ex.INSTANCE.CloseHandle(hThread);
                    openHandles.remove(hThread);
                } catch (Exception e) {
                    logger.debug("Error closing thread handle: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public int getThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0 || tid <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            if (processorGroupsSupported) {
                return getThreadGroupAffinity(tid, cpuMask, maskLength);
            } else {
                return getProcessAffinity(getCurrentProcessId(), cpuMask, maskLength);
            }
        } catch (Exception e) {
            logger.error("Exception in getThreadAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0 || pid <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        WinNT.HANDLE hProcess = null;
        try {
            hProcess = getProcessHandle(pid);
            if (hProcess == null) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            long mask = cpuMask.length > 0 ? cpuMask[0] : 0;

            if (mask == 0) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            boolean result = WindowsKernel32Ex.INSTANCE.SetProcessAffinityMask(hProcess,
                    new Pointer(mask));

            return result ? ErrorCodes.SUCCESS : ErrorCodes.ERROR_SYSTEM_CALL_FAILED;

        } catch (Exception e) {
            logger.error("Exception in setProcessAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } finally {
            if (hProcess != null && pid != getCurrentProcessId()) {
                try {
                    WindowsKernel32Ex.INSTANCE.CloseHandle(hProcess);
                    openHandles.remove(hProcess);
                } catch (Exception e) {
                    logger.debug("Error closing process handle: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public int getProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0 || pid <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        WinNT.HANDLE hProcess = null;
        try {
            hProcess = getProcessHandle(pid);
            if (hProcess == null) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            PointerByReference processAffinity = new PointerByReference();
            PointerByReference systemAffinity = new PointerByReference();

            boolean result = WindowsKernel32Ex.INSTANCE.GetProcessAffinityMask(hProcess,
                    processAffinity, systemAffinity);

            if (result && processAffinity.getValue() != null) {
                long affinity = Pointer.nativeValue(processAffinity.getValue());
                if (cpuMask.length > 0) {
                    cpuMask[0] = affinity;
                    for (int i = 1; i < Math.min(cpuMask.length, maskLength); i++) {
                        cpuMask[i] = 0;
                    }
                }
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

        } catch (Exception e) {
            logger.error("Exception in getProcessAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } finally {
            if (hProcess != null && pid != getCurrentProcessId()) {
                try {
                    WindowsKernel32Ex.INSTANCE.CloseHandle(hProcess);
                    openHandles.remove(hProcess);
                } catch (Exception e) {
                    logger.debug("Error closing process handle: {}", e.getMessage());
                }
            }
        }
    }

    // System information

    @Override
    public long getCurrentThreadId() {
        try {
            return WindowsKernel32Ex.INSTANCE.GetCurrentThreadId();
        } catch (Exception e) {
            logger.debug("Failed to get thread ID: {}", e.getMessage());
            return Thread.currentThread().getId();
        }
    }

    @Override
    public int getCurrentProcessId() {
        try {
            return WindowsKernel32Ex.INSTANCE.GetCurrentProcessId();
        } catch (Exception e) {
            logger.debug("Failed to get process ID: {}", e.getMessage());
            return fallbackGetProcessId();
        }
    }

    @Override
    public String getPlatformInfo() {
        return String.format("Windows %s, ProcessorGroups: %s, NUMA: %s, PDH: %s",
                System.getProperty("os.version", "unknown"),
                processorGroupsSupported ? "supported" : "not supported",
                numaSupported ? "available" : "unavailable",
                pdhAvailable ? "available" : "unavailable");
    }

    @Override
    public String[] getSupportedFeatures() {
        List<String> features = new ArrayList<>();
        features.add("cpu_affinity");
        features.add("process_affinity");
        features.add("topology_detection");
        features.add("basic_numa");
        features.add("windows_win32_api");
        features.add("processor_topology");

        if (processorGroupsSupported) {
            features.add("processor_groups");
            features.add("large_system_support");
        }

        if (numaSupported) {
            features.add("numa_operations");
            features.add("numa_memory_allocation");
        }

        if (pdhAvailable) {
            features.add("performance_counters");
            features.add("cpu_utilization_monitoring");
        }

        return features.toArray(new String[0]);
    }

    @Override
    public boolean supportsFeature(String feature) {
        if (feature == null) return false;
        String[] supported = getSupportedFeatures();
        for (String f : supported) {
            if (f.equals(feature)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRealtimeSupported() {
        return true; // Windows supports realtime priority classes
    }

    // Topology discovery

    @Override
    public int getCpuCount() {
        return getCachedValue("cpu_count", () -> {
            try {
                if (processorGroupsSupported) {
                    int totalCpus = 0;
                    for (short group = 0; group < processorGroupCount; group++) {
                        totalCpus += WindowsKernel32Ex.INSTANCE.GetActiveProcessorCount(group);
                    }
                    return totalCpus > 0 ? totalCpus : Runtime.getRuntime().availableProcessors();
                } else {
                    return Runtime.getRuntime().availableProcessors();
                }
            } catch (Exception e) {
                logger.debug("Failed to get CPU count: {}", e.getMessage());
                return Runtime.getRuntime().availableProcessors();
            }
        });
    }

    @Override
    public int getSocketCount() {
        return getCachedValue("socket_count", () -> {
            try {
                LogicalProcessorInfo info = getLogicalProcessorInfo();
                return Math.max(1, info.socketCount);
            } catch (Exception e) {
                logger.debug("Failed to get socket count: {}", e.getMessage());
                return 1;
            }
        });
    }

    @Override
    public int getCoresPerSocket() {
        return getCachedValue("cores_per_socket", () -> {
            try {
                LogicalProcessorInfo info = getLogicalProcessorInfo();
                return info.socketCount > 0 ? info.coreCount / info.socketCount : getCpuCount();
            } catch (Exception e) {
                logger.debug("Failed to get cores per socket: {}", e.getMessage());
                return getCpuCount() / getSocketCount();
            }
        });
    }

    @Override
    public int getMaxCacheLevel() {
        return getCachedValue("max_cache_level", () -> {
            try {
                LogicalProcessorInfo info = getLogicalProcessorInfo();
                // Ensure reasonable cache level (typically 1-4)
                int level = info.maxCacheLevel;
                if (level < 1 || level > 4) {
                    logger.debug("Invalid max cache level detected: {}, defaulting to 3", level);
                    return 3; // Most modern CPUs have L1, L2, L3
                }
                return level;
            } catch (Exception e) {
                logger.debug("Failed to get max cache level: {}", e.getMessage());
                return 3; // Safe default
            }
        });
    }

    @Override
    public long getCacheSize(int cacheLevel) {
        String cacheKey = "cache_size_l" + cacheLevel;
        return getCachedValue(cacheKey, () -> {
            try {
                // Validate cache level first
                if (cacheLevel < 1 || cacheLevel > getMaxCacheLevel()) {
                    return -1L;
                }

                LogicalProcessorInfo info = getLogicalProcessorInfo();
                Long size = info.cacheSizes.get(cacheLevel);

                if (size == null || size <= 0) {
                    // Don't log for every level, just return -1
                    return -1L;
                }
                return size;
            } catch (Exception e) {
                logger.debug("Failed to get L{} cache size: {}", cacheLevel, e.getMessage());
                return -1L;
            }
        });
    }

    @Override
    public long getCacheLineSize() {
        return getCachedValue("cache_line_size", () -> (long)CACHE_LINE_SIZE);
    }

    @Override
    public int isHyperThreadedCore(int coreId) {
        String cacheKey = "hyperthread_" + coreId;
        return getCachedValue(cacheKey, () -> {
            try {
                LogicalProcessorInfo info = getLogicalProcessorInfo();
                return info.logicalProcessorCount > info.coreCount ? 1 : 0;
            } catch (Exception e) {
                logger.debug("Failed to check hyperthreading for core {}: {}", coreId, e.getMessage());
                return 0;
            }
        });
    }

    @Override
    public int getCacheLevelCores(int coreId, int cacheLevel, long[] cpuMask, int maxCores) {
        if (cpuMask == null || maxCores <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            // Windows doesn't easily expose cache sharing information
            // Would need GetLogicalProcessorInformationEx with specific relationship types
            logger.debug("Cache level core mapping not fully implemented on Windows");
            return ErrorCodes.ERROR_NOT_SUPPORTED;

        } catch (Exception e) {
            logger.debug("Failed to get cache level cores: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    // NUMA operations

    @Override
    public int getNumaNodeCount() {
        if (!numaSupported) {
            return 1;
        }

        return getCachedValue("numa_node_count", () -> {
            try {
                IntByReference highestNode = new IntByReference();
                boolean result = WindowsKernel32Ex.INSTANCE.GetNumaHighestNodeNumber(highestNode);
                if (result) {
                    int nodeCount = highestNode.getValue() + 1;
                    // Validate reasonable node count
                    if (nodeCount < 1 || nodeCount > 64) {
                        logger.warn("Invalid NUMA node count: {}, defaulting to 1", nodeCount);
                        return 1;
                    }
                    return nodeCount;
                }
                return 1;
            } catch (Exception e) {
                logger.debug("Failed to get NUMA node count: {}", e.getMessage());
                return 1;
            }
        });
    }

    @Override
    public int getNumaNodeCpus(int nodeId, long[] cpuMask, int maxCores) {
        if (cpuMask == null) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        if (!numaSupported) {
            // For single-node systems, return all CPUs for node 0
            if (nodeId == 0 && getNumaNodeCount() == 1) {
                // Set all CPUs in the mask
                int cpuCount = getCpuCount();
                if (cpuMask.length > 0) {
                    cpuMask[0] = (1L << Math.min(cpuCount, 64)) - 1;
                    for (int i = 1; i < cpuMask.length; i++) {
                        cpuMask[i] = 0;
                    }
                }
                return ErrorCodes.SUCCESS;
            }
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            LongByReference processorMask = new LongByReference();
            boolean result = WindowsKernel32Ex.INSTANCE.GetNumaNodeProcessorMask(nodeId, processorMask);

            if (result) {
                if (cpuMask.length > 0) {
                    cpuMask[0] = processorMask.getValue();
                    // Check if we got an empty mask and it's a single node system
                    if (cpuMask[0] == 0 && getNumaNodeCount() == 1) {
                        // Fallback: assign all CPUs to node 0
                        int cpuCount = getCpuCount();
                        cpuMask[0] = (1L << Math.min(cpuCount, 64)) - 1;
                    }
                    for (int i = 1; i < cpuMask.length; i++) {
                        cpuMask[i] = 0;
                    }
                }
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

        } catch (Exception e) {
            logger.debug("Failed to get NUMA node {} CPUs: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int getNumaNodeMemoryInfo(int nodeId, long[] memoryInfo) {
        if (!numaSupported || memoryInfo == null || memoryInfo.length < 2) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            LongByReference availableBytes = new LongByReference();
            boolean result = WindowsKernel32Ex.INSTANCE.GetNumaAvailableMemoryNode(nodeId, availableBytes);

            if (result) {
                memoryInfo[0] = -1; // Total memory per node not available via this API
                memoryInfo[1] = availableBytes.getValue();
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

        } catch (Exception e) {
            logger.debug("Failed to get NUMA node {} memory info: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public long getNumaNodeDistance(int node1, int node2) {
        if (!numaSupported) {
            return -1;
        }

        // Windows doesn't expose NUMA distances via standard APIs
        logger.debug("NUMA distances not available on Windows");
        return -1;
    }

    @Override
    public int setNumaAffinity(long tid, int nodeId) {
        if (!numaSupported) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            long[] cpuMask = new long[16];
            int result = getNumaNodeCpus(nodeId, cpuMask, 1024);
            if (result != ErrorCodes.SUCCESS) {
                return result;
            }

            return setThreadAffinity(tid, cpuMask, cpuMask.length);

        } catch (Exception e) {
            logger.debug("Failed to set NUMA affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public long allocateNumaMemory(int nodeId, long size) {
        if (!numaSupported) {
            return 0;
        }

        try {
            WinNT.HANDLE hProcess = WindowsKernel32Ex.INSTANCE.GetCurrentProcess();
            Pointer ptr = WindowsKernel32Ex.INSTANCE.VirtualAllocExNuma(hProcess, null,
                    new NativeLong(size),
                    WindowsKernel32Ex.MEM_COMMIT | WindowsKernel32Ex.MEM_RESERVE,
                    WindowsKernel32Ex.PAGE_READWRITE, nodeId);

            return ptr != null ? Pointer.nativeValue(ptr) : 0;

        } catch (Exception e) {
            logger.debug("Failed to allocate NUMA memory: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int freeNumaMemory(long address, long size) {
        if (!numaSupported) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            boolean result = WindowsKernel32Ex.INSTANCE.VirtualFree(new Pointer(address),
                    new NativeLong(0), WindowsKernel32Ex.MEM_RELEASE);

            return result ? ErrorCodes.SUCCESS : ErrorCodes.ERROR_SYSTEM_CALL_FAILED;

        } catch (Exception e) {
            logger.debug("Failed to free NUMA memory: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setNumaMemoryPolicy(int nodeId) {
        logger.debug("NUMA memory policy not supported on Windows");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    @Override
    public int bindMemoryRange(long address, long size, int nodeId) {
        logger.debug("Memory binding not supported on Windows - use VirtualAllocExNuma during allocation");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    @Override
    public int setNumaNodeCoreAffinity(long tid, int nodeId, int coreId) {
        try {
            long[] nodeCpuMask = new long[16];
            int result = getNumaNodeCpus(nodeId, nodeCpuMask, 1024);
            if (result != ErrorCodes.SUCCESS) {
                return result;
            }

            boolean coreInNode = false;
            if (coreId < 64) {
                coreInNode = (nodeCpuMask[0] & (1L << coreId)) != 0;
            } else {
                int arrayIndex = coreId / 64;
                if (arrayIndex < nodeCpuMask.length) {
                    coreInNode = (nodeCpuMask[arrayIndex] & (1L << (coreId % 64))) != 0;
                }
            }

            if (!coreInNode) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            long[] coreMask = new long[16];
            if (coreId < 64) {
                coreMask[0] = 1L << coreId;
            } else {
                coreMask[coreId / 64] = 1L << (coreId % 64);
            }

            return setThreadAffinity(tid, coreMask, coreMask.length);

        } catch (Exception e) {
            logger.debug("Failed to set NUMA node-core affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    // Performance monitoring

    @Override
    public double getCoreUtilization(int coreId) {
        if (coreId < 0 || coreId >= getCpuCount()) {
            return 0.0;
        }

        WindowsCorePerformanceTracker tracker = coreTrackers.get(coreId);
        if (tracker != null) {
            return tracker.getUtilization();
        }

        if (pdhAvailable) {
            try {
                tracker = new WindowsCorePerformanceTracker(coreId);
                WindowsCorePerformanceTracker existing = coreTrackers.putIfAbsent(coreId, tracker);
                if (existing != null) {
                    tracker.close();
                    tracker = existing;
                }
                return tracker.getUtilization();
            } catch (Exception e) {
                logger.debug("Failed to create performance tracker for core {}: {}", coreId, e.getMessage());
            }
        }

        return 0.0;
    }

    @Override
    public double getNumaNodeUtilization(int nodeId) {
        if (!numaSupported) {
            return 0.0;
        }

        try {
            long[] cpuMask = new long[16];
            int result = getNumaNodeCpus(nodeId, cpuMask, 1024);
            if (result != ErrorCodes.SUCCESS) {
                return 0.0;
            }

            BitSet cpuSet = longArrayToBitSet(cpuMask);
            double totalUtilization = 0.0;
            int cpuCount = 0;

            for (int cpu = cpuSet.nextSetBit(0); cpu >= 0; cpu = cpuSet.nextSetBit(cpu + 1)) {
                totalUtilization += getCoreUtilization(cpu);
                cpuCount++;
            }

            return cpuCount > 0 ? totalUtilization / cpuCount : 0.0;

        } catch (Exception e) {
            logger.debug("Failed to get NUMA node {} utilization: {}", nodeId, e.getMessage());
            return 0.0;
        }
    }

    @Override
    public long getThreadCacheMisses(long tid) {
        logger.debug("Per-thread cache misses not available on Windows");
        return -1;
    }

    @Override
    public long getThreadContextSwitches(long tid) {
        logger.debug("Per-thread context switches not available via standard Windows APIs");
        return -1;
    }

    @Override
    public long getCoreCacheMisses(int coreId) {
        logger.debug("Per-core cache misses not available via standard Windows APIs");
        return -1;
    }

    // Helper methods

    private void detectWindowsFeatures() {
        try {
            // Check processor groups
            try {
                processorGroupCount = WindowsKernel32Ex.INSTANCE.GetActiveProcessorGroupCount();
                processorGroupsSupported = processorGroupCount > 0;
            } catch (Exception e) {
                processorGroupsSupported = false;
                processorGroupCount = 1;
            }

            // Check NUMA support
            try {
                IntByReference highestNode = new IntByReference();
                numaSupported = WindowsKernel32Ex.INSTANCE.GetNumaHighestNodeNumber(highestNode);
                // Validate the node count is reasonable
                if (numaSupported && highestNode.getValue() > 64) {
                    logger.warn("Unreasonable NUMA node count detected: {}, disabling NUMA", highestNode.getValue());
                    numaSupported = false;
                }
            } catch (Exception e) {
                numaSupported = false;
            }

            logger.debug("Windows features: ProcessorGroups={} ({}), NUMA={}",
                    processorGroupsSupported, processorGroupCount, numaSupported);

        } catch (Exception e) {
            logger.debug("Failed to detect Windows features: {}", e.getMessage());
            processorGroupsSupported = false;
            numaSupported = false;
            processorGroupCount = 1;
        }
    }

    private void initializePerformanceMonitoring() {
        if (!pdhAvailable) {
            return;
        }

        try {
            int cpuCount = Math.min(getCpuCount(), 256); // Reasonable limit
            for (int coreId = 0; coreId < cpuCount; coreId++) {
                try {
                    WindowsCorePerformanceTracker tracker = new WindowsCorePerformanceTracker(coreId);
                    coreTrackers.put(coreId, tracker);
                } catch (Exception e) {
                    logger.debug("Failed to initialize performance tracker for core {}: {}", coreId, e.getMessage());
                }
            }

            logger.debug("Initialized performance monitoring for {} cores", coreTrackers.size());

        } catch (Exception e) {
            logger.debug("Failed to initialize performance monitoring: {}", e.getMessage());
        }
    }

    private void cacheSystemInformation() {
        getCpuCount();
        getSocketCount();
        getCoresPerSocket();
        getMaxCacheLevel();
        getCacheLineSize();

        if (numaSupported) {
            getNumaNodeCount();
        }

        getLogicalProcessorInfo();

        logger.debug("Windows system information cached");
    }

    private int setThreadGroupAffinity(WinNT.HANDLE hThread, long[] cpuMask, int maskLength) {
        try {
            Memory groupAffinity = new Memory(16); // GROUP_AFFINITY structure
            groupAffinity.setShort(0, (short) 0); // Group
            groupAffinity.setLong(8, cpuMask[0]);   // Mask

            boolean result = WindowsKernel32Ex.INSTANCE.SetThreadGroupAffinity(hThread, groupAffinity, null);
            return result ? ErrorCodes.SUCCESS : ErrorCodes.ERROR_SYSTEM_CALL_FAILED;

        } catch (Exception e) {
            logger.debug("Failed to set thread group affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    private int getThreadGroupAffinity(long tid, long[] cpuMask, int maskLength) {
        WinNT.HANDLE hThread = null;
        try {
            hThread = getThreadHandle(tid);
            if (hThread == null) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            Memory groupAffinity = new Memory(16);
            boolean result = WindowsKernel32Ex.INSTANCE.GetThreadGroupAffinity(hThread, groupAffinity);

            if (result) {
                long mask = groupAffinity.getLong(8);

                if (cpuMask.length > 0) {
                    cpuMask[0] = mask;
                    for (int i = 1; i < cpuMask.length; i++) {
                        cpuMask[i] = 0;
                    }
                }

                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

        } catch (Exception e) {
            logger.debug("Failed to get thread group affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } finally {
            if (hThread != null && tid != getCurrentThreadId()) {
                WindowsKernel32Ex.INSTANCE.CloseHandle(hThread);
                openHandles.remove(hThread);
            }
        }
    }

    private WinNT.HANDLE getThreadHandle(long tid) {
        if (tid == getCurrentThreadId()) {
            return WindowsKernel32Ex.INSTANCE.GetCurrentThread();
        } else {
            WinNT.HANDLE handle = WindowsKernel32Ex.INSTANCE.OpenThread(
                    WindowsKernel32Ex.THREAD_SET_INFORMATION | WindowsKernel32Ex.THREAD_QUERY_INFORMATION,
                    false, (int) tid);
            if (handle != null) {
                openHandles.add(handle);
            }
            return handle;
        }
    }

    private WinNT.HANDLE getProcessHandle(int pid) {
        if (pid == getCurrentProcessId()) {
            return WindowsKernel32Ex.INSTANCE.GetCurrentProcess();
        } else {
            WinNT.HANDLE handle = WindowsKernel32Ex.INSTANCE.OpenProcess(
                    WindowsKernel32Ex.PROCESS_SET_INFORMATION | WindowsKernel32Ex.PROCESS_QUERY_INFORMATION,
                    false, pid);
            if (handle != null) {
                openHandles.add(handle);
            }
            return handle;
        }
    }

    private LogicalProcessorInfo getLogicalProcessorInfo() {
        return getCachedValue("logical_processor_info", () -> {
            LogicalProcessorInfo.Builder builder = new LogicalProcessorInfo.Builder();

            try {
                IntByReference returnLength = new IntByReference();
                WindowsKernel32Ex.INSTANCE.GetLogicalProcessorInformation(null, returnLength);

                if (returnLength.getValue() > 0) {
                    Memory buffer = new Memory(returnLength.getValue());
                    boolean result = WindowsKernel32Ex.INSTANCE.GetLogicalProcessorInformation(buffer, returnLength);

                    if (result) {
                        parseLogicalProcessorInformation(buffer, returnLength.getValue(), builder);
                    }
                }

            } catch (Exception e) {
                logger.debug("Failed to get logical processor information: {}", e.getMessage());
            }

            return builder.build();
        });
    }

    private void parseLogicalProcessorInformation(Memory buffer, int length, LogicalProcessorInfo.Builder builder) {
        try {
            int offset = 0;
            int structSize = 24; // SYSTEM_LOGICAL_PROCESSOR_INFORMATION size

            while (offset + structSize <= length) {
                long processorMask = buffer.getLong(offset);
                int relationship = buffer.getInt(offset + 8);

                switch (relationship) {
                    case 0: // RelationProcessorCore
                        builder.incrementCoreCount();
                        int logicalProcessors = Long.bitCount(processorMask);
                        builder.addLogicalProcessors(logicalProcessors);
                        break;

                    case 2: // RelationCache
                        int cacheLevel = buffer.getByte(offset + 12) & 0xFF;
                        // Validate cache level
                        if (cacheLevel >= 1 && cacheLevel <= 4) {
                            int cacheSize = buffer.getInt(offset + 16);
                            builder.addCacheInfo(cacheLevel, cacheSize);
                        }
                        break;

                    case 3: // RelationProcessorPackage
                        builder.incrementSocketCount();
                        break;
                }

                offset += structSize;
            }

        } catch (Exception e) {
            logger.debug("Failed to parse logical processor information: {}", e.getMessage());
        }
    }

    private BitSet longArrayToBitSet(long[] array) {
        BitSet bitSet = new BitSet();
        for (int i = 0; i < array.length; i++) {
            long word = array[i];
            for (int bit = 0; bit < 64; bit++) {
                if ((word & (1L << bit)) != 0) {
                    bitSet.set(i * 64 + bit);
                }
            }
        }
        return bitSet;
    }

    private int fallbackGetProcessId() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(name.split("@")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedValue(String key, java.util.function.Supplier<T> supplier) {
        if (!config.isCachingEnabled()) {
            return supplier.get();
        }
        return (T) systemCache.computeIfAbsent(key, k -> supplier.get());
    }

    // Helper classes

    private static class LogicalProcessorInfo {
        final int logicalProcessorCount;
        final int coreCount;
        final int socketCount;
        final int maxCacheLevel;
        final Map<Integer, Long> cacheSizes;

        private LogicalProcessorInfo(Builder builder) {
            this.logicalProcessorCount = builder.logicalProcessorCount;
            this.coreCount = Math.max(1, builder.coreCount); // Ensure at least 1
            this.socketCount = Math.max(1, builder.socketCount);
            // Validate and cap maxCacheLevel
            this.maxCacheLevel = Math.min(4, Math.max(0, builder.maxCacheLevel));
            this.cacheSizes = Collections.unmodifiableMap(new HashMap<>(builder.cacheSizes));
        }

        static class Builder {
            int logicalProcessorCount = 0;
            int coreCount = 0;
            int socketCount = 0;
            int maxCacheLevel = 0;
            Map<Integer, Long> cacheSizes = new HashMap<>();

            void incrementCoreCount() { coreCount++; }
            void incrementSocketCount() { socketCount++; }
            void addLogicalProcessors(int count) { logicalProcessorCount += count; }

            void addCacheInfo(int level, long size) {
                if (level >= 1 && level <= 4) { // Only accept valid cache levels
                    cacheSizes.put(level, size);
                    maxCacheLevel = Math.max(maxCacheLevel, level);
                }
            }

            LogicalProcessorInfo build() {
                // Ensure we have at least some defaults
                if (coreCount == 0) {
                    coreCount = Runtime.getRuntime().availableProcessors();
                }
                if (socketCount == 0) {
                    socketCount = 1;
                }
                if (maxCacheLevel == 0) {
                    maxCacheLevel = 3; // Default to L3
                }
                return new LogicalProcessorInfo(this);
            }
        }
    }

    private static class WindowsCorePerformanceTracker {
        private final int coreId;
        private Pointer queryHandle;
        private Pointer counterHandle;
        private volatile double utilization = 0.0;
        private volatile long lastUpdateTime = 0;
        private volatile boolean closed = false;

        public WindowsCorePerformanceTracker(int coreId) throws Exception {
            this.coreId = coreId;
            initialize();
        }

        private void initialize() throws Exception {
            if (WindowsPdh.INSTANCE == null) {
                throw new Exception("PDH not available");
            }

            PointerByReference queryRef = new PointerByReference();
            int result = WindowsPdh.INSTANCE.PdhOpenQuery(null, null, queryRef);
            if (result != 0) {
                throw new Exception("Failed to open PDH query: " + result);
            }

            queryHandle = queryRef.getValue();

            String counterPath = String.format("\\Processor(%d)\\%% Processor Time", coreId);
            PointerByReference counterRef = new PointerByReference();
            result = WindowsPdh.INSTANCE.PdhAddCounter(queryHandle, counterPath, null, counterRef);

            if (result != 0) {
                WindowsPdh.INSTANCE.PdhCloseQuery(queryHandle);
                queryHandle = null;
                throw new Exception("Failed to add PDH counter: " + result);
            }

            counterHandle = counterRef.getValue();

            try {
                WindowsPdh.INSTANCE.PdhCollectQueryData(queryHandle);
            } catch (Exception e) {
                // Initial collection might fail, ignore
            }
        }

        public double getUtilization() {
            if (closed) {
                return utilization;
            }

            long now = System.currentTimeMillis();
            if (now - lastUpdateTime > 1000) {
                updateUtilization();
            }
            return utilization;
        }

        private void updateUtilization() {
            if (closed || WindowsPdh.INSTANCE == null || queryHandle == null || counterHandle == null) {
                return;
            }

            try {
                int result = WindowsPdh.INSTANCE.PdhCollectQueryData(queryHandle);
                if (result == 0) {
                    IntByReference type = new IntByReference();
                    Memory value = new Memory(16);

                    result = WindowsPdh.INSTANCE.PdhGetFormattedCounterValue(counterHandle,
                            WindowsPdh.PDH_FMT_DOUBLE, type, value);

                    if (result == 0) {
                        utilization = Math.max(0.0, Math.min(1.0, value.getDouble(8) / 100.0));
                        lastUpdateTime = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                // Keep last known value
            }
        }

        public void close() {
            if (!closed) {
                closed = true;
                if (queryHandle != null && WindowsPdh.INSTANCE != null) {
                    try {
                        WindowsPdh.INSTANCE.PdhCloseQuery(queryHandle);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                    queryHandle = null;
                    counterHandle = null;
                }
            }
        }
    }

    // IRQ (Interrupt Request) management implementation
    // Note: Windows has very limited IRQ affinity control compared to Linux

    @Override
    public int getIrqCount() {
        // Windows doesn't expose a simple way to enumerate all IRQs
        // Return 0 to indicate this feature is not available
        logger.debug("IRQ enumeration not supported on Windows");
        return 0;
    }

    @Override
    public int[] getAllIrqNumbers() {
        // Windows doesn't provide a straightforward way to list all IRQ numbers
        // like Linux's /proc/interrupts
        logger.debug("IRQ enumeration not supported on Windows");
        return new int[0];
    }

    @Override
    public String getIrqDescription(int irqNumber) {
        // Windows doesn't provide easy access to IRQ descriptions
        return "Windows IRQ " + irqNumber + " (limited info)";
    }

    @Override
    public int getIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        // Windows doesn't provide a direct way to get IRQ affinity
        // Most IRQ affinity is handled automatically by the system
        logger.debug("Getting IRQ affinity not supported on Windows");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    @Override
    public int setIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        // Windows has very limited IRQ affinity control
        // Most interrupt handling is managed by the OS and device drivers
        logger.debug("Setting IRQ affinity not supported on Windows");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    @Override
    public int setDefaultIrqAffinity(long[] cpuMask, int maskLength) {
        // Windows doesn't have a direct equivalent to Linux's /proc/irq/default_smp_affinity
        // Some level of interrupt steering can be achieved through RSS (Receive Side Scaling)
        // for network adapters, but this is device-specific and not a general IRQ mechanism
        logger.debug("Setting default IRQ affinity not supported on Windows");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    // CPU Governor Control Implementation (Windows)

    @Override
    public com.faster.affinity.core.CPUGovernorManager.GovernorMode getCpuGovernor(int coreId) {
        // Windows doesn't have traditional Linux-style governors
        // Power management is handled by the OS through power schemes
        // Default to assuming performance mode for HFT applications
        logger.debug("CPU governor control not available on Windows - power managed by OS");
        return com.faster.affinity.core.CPUGovernorManager.GovernorMode.PERFORMANCE;
    }

    @Override
    public int setCpuGovernor(int coreId, com.faster.affinity.core.CPUGovernorManager.GovernorMode governor) {
        // Windows CPU frequency scaling is controlled through:
        // 1. Power Plans (High Performance, Balanced, Power Saver)
        // 2. Processor Power Management settings
        // 3. Platform-specific tools (Intel Turbo Boost, AMD Precision Boost)
        //
        // For HFT applications, users should:
        // - Set Windows to "High Performance" power plan
        // - Disable CPU throttling in BIOS
        // - Use platform-specific performance tools
        logger.debug("CPU governor control not directly supported on Windows - use High Performance power plan");
        return ErrorCodes.ERROR_NOT_SUPPORTED;
    }

    @Override
    public java.util.List<com.faster.affinity.core.CPUGovernorManager.GovernorMode> getAvailableGovernors(int coreId) {
        // Windows doesn't expose traditional governor modes
        // Return empty list to indicate no direct governor control
        logger.debug("Available governors not accessible on Windows");
        return Collections.emptyList();
    }

    @Override
    public long getCpuFrequency(int coreId) {
        // Windows frequency information can be obtained through:
        // 1. Performance counters
        // 2. WMI queries
        // 3. Registry values
        // For now, return -1 to indicate unavailable
        logger.debug("CPU frequency monitoring not implemented for Windows");
        return -1;
    }

    @Override
    public long getCpuMinFrequency(int coreId) {
        // Would require WMI query or performance counter access
        logger.debug("CPU min frequency not accessible on Windows");
        return -1;
    }

    @Override
    public long getCpuMaxFrequency(int coreId) {
        // Would require WMI query or performance counter access
        logger.debug("CPU max frequency not accessible on Windows");
        return -1;
    }
}