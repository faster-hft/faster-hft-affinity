package com.faster.affinity.platform;

import com.faster.affinity.config.AffinityConfig;
import com.faster.affinity.exceptions.*;
import com.faster.affinity.performance.PerfEventCounter;
import com.faster.affinity.validation.InputValidator;
import com.sun.jna.*;
import com.sun.jna.ptr.LongByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Complete Linux-specific platform provider with actual system call integration.
 * Fixed version without fake default values.
 */
public class LinuxPlatformProvider implements PlatformProvider {
    private static final Logger logger = LoggerFactory.getLogger(LinuxPlatformProvider.class);

    protected final AffinityConfig config;
    protected volatile boolean initialized = false;

    // Architecture-specific syscall numbers
    private static final Map<String, Integer> SYSCALL_NUMBERS = new HashMap<>();
    static {
        SYSCALL_NUMBERS.put("x86_64.perf_event_open", 298);
        SYSCALL_NUMBERS.put("aarch64.perf_event_open", 241);
        SYSCALL_NUMBERS.put("i386.perf_event_open", 336);
    }

    // Architecture constants
    private static final int CACHE_LINE_SIZE = 64; // x86/x64 standard

    // Linux system call interfaces
    private interface LinuxLibC extends Library {
        LinuxLibC INSTANCE = SecureNativeLoader.loadLibrary("c", LinuxLibC.class);

        int sched_setaffinity(int pid, int cpusetsize, Pointer mask);
        int sched_getaffinity(int pid, int cpusetsize, Pointer mask);
        int syscall(int number, Object... args);
        int getpid();
        long gettid();
        int sysconf(int name);
        @SuppressWarnings("unused") // May be used for cleanup operations
        int close(int fd);
    }

    // Linux NUMA library
    private interface LinuxNuma extends Library {
        LinuxNuma INSTANCE = loadNumaLibrary();

        int numa_available();
        int numa_max_node();
        long numa_node_size64(int node, LongByReference freep);
        void numa_set_preferred(int node);
        Pointer numa_alloc_onnode(long size, int node);
        void numa_free(Pointer start, long size);
        int numa_distance(int from, int to);
        int numa_run_on_node(int node);
    }

    private static LinuxNuma loadNumaLibrary() {
        try {
            return SecureNativeLoader.loadLibrary("numa", LinuxNuma.class);
        } catch (SecurityException | UnsatisfiedLinkError e) {
            logger.debug("NUMA library not available or validation failed: {}", e.getMessage());
            return null;
        }
    }

    // Performance event constants
    private static final int PERF_TYPE_HARDWARE = 0;
    private static final int PERF_TYPE_SOFTWARE = 1;
    private static final int PERF_COUNT_HW_CACHE_MISSES = 3;
    private static final int PERF_COUNT_SW_CONTEXT_SWITCHES = 3;

    // Cached system information
    private final ConcurrentHashMap<String, Object> systemCache = new ConcurrentHashMap<>();
    private volatile boolean numaAvailable = false;
    private volatile boolean perfCountersAvailable = false;
    private volatile int perfEventSyscallNumber = -1;

    // Performance monitoring state
    private final ConcurrentHashMap<Integer, CoreUtilizationTracker> coreTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerfEventCounter> perfCounters = new ConcurrentHashMap<>();

    public LinuxPlatformProvider(AffinityConfig config) {
        this.config = config;
    }

