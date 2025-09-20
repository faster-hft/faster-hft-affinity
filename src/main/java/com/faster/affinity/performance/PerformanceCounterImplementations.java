package com.faster.affinity.performance;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced performance counter implementations for both Linux and Windows.
 * Provides hardware performance monitoring including cache misses and context switches.
 */
public class PerformanceCounterImplementations {

    /**
     * Linux performance counter implementation using perf_event_open system call
     */
    public static class LinuxPerfCounters {
        private static final Logger logger = LoggerFactory.getLogger(LinuxPerfCounters.class);

        // Linux perf event interface
        private interface LinuxPerf extends Library {
            LinuxPerf INSTANCE = Native.load("c", LinuxPerf.class);

            int syscall(int number, Object... args);
            int close(int fd);
            long read(int fd, Pointer buffer, long count);
            int ioctl(int fd, int request, Object... args);
        }

        // perf_event_open syscall number (x86_64)
        private static final int SYS_perf_event_open = 298;

        // Performance event types
        private static final int PERF_TYPE_HARDWARE = 0;
        private static final int PERF_TYPE_SOFTWARE = 1;

        // Hardware events
        private static final int PERF_COUNT_HW_CACHE_MISSES = 3;
        private static final int PERF_COUNT_HW_CACHE_REFERENCES = 2;

        // Software events
        private static final int PERF_COUNT_SW_CONTEXT_SWITCHES = 3;
        private static final int PERF_COUNT_SW_TASK_CLOCK = 1;

        // Event flags
        private static final int PERF_FLAG_PID_CGROUP = 4;

        private final ConcurrentHashMap<String, PerfEventCounter> counters = new ConcurrentHashMap<>();

        public long getThreadCacheMisses(long tid) {
            try {
                String key = "cache_misses_" + tid;
                PerfEventCounter counter = counters.computeIfAbsent(key, k ->
                        createPerfCounter(PERF_TYPE_HARDWARE, PERF_COUNT_HW_CACHE_MISSES, (int)tid));

                return counter != null ? counter.read() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get cache misses for thread {}: {}", tid, e.getMessage());
                return 0;
            }
        }

        public long getThreadContextSwitches(long tid) {
            try {
                // First try reading from /proc/pid/status (more reliable)
                long procSwitches = readContextSwitchesFromProc(tid);
                if (procSwitches > 0) {
                    return procSwitches;
                }

                // Fallback to perf events
                String key = "ctx_switches_" + tid;
                PerfEventCounter counter = counters.computeIfAbsent(key, k ->
                        createPerfCounter(PERF_TYPE_SOFTWARE, PERF_COUNT_SW_CONTEXT_SWITCHES, (int)tid));

                return counter != null ? counter.read() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get context switches for thread {}: {}", tid, e.getMessage());
                return 0;
            }
        }

        public long getCoreCacheMisses(int coreId) {
            try {
                // Create a per-CPU counter for cache misses
                String key = "core_cache_misses_" + coreId;
                PerfEventCounter counter = counters.computeIfAbsent(key, k ->
                        createCpuPerfCounter(PERF_TYPE_HARDWARE, PERF_COUNT_HW_CACHE_MISSES, coreId));

                return counter != null ? counter.read() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get cache misses for core {}: {}", coreId, e.getMessage());
                return 0;
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
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("voluntary_ctxt_switches:")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length > 1) {
                                long voluntary = Long.parseLong(parts[1]);

                                // Also try to get involuntary switches
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("nonvoluntary_ctxt_switches:")) {
                                        String[] nvParts = line.split("\\s+");
                                        if (nvParts.length > 1) {
                                            long involuntary = Long.parseLong(nvParts[1]);
                                            return voluntary + involuntary;
                                        }
                                        break;
                                    }
                                }

                                return voluntary;
                            }
                        }
                    }
                }

                return 0;

            } catch (Exception e) {
                logger.debug("Failed to read context switches from /proc/{}/status: {}", tid, e.getMessage());
                return 0;
            }
        }

        private PerfEventCounter createPerfCounter(int type, int config, int pid) {
            try {
                Memory attr = new Memory(104);
                attr.clear();

                attr.setInt(0, type);
                attr.setInt(4, 8);
                attr.setLong(8, config);
                attr.setLong(24, 1);
                attr.setLong(32, 1);

                int fd = LinuxPerf.INSTANCE.syscall(SYS_perf_event_open, attr, pid, -1, -1, 0);

                if (fd < 0) {
                    logger.debug("perf_event_open failed for pid {}, type {}, config {}", pid, type, config);
                    return null;
                }

                String description = String.format("%s_%s_tid_%d",
                        type == PERF_TYPE_HARDWARE ? "hw" : "sw",
                        config == PERF_COUNT_HW_CACHE_MISSES ? "cache_miss" :
                                config == PERF_COUNT_SW_CONTEXT_SWITCHES ? "ctx_switch" : "counter",
                        pid);

                return new PerfEventCounter(fd, description);

            } catch (Exception e) {
                logger.debug("Failed to create perf counter: {}", e.getMessage());
                return null;
            }
        }

        // Update createCpuPerfCounter method (around line 110):
        private PerfEventCounter createCpuPerfCounter(int type, int config, int cpu) {
            try {
                Memory attr = new Memory(104);
                attr.clear();

                attr.setInt(0, type);
                attr.setInt(4, 8);
                attr.setLong(8, config);
                attr.setLong(24, 1);

                int fd = LinuxPerf.INSTANCE.syscall(SYS_perf_event_open, attr, -1, cpu, -1, 0);

                if (fd < 0) {
                    logger.debug("CPU perf_event_open failed for cpu {}, type {}, config {}", cpu, type, config);
                    return null;
                }

                String description = String.format("%s_cpu_%d",
                        type == PERF_TYPE_HARDWARE ? "hw_cache_miss" : "sw_counter", cpu);

                return new PerfEventCounter(fd, description);

            } catch (Exception e) {
                logger.debug("Failed to create CPU perf counter: {}", e.getMessage());
                return null;
            }
        }

        public void cleanup() {
            counters.values().forEach(PerfEventCounter::close);
            counters.clear();
        }
    }

    /**
     * Windows performance counter implementation using Performance Counter API
     */
    public static class WindowsPerfCounters {
        private static final Logger logger = LoggerFactory.getLogger(WindowsPerfCounters.class);

        // Performance Data Helper (PDH) interface
        private interface WindowsPdh extends Library {
            WindowsPdh INSTANCE = loadPdhLibrary();

            int PdhOpenQuery(String szDataSource, Pointer dwUserData, PointerByReference phQuery);
            int PdhAddCounter(Pointer hQuery, String szFullCounterPath, Pointer dwUserData, PointerByReference phCounter);
            int PdhCollectQueryData(Pointer hQuery);
            int PdhGetFormattedCounterValue(Pointer hCounter, int dwFormat, IntByReference lpdwType, Pointer pValue);
            int PdhCloseQuery(Pointer hQuery);
            int PdhEnumObjects(String szDataSource, String szMachineName, Pointer mszObjectList,
                               IntByReference pcchBufferSize, int dwDetailLevel, boolean bRefresh);

            int PDH_FMT_LONG = 0x00000100;
            int PDH_FMT_DOUBLE = 0x00000200;
        }

        private static WindowsPdh loadPdhLibrary() {
            try {
                return Native.load("pdh", WindowsPdh.class);
            } catch (UnsatisfiedLinkError e) {
                return null;
            }
        }

        // Process and Thread Information interface
        private interface WindowsPsapi extends Library {
            WindowsPsapi INSTANCE = loadPsapiLibrary();

            boolean GetProcessMemoryInfo(Pointer hProcess, Pointer ppsmemCounters, int cb);
            boolean GetPerformanceInfo(Pointer pPerformanceInformation, int cb);
        }

        private static WindowsPsapi loadPsapiLibrary() {
            try {
                return Native.load("psapi", WindowsPsapi.class);
            } catch (UnsatisfiedLinkError e) {
                return null;
            }
        }

        private final ConcurrentHashMap<String, WindowsPerfCounter> counters = new ConcurrentHashMap<>();
        private volatile boolean pdhAvailable = false;

        public WindowsPerfCounters() {
            pdhAvailable = WindowsPdh.INSTANCE != null;
        }

        public long getThreadCacheMisses(long tid) {
            try {
                // Windows doesn't provide direct per-thread cache miss counters
                // We can approximate using process-level counters
                String key = "thread_cache_misses_" + tid;
                WindowsPerfCounter counter = counters.computeIfAbsent(key, k ->
                        createProcessCacheCounter((int)tid));

                return counter != null ? counter.getValue() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get cache misses for thread {}: {}", tid, e.getMessage());
                return 0;
            }
        }

        public long getThreadContextSwitches(long tid) {
            try {
                // Try to get process-level context switch information
                String key = "thread_ctx_switches_" + tid;
                WindowsPerfCounter counter = counters.computeIfAbsent(key, k ->
                        createProcessContextSwitchCounter((int)tid));

                return counter != null ? counter.getValue() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get context switches for thread {}: {}", tid, e.getMessage());
                return 0;
            }
        }

        public long getCoreCacheMisses(int coreId) {
            try {
                String key = "core_cache_misses_" + coreId;
                WindowsPerfCounter counter = counters.computeIfAbsent(key, k ->
                        createProcessorCacheCounter(coreId));

                return counter != null ? counter.getValue() : 0;

            } catch (Exception e) {
                logger.debug("Failed to get cache misses for core {}: {}", coreId, e.getMessage());
                return 0;
            }
        }

        private WindowsPerfCounter createProcessCacheCounter(int processId) {
            if (!pdhAvailable) {
                return null;
            }

            try {
                String counterPath = String.format("\\Process(%d)\\Page Faults/sec", processId);
                return new WindowsPerfCounter(counterPath);
            } catch (Exception e) {
                logger.debug("Failed to create process cache counter: {}", e.getMessage());
                return null;
            }
        }

        private WindowsPerfCounter createProcessContextSwitchCounter(int processId) {
            if (!pdhAvailable) {
                return null;
            }

            try {
                // Windows doesn't have a direct per-process context switch counter
                // Use system-wide context switches as approximation
                String counterPath = "\\System\\Context Switches/sec";
                return new WindowsPerfCounter(counterPath);
            } catch (Exception e) {
                logger.debug("Failed to create context switch counter: {}", e.getMessage());
                return null;
            }
        }

        private WindowsPerfCounter createProcessorCacheCounter(int coreId) {
            if (!pdhAvailable) {
                return null;
            }

            try {
                // Try L2 cache misses counter
                String counterPath = String.format("\\Processor(%d)\\%% C2 Time", coreId);
                return new WindowsPerfCounter(counterPath);
            } catch (Exception e) {
                logger.debug("Failed to create processor cache counter: {}", e.getMessage());
                return null;
            }
        }

        public void cleanup() {
            counters.values().forEach(WindowsPerfCounter::close);
            counters.clear();
        }

        private static class WindowsPerfCounter {
            private Pointer queryHandle;
            private Pointer counterHandle;
            private volatile long lastValue = 0;
            private volatile long lastUpdateTime = 0;

            public WindowsPerfCounter(String counterPath) throws Exception {
                if (WindowsPdh.INSTANCE == null) {
                    throw new Exception("PDH not available");
                }

                PointerByReference queryRef = new PointerByReference();
                int result = WindowsPdh.INSTANCE.PdhOpenQuery(null, null, queryRef);
                if (result != 0) {
                    throw new Exception("Failed to open PDH query: " + result);
                }

                queryHandle = queryRef.getValue();

                PointerByReference counterRef = new PointerByReference();
                result = WindowsPdh.INSTANCE.PdhAddCounter(queryHandle, counterPath, null, counterRef);
                if (result != 0) {
                    WindowsPdh.INSTANCE.PdhCloseQuery(queryHandle);
                    throw new Exception("Failed to add counter " + counterPath + ": " + result);
                }

                counterHandle = counterRef.getValue();

                // Initial collection
                WindowsPdh.INSTANCE.PdhCollectQueryData(queryHandle);
            }

            public long getValue() {
                long now = System.currentTimeMillis();
                if (now - lastUpdateTime > 1000) { // Update every second
                    updateValue();
                }
                return lastValue;
            }

            private void updateValue() {
                if (queryHandle == null || counterHandle == null) {
                    return;
                }

                try {
                    int result = WindowsPdh.INSTANCE.PdhCollectQueryData(queryHandle);
                    if (result == 0) {
                        IntByReference type = new IntByReference();
                        Memory value = new Memory(16);

                        result = WindowsPdh.INSTANCE.PdhGetFormattedCounterValue(counterHandle,
                                WindowsPdh.PDH_FMT_LONG, type, value);

                        if (result == 0) {
                            lastValue = value.getLong(8);
                            lastUpdateTime = System.currentTimeMillis();
                        }
                    }
                } catch (Exception e) {
                    // Keep last known value
                }
            }

            public void close() {
                if (queryHandle != null) {
                    try {
                        WindowsPdh.INSTANCE.PdhCloseQuery(queryHandle);
                    } catch (Exception e) {
                        // Ignore
                    }
                    queryHandle = null;
                    counterHandle = null;
                }
            }
        }
    }
}