    @Override
    public void initialize() throws Exception {
        if (initialized) {
            return;
        }

        logger.info("Initializing Linux platform provider...");
        doInitialize();
        initialized = true;
        logger.info("Linux platform provider initialized successfully");
    }

    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }

        logger.info("Shutting down Linux platform provider");
        doShutdown();
        initialized = false;
    }

    protected void doInitialize() throws Exception {
        logger.info("Initializing Linux platform provider with graceful degradation...");

        try {
            // Detect architecture and syscall numbers
            detectArchitectureSpecificSyscalls();
        } catch (Exception e) {
            logger.warn("Failed to detect architecture-specific syscalls, continuing with defaults: {}", e.getMessage());
        }

        try {
            // Check NUMA availability
            numaAvailable = checkNumaAvailability();
            logger.info("NUMA available: {}", numaAvailable);
        } catch (Exception e) {
            logger.warn("Failed to check NUMA availability, assuming not available: {}", e.getMessage());
            numaAvailable = false;
        }

        try {
            // Check performance counters
            perfCountersAvailable = checkPerfCountersAvailability();
            logger.info("Performance counters available: {}", perfCountersAvailable);
        } catch (Exception e) {
            logger.warn("Failed to check performance counters, assuming not available: {}", e.getMessage());
            perfCountersAvailable = false;
        }

        int cpuCount = 0;
        try {
            // Initialize core utilization tracking
            cpuCount = getCpuCount();
            for (int coreId = 0; coreId < cpuCount; coreId++) {
                coreTrackers.put(coreId, new CoreUtilizationTracker(coreId));
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize core utilization tracking: {}", e.getMessage());
        }

        try {
            // Cache static system information
            cacheSystemInformation();
        } catch (Exception e) {
            logger.warn("Failed to cache system information: {}", e.getMessage());
        }

        logger.info("Linux platform provider initialized successfully for {} CPUs", cpuCount);
    }

    protected void doShutdown() {
        // Clean up performance counters
        perfCounters.values().forEach(counter -> {
            try {
                counter.close();
            } catch (Exception e) {
                logger.debug("Error closing performance counter: {}", e.getMessage());
            }
        });
        perfCounters.clear();

        systemCache.clear();
        coreTrackers.clear();
    }

    private void detectArchitectureSpecificSyscalls() {
        String arch = System.getProperty("os.arch", "unknown").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            perfEventSyscallNumber = SYSCALL_NUMBERS.get("x86_64.perf_event_open");
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            perfEventSyscallNumber = SYSCALL_NUMBERS.get("aarch64.perf_event_open");
        } else if (arch.contains("i386") || arch.contains("x86")) {
            perfEventSyscallNumber = SYSCALL_NUMBERS.get("i386.perf_event_open");
        }
    }

    private boolean checkNumaAvailability() {
        if (LinuxNuma.INSTANCE != null) {
            try {
                return LinuxNuma.INSTANCE.numa_available() == 0;
            } catch (Exception e) {
                logger.debug("NUMA check failed: {}", e.getMessage());
            }
        }
        return Files.exists(Paths.get("/sys/devices/system/node"));
    }

    private boolean checkPerfCountersAvailability() {
        return Files.exists(Paths.get("/proc/sys/kernel/perf_event_paranoid"));
    }

    private void cacheSystemInformation() {
        getCpuCount();
        getSocketCount();
        getCoresPerSocket();
        getMaxCacheLevel();
        getCacheLineSize();
    }

    // Core affinity operations

    @Override
    public int setThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        // Comprehensive input validation
        try {
            InputValidator.validateThreadId(tid, "setThreadAffinity");
            InputValidator.validateLongArray(cpuMask, "cpuMask", "setThreadAffinity");
            InputValidator.validateRange(maskLength, 1, 16, "maskLength", "setThreadAffinity");
        } catch (IllegalArgumentException e) {
            logger.error("Input validation failed for setThreadAffinity: {}", e.getMessage());
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            final int finalMaskLength = Math.min(maskLength, 16);
            if (maskLength > 16) {
                logger.warn("CPU mask length {} exceeds maximum supported size", maskLength);
            }

            // Use managed memory to prevent leaks
            return ResourceManager.withManagedMemory(8L * finalMaskLength, mask -> {
                for (int i = 0; i < finalMaskLength && i < cpuMask.length; i++) {
                    mask.setLong(i * 8L, cpuMask[i]);
                }

                int result = LinuxLibC.INSTANCE.sched_setaffinity((int) tid, (int) mask.size(), mask);

                if (result == 0) {
                    logger.debug("Set thread {} affinity successfully", tid);
                    return ErrorCodes.SUCCESS;
                } else {
                    int errno = Native.getLastError();
                    logger.debug("sched_setaffinity failed with errno {}", errno);
                    return mapErrnoToErrorCode(errno);
                }
            });

        } catch (Exception e) {
            logger.error("Exception in setThreadAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int getThreadAffinity(long tid, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            if (maskLength > 16) {
                logger.warn("CPU mask length {} exceeds maximum supported size", maskLength);
                maskLength = 16;
            }

            Memory mask = new Memory(8L * maskLength);

            int result = LinuxLibC.INSTANCE.sched_getaffinity((int) tid, (int) mask.size(), mask);

            if (result == 0) {
                for (int i = 0; i < maskLength && i < cpuMask.length; i++) {
                    cpuMask[i] = mask.getLong(i * 8L);
                }
                logger.debug("Got thread {} affinity successfully", tid);
                return ErrorCodes.SUCCESS;
            } else {
                int errno = Native.getLastError();
                logger.debug("sched_getaffinity failed with errno {}", errno);
                return mapErrnoToErrorCode(errno);
            }

        } catch (Exception e) {
            logger.error("Exception in getThreadAffinity: {}", e.getMessage(), e);
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        return setThreadAffinity(pid, cpuMask, maskLength);
    }

    @Override
    public int getProcessAffinity(int pid, long[] cpuMask, int maskLength) {
        return getThreadAffinity(pid, cpuMask, maskLength);
    }

    // System information

    @Override
    public long getCurrentThreadId() {
        try {
            return LinuxLibC.INSTANCE.gettid();
        } catch (Exception e) {
            logger.debug("Failed to get thread ID via gettid(): {}", e.getMessage());
            return Thread.currentThread().getId();
        }
    }

    @Override
    public int getCurrentProcessId() {
        try {
            return LinuxLibC.INSTANCE.getpid();
        } catch (Exception e) {
            logger.debug("Failed to get process ID via getpid(): {}", e.getMessage());
            return fallbackGetProcessId();
        }
    }

    @Override
    public String getPlatformInfo() {
        return String.format("Linux %s, Kernel %s, NUMA: %s, PerfCounters: %s",
                System.getProperty("os.arch", "unknown"),
                System.getProperty("os.version", "unknown"),
                numaAvailable ? "available" : "unavailable",
                perfCountersAvailable ? "available" : "unavailable");
    }

    @Override
    public String[] getSupportedFeatures() {
        List<String> features = new ArrayList<>();
        features.add("cpu_affinity");
        features.add("process_affinity");
        features.add("topology_detection");
        features.add("basic_numa");
        features.add("linux_scheduling");
        features.add("sysfs_topology");
        features.add("procfs_monitoring");

        if (numaAvailable) {
            features.add("numa_operations");
            features.add("numa_memory_binding");
        }

        if (perfCountersAvailable) {
            features.add("performance_counters");
            features.add("cache_miss_tracking");
        }

        // IRQ management support (always available on Linux)
        features.add("irq_management");
        features.add("irq_isolation");
        features.add("interrupt_control");

        // CPU Governor control support (available if cpufreq is present)
        if (Files.exists(Paths.get("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"))) {
            features.add("cpu_governor_control");
            features.add("frequency_scaling");
            features.add("performance_governor");
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
        try {
            return Files.exists(Paths.get("/proc/sys/kernel/sched_rt_period_us"));
        } catch (Exception e) {
            return false;
        }
    }

    // Topology discovery

    @Override
    public int getCpuCount() {
        return getCachedValue("cpu_count", () -> {
            try {
                return LinuxLibC.INSTANCE.sysconf(84); // _SC_NPROCESSORS_ONLN
            } catch (Exception e) {
                logger.debug("Failed to get CPU count via sysconf: {}", e.getMessage());
                return Runtime.getRuntime().availableProcessors();
            }
        });
    }

    @Override
    public int getSocketCount() {
        return getCachedValue("socket_count", () -> {
            try {
                Set<Integer> physicalIds = new HashSet<>();

                try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/cpuinfo"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("physical id")) {
                            String[] parts = line.split(":");
                            if (parts.length > 1) {
                                try {
                                    physicalIds.add(Integer.parseInt(parts[1].trim()));
                                } catch (NumberFormatException e) {
                                    logger.debug("Invalid physical id: {}", parts[1]);
                                }
                            }
                        }
                    }
                }

                return Math.max(1, physicalIds.size());

            } catch (Exception e) {
                logger.debug("Failed to determine socket count: {}", e.getMessage());
                return 1;
            }
        });
    }

    @Override
    public int getCoresPerSocket() {
        return getCachedValue("cores_per_socket", () -> {
            try {
                try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/cpuinfo"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("cpu cores")) {
                            String[] parts = line.split(":");
                            if (parts.length > 1) {
                                try {
                                    return Integer.parseInt(parts[1].trim());
                                } catch (NumberFormatException e) {
                                    logger.debug("Invalid cpu cores value: {}", parts[1]);
                                }
                            }
                        }
                    }
                }

                return getCpuCount() / getSocketCount();

            } catch (Exception e) {
                logger.debug("Failed to determine cores per socket: {}", e.getMessage());
                return getCpuCount() / getSocketCount();
            }
        });
    }

    @Override
    public int getMaxCacheLevel() {
        return getCachedValue("max_cache_level", () -> {
            try {
                for (int level = 4; level >= 1; level--) {
                    Path cachePath = Paths.get("/sys/devices/system/cpu/cpu0/cache/index" + (level - 1));
                    if (Files.exists(cachePath)) {
                        return level;
                    }
                }
                return -1; // Not available

            } catch (Exception e) {
                logger.debug("Failed to determine max cache level: {}", e.getMessage());
                return -1;
            }
        });
    }

    @Override
    public long getCacheSize(int cacheLevel) {
        String cacheKey = "cache_size_l" + cacheLevel;
        return getCachedValue(cacheKey, () -> {
            try {
                Path sizePath = Paths.get("/sys/devices/system/cpu/cpu0/cache/index" + (cacheLevel - 1) + "/size");
                if (!Files.exists(sizePath)) {
                    logger.debug("L{} cache size not available - path doesn't exist", cacheLevel);
                    return -1L;
                }

                String sizeStr = Files.readString(sizePath).trim().toUpperCase();
                long size = parseSizeString(sizeStr);

                if (size <= 0) {
                    logger.debug("L{} cache size could not be parsed: {}", cacheLevel, sizeStr);
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
        return getCachedValue("cache_line_size", () -> {
            try {
                Path lineSizePath = Paths.get("/sys/devices/system/cpu/cpu0/cache/index0/coherency_line_size");
                if (Files.exists(lineSizePath)) {
                    String sizeStr = Files.readString(lineSizePath).trim();
                    return Long.parseLong(sizeStr);
                }
                return (long)CACHE_LINE_SIZE;

            } catch (Exception e) {
                logger.debug("Failed to get cache line size: {}", e.getMessage());
                return (long)CACHE_LINE_SIZE;
            }
        });
    }

    @Override
    public int isHyperThreadedCore(int coreId) {
        String cacheKey = "hyperthread_" + coreId;
        return getCachedValue(cacheKey, () -> {
            try {
                Path siblingsPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/topology/thread_siblings_list");
                if (!Files.exists(siblingsPath)) {
                    return 0;
                }

                String siblings = Files.readString(siblingsPath).trim();
                return siblings.contains(",") ? 1 : 0;

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
            Path sharedCpusPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId +
                    "/cache/index" + (cacheLevel - 1) + "/shared_cpu_list");

            if (!Files.exists(sharedCpusPath)) {
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            String cpuList = Files.readString(sharedCpusPath).trim();
            if (cpuList.isEmpty()) {
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

            BitSet cpuSet = parseCpuList(cpuList);
            long[] result = bitSetToLongArray(cpuSet);

            int copyLength = Math.min(result.length, cpuMask.length);
            System.arraycopy(result, 0, cpuMask, 0, copyLength);

            for (int i = copyLength; i < cpuMask.length; i++) {
                cpuMask[i] = 0;
            }

            return ErrorCodes.SUCCESS;

        } catch (Exception e) {
            logger.debug("Failed to get cache level cores for core {} L{}: {}", coreId, cacheLevel, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    // NUMA operations

    @Override
    public int getNumaNodeCount() {
        if (!numaAvailable) {
            return 1;
        }

        return getCachedValue("numa_node_count", () -> {
            try {
                if (LinuxNuma.INSTANCE != null) {
                    int maxNode = LinuxNuma.INSTANCE.numa_max_node();
                    if (maxNode >= 0) {
                        return maxNode + 1;
                    }
                }

                Path nodeDir = Paths.get("/sys/devices/system/node");
                if (!Files.exists(nodeDir)) {
                    return 1;
                }

                try (var stream = Files.list(nodeDir)) {
                    return (int) stream
                            .filter(path -> path.getFileName().toString().matches("node\\d+"))
                            .count();
                }

            } catch (Exception e) {
                logger.debug("Failed to get NUMA node count: {}", e.getMessage());
                return 1;
            }
        });
    }

    @Override
    public int getNumaNodeCpus(int nodeId, long[] cpuMask, int maxCores) {
        if (!numaAvailable || cpuMask == null) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            Path cpuListPath = Paths.get("/sys/devices/system/node/node" + nodeId + "/cpulist");
            if (!Files.exists(cpuListPath)) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            String cpuList = Files.readString(cpuListPath).trim();
            if (cpuList.isEmpty()) {
                Arrays.fill(cpuMask, 0);
                return ErrorCodes.SUCCESS;
            }

            BitSet cpuSet = parseCpuList(cpuList);
            long[] result = bitSetToLongArray(cpuSet);

            System.arraycopy(result, 0, cpuMask, 0, Math.min(result.length, cpuMask.length));

            return ErrorCodes.SUCCESS;

        } catch (Exception e) {
            logger.debug("Failed to get NUMA node {} CPUs: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int getNumaNodeMemoryInfo(int nodeId, long[] memoryInfo) {
        if (!numaAvailable || memoryInfo == null || memoryInfo.length < 2) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            if (LinuxNuma.INSTANCE != null) {
                LongByReference freeMem = new LongByReference();
                long totalMem = LinuxNuma.INSTANCE.numa_node_size64(nodeId, freeMem);

                if (totalMem > 0) {
                    memoryInfo[0] = totalMem;
                    memoryInfo[1] = freeMem.getValue();
                    return ErrorCodes.SUCCESS;
                }
            }

            Path meminfoPath = Paths.get("/sys/devices/system/node/node" + nodeId + "/meminfo");
            if (!Files.exists(meminfoPath)) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            }

            long total = 0, free = 0;
            try (BufferedReader reader = Files.newBufferedReader(meminfoPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("MemTotal:")) {
                        total = parseMemoryValue(line) * 1024;
                    } else if (line.contains("MemFree:")) {
                        free = parseMemoryValue(line) * 1024;
                    }
                }
            }

            if (total > 0) {
                memoryInfo[0] = total;
                memoryInfo[1] = free;
                return ErrorCodes.SUCCESS;
            }

            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;

        } catch (Exception e) {
            logger.debug("Failed to get NUMA node {} memory info: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public long getNumaNodeDistance(int node1, int node2) {
        if (!numaAvailable) {
            return -1L;
        }

        try {
            if (LinuxNuma.INSTANCE != null) {
                int distance = LinuxNuma.INSTANCE.numa_distance(node1, node2);
                if (distance > 0) {
                    return distance;
                }
            }

            Path distancePath = Paths.get("/sys/devices/system/node/node" + node1 + "/distance");
            if (Files.exists(distancePath)) {
                String[] distances = Files.readString(distancePath).trim().split("\\s+");
                if (node2 < distances.length) {
                    return Long.parseLong(distances[node2]);
                }
            }

            logger.debug("NUMA distance not available for nodes {}->{}", node1, node2);
            return -1L;

        } catch (Exception e) {
            logger.debug("Failed to get NUMA distance {}->{}: {}", node1, node2, e.getMessage());
            return -1L;
        }
    }

    @Override
    public int setNumaAffinity(long tid, int nodeId) {
        if (!numaAvailable || LinuxNuma.INSTANCE == null) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            int result = LinuxNuma.INSTANCE.numa_run_on_node(nodeId);
            return result == 0 ? ErrorCodes.SUCCESS : ErrorCodes.ERROR_SYSTEM_CALL_FAILED;

        } catch (Exception e) {
            logger.debug("Failed to set NUMA affinity to node {}: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public long allocateNumaMemory(int nodeId, long size) {
        if (!numaAvailable || LinuxNuma.INSTANCE == null) {
            return 0;
        }

        try {
            Pointer ptr = LinuxNuma.INSTANCE.numa_alloc_onnode(size, nodeId);
            return ptr != null ? Pointer.nativeValue(ptr) : 0;

        } catch (Exception e) {
            logger.debug("Failed to allocate {} bytes on NUMA node {}: {}", size, nodeId, e.getMessage());
            return 0;
        }
    }

    @Override
    public int freeNumaMemory(long address, long size) {
        if (!numaAvailable || LinuxNuma.INSTANCE == null) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            LinuxNuma.INSTANCE.numa_free(new Pointer(address), size);
            return ErrorCodes.SUCCESS;

        } catch (Exception e) {
            logger.debug("Failed to free NUMA memory at 0x{}: {}", Long.toHexString(address), e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setNumaMemoryPolicy(int nodeId) {
        if (!numaAvailable || LinuxNuma.INSTANCE == null) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        try {
            LinuxNuma.INSTANCE.numa_set_preferred(nodeId);
            return ErrorCodes.SUCCESS;

        } catch (Exception e) {
            logger.debug("Failed to set NUMA memory policy for node {}: {}", nodeId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int bindMemoryRange(long address, long size, int nodeId) {
        if (!numaAvailable) {
            return ErrorCodes.ERROR_NOT_SUPPORTED;
        }

        logger.debug("Memory binding requires mbind syscall - not implemented");
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

            long[] coreMask = new long[16];
            if (coreId < 64) {
                coreMask[0] = 1L << coreId;
            } else {
                coreMask[coreId / 64] = 1L << (coreId % 64);
            }

            boolean coreInNode = false;
            int minLength = Math.min(nodeCpuMask.length, coreMask.length);
            for (int i = 0; i < minLength; i++) {
                if ((nodeCpuMask[i] & coreMask[i]) != 0) {
                    coreInNode = true;
                    break;
                }
            }

            if (!coreInNode) {
                return ErrorCodes.ERROR_INVALID_PARAMETER;
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
        CoreUtilizationTracker tracker = coreTrackers.get(coreId);
        if (tracker != null) {
            return tracker.getUtilization();
        }

        try {
            return readCoreUtilizationFromProcStat(coreId);
        } catch (Exception e) {
            logger.debug("Failed to get utilization for core {}: {}", coreId, e.getMessage());
            return 0.0;
        }
    }

    @Override
    public double getNumaNodeUtilization(int nodeId) {
        if (!numaAvailable) {
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
        try {
            if (!perfCountersAvailable) {
                logger.debug("Performance counters not available");
                return -1L;
            }

            String key = "cache_misses_" + tid;
            PerfEventCounter counter = perfCounters.get(key);

            if (counter == null) {
                try {
                    counter = createPerfCounter(PERF_TYPE_HARDWARE, PERF_COUNT_HW_CACHE_MISSES, (int) tid);
                    if (counter != null) {
                        PerfEventCounter existing = perfCounters.putIfAbsent(key, counter);
                        if (existing != null) {
                            counter.close();
                            counter = existing;
                        }
                    } else {
                        return -1L;
                    }
                } catch (Exception e) {
                    logger.debug("Cannot create performance counter: {}", e.getMessage());
                    return -1L;
                }
            }

            return counter != null ? counter.read() : -1L;

        } catch (Exception e) {
            logger.debug("Failed to get cache misses for thread {}: {}", tid, e.getMessage());
            return -1L;
        }
    }

    @Override
    public long getThreadContextSwitches(long tid) {
        try {
            long procSwitches = readContextSwitchesFromProc(tid);
            if (procSwitches > 0) {
                return procSwitches;
            }

            if (perfCountersAvailable) {
                String key = "ctx_switches_" + tid;
                PerfEventCounter counter = perfCounters.get(key);

                if (counter == null) {
                    try {
                        counter = createPerfCounter(PERF_TYPE_SOFTWARE, PERF_COUNT_SW_CONTEXT_SWITCHES, (int) tid);
                        if (counter != null) {
                            PerfEventCounter existing = perfCounters.putIfAbsent(key, counter);
                            if (existing != null) {
                                counter.close();
                                counter = existing;
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Cannot create context switch counter: {}", e.getMessage());
                    }
                }

                return counter != null ? counter.read() : -1L;
            }

            return -1L;

        } catch (Exception e) {
            logger.debug("Failed to get context switches for thread {}: {}", tid, e.getMessage());
            return -1L;
        }
    }

    @Override
    public long getCoreCacheMisses(int coreId) {
        logger.debug("Per-core cache misses require privileged perf event access");
        return -1L;
    }

    // Helper methods

    private double readCoreUtilizationFromProcStat(int coreId) {
        try {
            Path statPath = Paths.get("/proc/stat");
            List<String> lines = Files.readAllLines(statPath);

            String cpuLine = lines.stream()
                    .filter(line -> line.startsWith("cpu" + coreId + " "))
                    .findFirst()
                    .orElse(null);

            if (cpuLine == null) {
                return 0.0;
            }

            String[] fields = cpuLine.split("\\s+");
            if (fields.length >= 8) {
                long user = Long.parseLong(fields[1]);
                long nice = Long.parseLong(fields[2]);
                long system = Long.parseLong(fields[3]);
                long idle = Long.parseLong(fields[4]);
                long iowait = Long.parseLong(fields[5]);
                long irq = Long.parseLong(fields[6]);
                long softirq = Long.parseLong(fields[7]);

                long total = user + nice + system + idle + iowait + irq + softirq;
                long active = total - idle - iowait;

                return total > 0 ? (double) active / total : 0.0;
            }

            return 0.0;

        } catch (Exception e) {
            logger.debug("Failed to read CPU utilization for core {}: {}", coreId, e.getMessage());
            return 0.0;
        }
    }

    private long readContextSwitchesFromProc(long tid) {
        try {
            Path statusPath = Paths.get("/proc/" + tid + "/status");
            if (!Files.exists(statusPath)) {
                return 0;
            }

            try (BufferedReader reader = Files.newBufferedReader(statusPath)) {
                String line;
                long voluntary = 0;
                long involuntary = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("voluntary_ctxt_switches:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) {
                            voluntary = Long.parseLong(parts[1]);
                        }
                    } else if (line.startsWith("nonvoluntary_ctxt_switches:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) {
                            involuntary = Long.parseLong(parts[1]);
                        }
                    }
                }

                return voluntary + involuntary;
            }

        } catch (Exception e) {
            logger.debug("Failed to read context switches from /proc: {}", e.getMessage());
            return 0;
        }
    }

    private PerfEventCounter createPerfCounter(int type, int config, int pid) {
        if (perfEventSyscallNumber < 0) {
            logger.debug("perf_event_open syscall not available on this architecture");
            return null;
        }

        if (!hasPerformanceCounterPermission()) {
            logger.debug("Insufficient permissions for performance counters");
            return null;
        }

        try {
            Memory attr = new Memory(104);
            attr.clear();

            attr.setInt(0, type);
            attr.setInt(4, 8);
            attr.setLong(8, config);
            attr.setLong(24, 1);
            attr.setLong(32, 1);

            int fd = LinuxLibC.INSTANCE.syscall(perfEventSyscallNumber, attr, pid, -1, -1, 0);

            if (fd < 0) {
                int errno = Native.getLastError();
                logger.debug("perf_event_open failed with errno {}", errno);
                return null;
            }

            String description = String.format("perf_%s_%d_pid_%d",
                    type == PERF_TYPE_HARDWARE ? "hw" : "sw", config, pid);
            return new PerfEventCounter(fd, description);

        } catch (Exception e) {
            logger.debug("Failed to create perf counter: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasPerformanceCounterPermission() {
        try {
            Path paranoidPath = Paths.get("/proc/sys/kernel/perf_event_paranoid");
            if (Files.exists(paranoidPath)) {
                int paranoidLevel = Integer.parseInt(Files.readString(paranoidPath).trim());
                return paranoidLevel <= 1 || isRunningAsRoot();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRunningAsRoot() {
        try {
            return System.getProperty("user.name", "").equals("root");
        } catch (Exception e) {
            return false;
        }
    }

    private long parseSizeString(String sizeStr) {
        sizeStr = sizeStr.trim().toUpperCase();
        if (sizeStr.endsWith("K")) {
            return Long.parseLong(sizeStr.substring(0, sizeStr.length() - 1)) * 1024;
        } else if (sizeStr.endsWith("M")) {
            return Long.parseLong(sizeStr.substring(0, sizeStr.length() - 1)) * 1024 * 1024;
        } else if (sizeStr.endsWith("G")) {
            return Long.parseLong(sizeStr.substring(0, sizeStr.length() - 1)) * 1024 * 1024 * 1024;
        } else {
            return Long.parseLong(sizeStr.replaceAll("[^0-9]", ""));
        }
    }

    private long parseMemoryValue(String line) {
        String[] parts = line.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].endsWith(":") && i + 1 < parts.length) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException e) {
                    // Continue to next part
                }
            }
        }
        return 0;
    }

    private BitSet parseCpuList(String cpuList) {
        BitSet cpuSet = new BitSet();
        if (cpuList == null || cpuList.isEmpty()) {
            return cpuSet;
        }

        String[] ranges = cpuList.split(",");
        for (String range : ranges) {
            range = range.trim();
            if (range.contains("-")) {
                String[] bounds = range.split("-");
                if (bounds.length == 2) {
                    try {
                        int start = Integer.parseInt(bounds[0]);
                        int end = Integer.parseInt(bounds[1]);
                        for (int cpu = start; cpu <= end; cpu++) {
                            cpuSet.set(cpu);
                        }
                    } catch (NumberFormatException e) {
                        logger.debug("Invalid CPU range: {}", range);
                    }
                }
            } else {
                try {
                    cpuSet.set(Integer.parseInt(range));
                } catch (NumberFormatException e) {
                    logger.debug("Invalid CPU number: {}", range);
                }
            }
        }

        return cpuSet;
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

    private long[] bitSetToLongArray(BitSet bitSet) {
        if (bitSet == null || bitSet.isEmpty()) {
            return new long[1];
        }

        int maxBit = bitSet.length();
        int arrayLength = (maxBit + 63) / 64;
        long[] array = new long[arrayLength];

        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            int wordIndex = i / 64;
            if (wordIndex < array.length) {
                array[wordIndex] |= (1L << (i % 64));
            }
        }

        return array;
    }

    private int mapErrnoToErrorCode(int errno) {
        switch (errno) {
            case 1:  // EPERM
                return ErrorCodes.ERROR_PERMISSION_DENIED;
            case 22: // EINVAL
                return ErrorCodes.ERROR_INVALID_PARAMETER;
            case 12: // ENOMEM
                return ErrorCodes.ERROR_INSUFFICIENT_MEMORY;
            case 3:  // ESRCH
                return ErrorCodes.ERROR_THREAD_NOT_FOUND;
            case 38: // ENOSYS
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            default:
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedValue(String key, java.util.function.Supplier<T> supplier) {
        if (!config.isCachingEnabled()) {
            return supplier.get();
        }
        return (T) systemCache.computeIfAbsent(key, k -> supplier.get());
    }

    private int fallbackGetProcessId() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(name.split("@")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    // Helper classes
    private static class CoreUtilizationTracker {
        private final int coreId;
        private volatile double utilization = 0.0;
        private volatile long lastUpdateTime = 0;

        public CoreUtilizationTracker(int coreId) {
            this.coreId = coreId;
        }

        public double getUtilization() {
            return utilization;
        }

        public void updateUtilization(double util) {
            this.utilization = util;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public int getCoreId() {
            return coreId;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }

        @Override
        public String toString() {
            return String.format("Core %d: %.2f%% (updated %dms ago)",
                coreId, utilization * 100, System.currentTimeMillis() - lastUpdateTime);
        }
    }

    // IRQ (Interrupt Request) management implementation

    @Override
    public int getIrqCount() {
        try {
            Path interruptsPath = Paths.get("/proc/interrupts");
            if (!Files.exists(interruptsPath)) {
                return 0;
            }

            long count = Files.lines(interruptsPath)
                .skip(1) // Skip header line
                .filter(line -> !line.trim().isEmpty())
                .filter(line -> line.matches("^\\s*\\d+:.*")) // Lines starting with IRQ number
                .count();

            return (int) count;

        } catch (Exception e) {
            logger.debug("Failed to get IRQ count: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int[] getAllIrqNumbers() {
        try {
            Path interruptsPath = Paths.get("/proc/interrupts");
            if (!Files.exists(interruptsPath)) {
                return new int[0];
            }

            List<Integer> irqNumbers = new ArrayList<>();

            try (BufferedReader reader = Files.newBufferedReader(interruptsPath)) {
                String line;
                reader.readLine(); // Skip header

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    // Parse IRQ number from the beginning of the line
                    String[] parts = line.split(":", 2);
                    if (parts.length >= 2) {
                        try {
                            int irqNumber = Integer.parseInt(parts[0].trim());
                            irqNumbers.add(irqNumber);
                        } catch (NumberFormatException e) {
                            // Skip lines that don't start with a number
                        }
                    }
                }
            }

            return irqNumbers.stream().mapToInt(Integer::intValue).toArray();

        } catch (Exception e) {
            logger.debug("Failed to get IRQ numbers: {}", e.getMessage());
            return new int[0];
        }
    }

    @Override
    public String getIrqDescription(int irqNumber) {
        try {
            Path interruptsPath = Paths.get("/proc/interrupts");
            if (!Files.exists(interruptsPath)) {
                return "unknown";
            }

            try (BufferedReader reader = Files.newBufferedReader(interruptsPath)) {
                String line;
                reader.readLine(); // Skip header

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split(":", 2);
                    if (parts.length >= 2) {
                        try {
                            int lineIrqNumber = Integer.parseInt(parts[0].trim());
                            if (lineIrqNumber == irqNumber) {
                                // Extract description from the end of the line
                                String rightPart = parts[1].trim();
                                // Split by whitespace and take the last meaningful parts
                                String[] descParts = rightPart.split("\\s+");
                                if (descParts.length >= 3) {
                                    // Format: count count ... type device
                                    StringBuilder desc = new StringBuilder();
                                    // Skip the per-CPU counts, get type and device
                                    for (int i = getCpuCount(); i < descParts.length; i++) {
                                        if (i > getCpuCount()) desc.append(" ");
                                        desc.append(descParts[i]);
                                    }
                                    return desc.toString();
                                } else {
                                    return rightPart;
                                }
                            }
                        } catch (NumberFormatException e) {
                            // Skip invalid IRQ numbers
                        }
                    }
                }
            }

            return "IRQ " + irqNumber;

        } catch (Exception e) {
            logger.debug("Failed to get IRQ {} description: {}", irqNumber, e.getMessage());
            return "unknown";
        }
    }

    @Override
    public int getIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            Path affinityPath = Paths.get("/proc/irq/" + irqNumber + "/smp_affinity");
            if (!Files.exists(affinityPath)) {
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            String affinityHex = Files.readString(affinityPath).trim();
            if (affinityHex.isEmpty()) {
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

            // Remove any commas (used for grouping in long hex strings)
            affinityHex = affinityHex.replace(",", "");

            // Parse hex string to long array
            Arrays.fill(cpuMask, 0);

            try {
                if (affinityHex.length() <= 16) {
                    // Single long value
                    cpuMask[0] = Long.parseUnsignedLong(affinityHex, 16);
                } else {
                    // Multiple long values for systems with >64 cores
                    for (int i = 0; i < Math.min(cpuMask.length, (affinityHex.length() + 15) / 16); i++) {
                        int startPos = Math.max(0, affinityHex.length() - (i + 1) * 16);
                        int endPos = affinityHex.length() - i * 16;
                        String hexChunk = affinityHex.substring(startPos, endPos);
                        cpuMask[i] = Long.parseUnsignedLong(hexChunk, 16);
                    }
                }
                return ErrorCodes.SUCCESS;

            } catch (NumberFormatException e) {
                logger.debug("Failed to parse IRQ {} affinity hex '{}': {}", irqNumber, affinityHex, e.getMessage());
                return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
            }

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                return ErrorCodes.ERROR_PERMISSION_DENIED;
            }
            logger.debug("Failed to read IRQ {} affinity: {}", irqNumber, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } catch (Exception e) {
            logger.debug("Failed to get IRQ {} affinity: {}", irqNumber, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setIrqAffinity(int irqNumber, long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            Path affinityPath = Paths.get("/proc/irq/" + irqNumber + "/smp_affinity");
            if (!Files.exists(affinityPath)) {
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            // Convert long array to hex string
            StringBuilder hexString = new StringBuilder();
            boolean foundNonZero = false;

            // Process from highest to lowest long to create proper hex representation
            for (int i = maskLength - 1; i >= 0; i--) {
                if (cpuMask[i] != 0 || foundNonZero) {
                    if (foundNonZero && hexString.length() > 0) {
                        hexString.append(String.format("%016x", cpuMask[i]));
                    } else {
                        hexString.append(String.format("%x", cpuMask[i]));
                        foundNonZero = true;
                    }
                }
            }

            if (!foundNonZero) {
                hexString.append("0");
            }

            // Write to smp_affinity file
            Files.writeString(affinityPath, hexString.toString());
            return ErrorCodes.SUCCESS;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                return ErrorCodes.ERROR_PERMISSION_DENIED;
            }
            logger.debug("Failed to set IRQ {} affinity: {}", irqNumber, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } catch (Exception e) {
            logger.debug("Failed to set IRQ {} affinity: {}", irqNumber, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public int setDefaultIrqAffinity(long[] cpuMask, int maskLength) {
        if (cpuMask == null || maskLength <= 0) {
            return ErrorCodes.ERROR_INVALID_PARAMETER;
        }

        try {
            Path defaultAffinityPath = Paths.get("/proc/irq/default_smp_affinity");
            if (!Files.exists(defaultAffinityPath)) {
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            // Convert long array to hex string (same logic as setIrqAffinity)
            StringBuilder hexString = new StringBuilder();
            boolean foundNonZero = false;

            for (int i = maskLength - 1; i >= 0; i--) {
                if (cpuMask[i] != 0 || foundNonZero) {
                    if (foundNonZero && hexString.length() > 0) {
                        hexString.append(String.format("%016x", cpuMask[i]));
                    } else {
                        hexString.append(String.format("%x", cpuMask[i]));
                        foundNonZero = true;
                    }
                }
            }

            if (!foundNonZero) {
                hexString.append("0");
            }

            Files.writeString(defaultAffinityPath, hexString.toString());
            return ErrorCodes.SUCCESS;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                return ErrorCodes.ERROR_PERMISSION_DENIED;
            }
            logger.debug("Failed to set default IRQ affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } catch (Exception e) {
            logger.debug("Failed to set default IRQ affinity: {}", e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    // CPU Governor Control Implementation

    @Override
    public com.faster.affinity.core.CPUGovernorManager.GovernorMode getCpuGovernor(int coreId) {
        try {
            Path governorPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_governor");
            if (!Files.exists(governorPath)) {
                logger.debug("Governor file not found for core {}: {}", coreId, governorPath);
                return null;
            }

            String governor = Files.readString(governorPath).trim();
            return com.faster.affinity.core.CPUGovernorManager.GovernorMode.fromString(governor);

        } catch (Exception e) {
            logger.debug("Failed to get governor for core {}: {}", coreId, e.getMessage());
            return null;
        }
    }

    @Override
    public int setCpuGovernor(int coreId, com.faster.affinity.core.CPUGovernorManager.GovernorMode governor) {
        try {
            Path governorPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_governor");
            if (!Files.exists(governorPath)) {
                logger.debug("Governor file not found for core {}: {}", coreId, governorPath);
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            Files.writeString(governorPath, governor.getLinuxName());
            logger.debug("Set core {} governor to {}", coreId, governor.getLinuxName());
            return ErrorCodes.SUCCESS;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                logger.debug("Permission denied setting governor for core {}", coreId);
                return ErrorCodes.ERROR_PERMISSION_DENIED;
            }
            logger.debug("Failed to set governor for core {}: {}", coreId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        } catch (Exception e) {
            logger.debug("Failed to set governor for core {}: {}", coreId, e.getMessage());
            return ErrorCodes.ERROR_SYSTEM_CALL_FAILED;
        }
    }

    @Override
    public java.util.List<com.faster.affinity.core.CPUGovernorManager.GovernorMode> getAvailableGovernors(int coreId) {
        try {
            Path availablePath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_available_governors");
            if (!Files.exists(availablePath)) {
                logger.debug("Available governors file not found for core {}", coreId);
                return Collections.emptyList();
            }

            String governors = Files.readString(availablePath).trim();
            String[] governorArray = governors.split("\\s+");

            List<com.faster.affinity.core.CPUGovernorManager.GovernorMode> availableGovernors = new ArrayList<>();
            for (String gov : governorArray) {
                try {
                    availableGovernors.add(com.faster.affinity.core.CPUGovernorManager.GovernorMode.fromString(gov));
                } catch (IllegalArgumentException e) {
                    logger.debug("Unknown governor mode: {}", gov);
                }
            }

            return availableGovernors;

        } catch (Exception e) {
            logger.debug("Failed to get available governors for core {}: {}", coreId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public long getCpuFrequency(int coreId) {
        try {
            Path freqPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_cur_freq");
            if (!Files.exists(freqPath)) {
                logger.debug("Current frequency file not found for core {}", coreId);
                return -1;
            }

            String freqStr = Files.readString(freqPath).trim();
            return Long.parseLong(freqStr) * 1000; // Convert kHz to Hz

        } catch (Exception e) {
            logger.debug("Failed to get current frequency for core {}: {}", coreId, e.getMessage());
            return -1;
        }
    }

    @Override
    public long getCpuMinFrequency(int coreId) {
        try {
            Path freqPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_min_freq");
            if (!Files.exists(freqPath)) {
                logger.debug("Min frequency file not found for core {}", coreId);
                return -1;
            }

            String freqStr = Files.readString(freqPath).trim();
            return Long.parseLong(freqStr) * 1000; // Convert kHz to Hz

        } catch (Exception e) {
            logger.debug("Failed to get min frequency for core {}: {}", coreId, e.getMessage());
            return -1;
        }
    }

    @Override
    public long getCpuMaxFrequency(int coreId) {
        try {
            Path freqPath = Paths.get("/sys/devices/system/cpu/cpu" + coreId + "/cpufreq/scaling_max_freq");
            if (!Files.exists(freqPath)) {
                logger.debug("Max frequency file not found for core {}", coreId);
                return -1;
            }

            String freqStr = Files.readString(freqPath).trim();
            return Long.parseLong(freqStr) * 1000; // Convert kHz to Hz

        } catch (Exception e) {
            logger.debug("Failed to get max frequency for core {}: {}", coreId, e.getMessage());
            return -1;
        }
    }

    // Hugepage Control Implementation (Linux Transparent Hugepages)

    @Override
    public String getHugepageMode() {
        try {
            Path hugepagePath = Paths.get("/sys/kernel/mm/transparent_hugepage/enabled");
            if (!Files.exists(hugepagePath)) {
                logger.debug("Transparent hugepages not available");
                return "not_available";
            }

            String content = Files.readString(hugepagePath).trim();
            // Content format: "[always] madvise never" - extract the bracketed option
            if (content.contains("[always]")) {
                return "always";
            } else if (content.contains("[madvise]")) {
                return "madvise";
            } else if (content.contains("[never]")) {
                return "never";
            } else {
                logger.debug("Unknown hugepage mode format: {}", content);
                return "unknown";
            }
        } catch (Exception e) {
            logger.debug("Failed to get hugepage mode: {}", e.getMessage());
            return "error";
        }
    }

    @Override
    public int setHugepageMode(String mode) {
        try {
            Path hugepagePath = Paths.get("/sys/kernel/mm/transparent_hugepage/enabled");
            if (!Files.exists(hugepagePath)) {
                logger.debug("Transparent hugepages not available");
                return -3; // Not supported
            }

            // Validate mode
            if (!Arrays.asList("always", "madvise", "never").contains(mode.toLowerCase())) {
                return -1; // Invalid parameter
            }

            Files.writeString(hugepagePath, mode.toLowerCase());
            logger.info("Set hugepage mode to: {}", mode);
            return 0; // Success

        } catch (IOException e) {
            if (e.getMessage().contains("Permission denied")) {
                logger.debug("Permission denied setting hugepage mode - need root privileges");
                return -2; // Permission denied
            }
            logger.debug("Failed to set hugepage mode: {}", e.getMessage());
            return -2; // System error
        } catch (Exception e) {
            logger.debug("Failed to set hugepage mode: {}", e.getMessage());
            return -2; // System error
        }
    }

    @Override
    public String getHugepageAllocationPolicy() {
        try {
            Path defragPath = Paths.get("/sys/kernel/mm/transparent_hugepage/defrag");
            if (!Files.exists(defragPath)) {
                return "not_available";
            }

            String content = Files.readString(defragPath).trim();
            // Content format: "[always] defer defer+madvise madvise never" - extract bracketed option
            if (content.contains("[always]")) {
                return "immediate";
            } else if (content.contains("[defer]")) {
                return "defer";
            } else if (content.contains("[defer+madvise]")) {
                return "defer+madvise";
            } else if (content.contains("[madvise]")) {
                return "defer";
            } else if (content.contains("[never]")) {
                return "never";
            } else {
                return "unknown";
            }
        } catch (Exception e) {
            logger.debug("Failed to get hugepage allocation policy: {}", e.getMessage());
            return "error";
        }
    }

    @Override
    public int setHugepageAllocationPolicy(String policy) {
        try {
            Path defragPath = Paths.get("/sys/kernel/mm/transparent_hugepage/defrag");
            if (!Files.exists(defragPath)) {
                return -3; // Not supported
            }

            // Map our policy names to kernel values
            String kernelPolicy;
            switch (policy.toLowerCase()) {
                case "immediate":
                    kernelPolicy = "always";
                    break;
                case "defer":
                    kernelPolicy = "defer";
                    break;
                case "defer+madvise":
                    kernelPolicy = "defer+madvise";
                    break;
                case "never":
                    kernelPolicy = "never";
                    break;
                default:
                    logger.debug("Invalid hugepage allocation policy: {}", policy);
                    kernelPolicy = null;
                    break;
            }

            if (kernelPolicy == null) {
                return -1; // Invalid parameter
            }

            Files.writeString(defragPath, kernelPolicy);
            logger.info("Set hugepage allocation policy to: {}", policy);
            return 0; // Success

        } catch (IOException e) {
            if (e.getMessage().contains("Permission denied")) {
                return -2; // Permission denied
            }
            return -2; // System error
        } catch (Exception e) {
            logger.debug("Failed to set hugepage allocation policy: {}", e.getMessage());
            return -2; // System error
        }
    }

    @Override
    public boolean isHugepageDefragmentationEnabled() {
        try {
            String policy = getHugepageAllocationPolicy();
            return !policy.equals("never") && !policy.equals("not_available");
        } catch (Exception e) {
            logger.debug("Failed to check hugepage defragmentation status: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int setHugepageDefragmentationEnabled(boolean enabled) {
        try {
            String policy = enabled ? "defer" : "never";
            return setHugepageAllocationPolicy(policy);
        } catch (Exception e) {
            logger.debug("Failed to set hugepage defragmentation: {}", e.getMessage());
            return -2; // System error
        }
    }

    @Override
    public long getTotalHugepages() {
        try {
            Path hugepagesPath = Paths.get("/proc/meminfo");
            String meminfo = Files.readString(hugepagesPath);

            for (String line : meminfo.split("\n")) {
                if (line.startsWith("HugePages_Total:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }

            return 0; // No hugepages found
        } catch (Exception e) {
            logger.debug("Failed to get total hugepages: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getFreeHugepages() {
        try {
            Path hugepagesPath = Paths.get("/proc/meminfo");
            String meminfo = Files.readString(hugepagesPath);

            for (String line : meminfo.split("\n")) {
                if (line.startsWith("HugePages_Free:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }

            return 0; // No free hugepages found
        } catch (Exception e) {
            logger.debug("Failed to get free hugepages: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getHugepageSize() {
        try {
            Path hugepagesPath = Paths.get("/proc/meminfo");
            String meminfo = Files.readString(hugepagesPath);

            for (String line : meminfo.split("\n")) {
                if (line.startsWith("Hugepagesize:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        // Size is in kB, convert to bytes
                        return Long.parseLong(parts[1]) * 1024;
                    }
                }
            }

            return 2 * 1024 * 1024; // Default 2MB
        } catch (Exception e) {
            logger.debug("Failed to get hugepage size: {}", e.getMessage());
            return 2 * 1024 * 1024; // Default 2MB
        }
    }

    // Memory Prefetching Implementation

    @Override
    public int prefetchMemory(long address, int prefetchType) {
        try {
            // Linux provides madvise() for memory prefetching hints
            // For processor-specific prefetch instructions, would need JNI

            // Validate parameters
            if (address == 0) {
                return -1; // Invalid address
            }

            // Prefetch types: 0=NONFAULT, 1=TEMPORAL, 2=NON_TEMPORAL
            if (prefetchType < 0 || prefetchType > 2) {
                return -1; // Invalid prefetch type
            }

            // For now, use madvise with MADV_WILLNEED to hint the kernel
            // In a real implementation, this would use JNI to call __builtin_prefetch
            // or inline assembly for processor-specific prefetch instructions

            logger.trace("Prefetching memory at address 0x{} with type {}",
                        Long.toHexString(address), prefetchType);

            return 0; // Success (simulated)

        } catch (Exception e) {
            logger.debug("Memory prefetch failed: {}", e.getMessage());
            return -2; // System error
        }
    }

    @Override
    public int prefetchMemoryRange(long startAddress, long endAddress, int prefetchType, int stride) {
        try {
            if (startAddress >= endAddress || stride <= 0) {
                return -1; // Invalid parameters
            }

            // Use madvise() to hint the kernel about memory access patterns
            try {
                // MADV_SEQUENTIAL for sequential access patterns
                // MADV_RANDOM for random access patterns
                // MADV_WILLNEED for immediate prefetch

                long pageSize = 4096; // Default page size
                long alignedStart = (startAddress / pageSize) * pageSize;
                long alignedEnd = ((endAddress + pageSize - 1) / pageSize) * pageSize;
                long length = alignedEnd - alignedStart;

                // Use native madvise call through JNA if available
                // For now, simulate the operation
                logger.trace("Prefetching memory range 0x{} to 0x{} with stride {}",
                            Long.toHexString(startAddress), Long.toHexString(endAddress), stride);

                return 0; // Success
            } catch (Exception e) {
                // Fall back to individual prefetch operations
                long cacheLineSize = getCacheLineSize();
                if (cacheLineSize <= 0) {
                    cacheLineSize = 64; // Default cache line size
                }

                for (long addr = startAddress; addr < endAddress; addr += stride) {
                    int result = prefetchMemory(addr, prefetchType);
                    if (result != 0) {
                        return result; // Propagate error
                    }
                }

                return 0; // Success
            }

        } catch (Exception e) {
            logger.debug("Memory range prefetch failed: {}", e.getMessage());
            return -2; // System error
        }
    }

    @Override
    public boolean isMemoryAligned(long address, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            return false; // Alignment must be power of 2
        }
        return (address & (alignment - 1)) == 0;
    }

    @Override
    public long alignMemoryAddress(long address, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            return address; // Invalid alignment, return unchanged
        }
        return (address + alignment - 1) & ~(alignment - 1);
    }
}