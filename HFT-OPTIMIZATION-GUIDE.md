# 🚀 HFT Optimization Guide: Advanced Strategies for Ultra-Low Latency

## 📋 Table of Contents

1. [Overview](#overview)
2. [Thread Architecture Patterns](#thread-architecture-patterns)
3. [Memory Management Strategies](#memory-management-strategies)
4. [System-Level Optimizations](#system-level-optimizations)
5. [Network & I/O Optimization](#network--io-optimization)
6. [Latency vs Throughput Trade-offs](#latency-vs-throughput-trade-offs)
7. [Performance Monitoring & Measurement](#performance-monitoring--measurement)
8. [Common Anti-Patterns](#common-anti-patterns)

## 🎯 Overview

This guide provides battle-tested optimization strategies for HFT systems using the faster-hft-affinity library. These techniques are derived from real-world trading systems achieving sub-100ns latencies.

**Prerequisites**: Complete [HFT-PREREQUISITES.md](HFT-PREREQUISITES.md) before proceeding.

**Performance Targets**:
- **Thread affinity setting**: < 50ns
- **Memory allocation**: < 20ns (pool allocators)
- **Context switches**: < 500ns (when unavoidable)
- **Cache miss penalty**: < 200ns (optimized data structures)

---

## 🧵 Thread Architecture Patterns

### 1. Dedicated Core Strategy

**Principle**: One critical thread per physical core, with sibling cores offline.

```java
public class DedicatedCoreStrategy {
    private final AffinityLibrary affinity;
    private final Map<ThreadRole, Integer> coreAssignments;

    public enum ThreadRole {
        MARKET_DATA_HANDLER(2),    // Dedicated core 2
        ORDER_PROCESSOR(4),        // Dedicated core 4
        RISK_ENGINE(6),           // Dedicated core 6
        POSITION_MANAGER(8);      // Dedicated core 8

        private final int coreId;
        ThreadRole(int coreId) { this.coreId = coreId; }
        public int getCoreId() { return coreId; }
    }

    public void bindThreadToRole(ThreadRole role) {
        BitSet cpuMask = new BitSet();
        cpuMask.set(role.getCoreId());

        var result = affinity.setCurrentThreadAffinity(cpuMask);
        if (result.isFailure()) {
            throw new RuntimeException("Failed to bind thread to core " +
                role.getCoreId() + ": " + result.getError().getMessage());
        }

        // Disable hyperthreading sibling
        disableHyperthreadingSibling(role.getCoreId());

        // Set real-time priority
        setRealtimePriority(99);
    }

    private void disableHyperthreadingSibling(int coreId) {
        // Assuming sibling is coreId + 1 for Intel topology
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "echo 0 > /sys/devices/system/cpu/cpu" + (coreId + 1) + "/online");
            pb.start().waitFor();
        } catch (Exception e) {
            // Log warning but continue
            System.err.println("Warning: Could not disable HT sibling: " + e.getMessage());
        }
    }
}
```

### 2. NUMA-Aware Thread Placement

**Principle**: Place related threads on the same NUMA node to minimize memory access latency.

```java
public class NumaAwareThreadPlacement {
    private final AffinityLibrary affinity;
    private final NUMAManager numa;

    public static class NumaTopology {
        private final Map<Integer, Set<Integer>> nodeToChips = new HashMap<>();

        public NumaTopology(AffinityLibrary affinity) {
            // Detect NUMA topology
            var topology = affinity.getNUMAManager().getSystemTopology();
            if (topology.isSuccess()) {
                buildNodeMapping(topology.getValue());
            }
        }

        public Set<Integer> getCoresForNode(int numaNode) {
            return nodeToChips.getOrDefault(numaNode, Collections.emptySet());
        }
    }

    public void setupTradingSystem() {
        NumaTopology topology = new NumaTopology(affinity);

        // Place market data processing on NUMA node 0
        setupMarketDataProcessor(topology.getCoresForNode(0));

        // Place order processing on NUMA node 1
        setupOrderProcessor(topology.getCoresForNode(1));

        // Place risk management on NUMA node 2
        setupRiskEngine(topology.getCoresForNode(2));
    }

    private void setupMarketDataProcessor(Set<Integer> availableCores) {
        if (availableCores.size() < 4) {
            throw new IllegalStateException("Insufficient cores for market data processing");
        }

        Iterator<Integer> coreIter = availableCores.iterator();

        // Main market data thread - highest priority core
        int primaryCore = coreIter.next();
        bindMarketDataThread(primaryCore);

        // Secondary feeds - additional cores
        while (coreIter.hasNext() && needsMoreFeedHandlers()) {
            int secondaryCore = coreIter.next();
            bindSecondaryFeedHandler(secondaryCore);
        }
    }

    private void bindMarketDataThread(int coreId) {
        Thread marketDataThread = new Thread(() -> {
            // Bind current thread to core
            BitSet cpuMask = new BitSet();
            cpuMask.set(coreId);
            affinity.setCurrentThreadAffinity(cpuMask);

            // Allocate data structures on same NUMA node
            int numaNode = numa.getCpuNumaNode(coreId);
            allocateMarketDataBuffers(numaNode);

            // Run market data processing loop
            processMarketData();
        });

        marketDataThread.setName("MarketData-Core" + coreId);
        marketDataThread.start();
    }
}
```

### 3. Interrupt Isolation Strategy

**Principle**: Move all interrupts away from critical trading cores.

```java
public class InterruptIsolationManager {
    private final Set<Integer> tradingCores;
    private final Set<Integer> systemCores;

    public InterruptIsolationManager(Set<Integer> trading, Set<Integer> system) {
        this.tradingCores = trading;
        this.systemCores = system;
    }

    public void isolateInterrupts() {
        try {
            // Move all IRQs to system cores
            moveIRQsToSystemCores();

            // Move kernel threads to system cores
            moveKernelThreadsToSystemCores();

            // Configure network IRQ affinity
            configureNetworkIRQs();

            // Verify isolation
            verifyIsolation();

        } catch (Exception e) {
            throw new RuntimeException("Interrupt isolation failed", e);
        }
    }

    private void moveIRQsToSystemCores() throws IOException {
        // Get list of all IRQs
        List<String> irqs = Files.list(Paths.get("/proc/irq"))
            .filter(Files::isDirectory)
            .map(path -> path.getFileName().toString())
            .filter(name -> name.matches("\\d+"))
            .collect(Collectors.toList());

        String systemCoresMask = createCpuMask(systemCores);

        for (String irq : irqs) {
            Path affinityPath = Paths.get("/proc/irq/" + irq + "/smp_affinity_list");
            if (Files.exists(affinityPath)) {
                try {
                    Files.write(affinityPath, systemCoresMask.getBytes());
                } catch (IOException e) {
                    // Some IRQs can't be moved, log and continue
                    System.err.println("Warning: Could not move IRQ " + irq);
                }
            }
        }
    }

    private void configureNetworkIRQs() {
        // For multi-queue NICs, distribute queues across system cores
        try {
            List<String> networkInterfaces = getNetworkInterfaces();

            for (String iface : networkInterfaces) {
                configureNicQueues(iface);
            }
        } catch (Exception e) {
            System.err.println("Warning: Network IRQ configuration failed: " + e.getMessage());
        }
    }

    private void configureNicQueues(String iface) throws IOException {
        Path queuesPath = Paths.get("/sys/class/net/" + iface + "/queues");
        if (!Files.exists(queuesPath)) return;

        // Get RX queues
        List<Path> rxQueues = Files.list(queuesPath)
            .filter(path -> path.getFileName().toString().startsWith("rx-"))
            .collect(Collectors.toList());

        // Distribute queues across system cores
        Iterator<Integer> coreIter = systemCores.iterator();
        for (Path queuePath : rxQueues) {
            if (!coreIter.hasNext()) {
                coreIter = systemCores.iterator(); // Wrap around
            }

            int targetCore = coreIter.next();
            Path rpsPath = queuePath.resolve("rps_cpus");

            if (Files.exists(rpsPath)) {
                String coreMask = String.format("%x", 1 << targetCore);
                Files.write(rpsPath, coreMask.getBytes());
            }
        }
    }
}
```

### 4. Priority-Based Thread Hierarchy

**Principle**: Use real-time scheduling priorities to ensure critical threads preempt less important ones.

```java
public class ThreadPriorityManager {

    public enum Priority {
        CRITICAL_PATH(99),      // Order processing, risk checks
        HIGH_THROUGHPUT(95),    // Market data ingestion
        BACKGROUND_RT(90),      // Position updates, logging
        NORMAL_RT(85),          // Database writes, reporting
        BEST_EFFORT(50);        // Administrative tasks

        private final int value;
        Priority(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    public static void setThreadPriority(Priority priority) {
        try {
            // Set real-time FIFO scheduling
            ProcessBuilder pb = new ProcessBuilder("chrt", "-f",
                String.valueOf(priority.getValue()), "-p",
                String.valueOf(ProcessHandle.current().pid()));

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                // Fallback to Java priority if real-time scheduling fails
                int javaPriority = mapToJavaPriority(priority);
                Thread.currentThread().setPriority(javaPriority);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to set thread priority", e);
        }
    }

    private static int mapToJavaPriority(Priority priority) {
        switch (priority) {
            case CRITICAL_PATH: return Thread.MAX_PRIORITY;
            case HIGH_THROUGHPUT: return Thread.MAX_PRIORITY - 1;
            case BACKGROUND_RT: return Thread.NORM_PRIORITY + 2;
            case NORMAL_RT: return Thread.NORM_PRIORITY + 1;
            case BEST_EFFORT: return Thread.NORM_PRIORITY;
            default: return Thread.NORM_PRIORITY;
        }
    }
}
```

---

## 💾 Memory Management Strategies

### 1. NUMA-Local Allocation Patterns

**Principle**: Allocate memory on the same NUMA node as the processing thread.

```java
public class NumaLocalAllocator {
    private final NUMAManager numa;
    private final Map<Integer, ByteBuffer> nodeBuffers = new ConcurrentHashMap<>();

    public NumaLocalAllocator(AffinityLibrary affinity) {
        this.numa = affinity.getNUMAManager();
        initializeNodeBuffers();
    }

    private void initializeNodeBuffers() {
        var topology = numa.getSystemTopology();
        if (topology.isFailure()) return;

        int nodeCount = topology.getValue().getNumaNodeCount();
        for (int node = 0; node < nodeCount; node++) {
            // Pre-allocate large buffer on each node
            var buffer = numa.allocateNuma(64 * 1024 * 1024, node); // 64MB per node
            if (buffer.isSuccess()) {
                nodeBuffers.put(node, buffer.getValue());
            }
        }
    }

    public <T> T allocateLocal(Class<T> type) {
        // Get current thread's NUMA node
        int currentCore = getCurrentCore();
        int numaNode = numa.getCpuNumaNode(currentCore);

        // Allocate from node-local buffer
        return allocateFromNodeBuffer(type, numaNode);
    }

    public MarketDataBuffer createMarketDataBuffer(int expectedMessages) {
        int currentCore = getCurrentCore();
        int numaNode = numa.getCpuNumaNode(currentCore);

        // Calculate required size with alignment padding
        int messageSize = MarketDataMessage.SIZE;
        int totalSize = expectedMessages * messageSize;
        int alignedSize = alignTo64Bytes(totalSize);

        // Allocate NUMA-local memory
        var buffer = numa.allocateNuma(alignedSize, numaNode);
        if (buffer.isFailure()) {
            throw new RuntimeException("NUMA allocation failed: " + buffer.getError().getMessage());
        }

        return new MarketDataBuffer(buffer.getValue(), expectedMessages);
    }

    private int getCurrentCore() {
        try {
            // Use affinity library to get current core
            var affinity = AffinityLibraryFactory.getDefault();
            var currentAffinity = affinity.getCurrentThreadAffinity();

            if (currentAffinity.isSuccess()) {
                BitSet mask = currentAffinity.getValue();
                return mask.nextSetBit(0);
            }
        } catch (Exception e) {
            // Fallback to system call
        }

        // Fallback: use sched_getcpu() equivalent
        return Runtime.getRuntime().availableProcessors() % 4; // Simple fallback
    }
}
```

### 2. Lock-Free Memory Pools

**Principle**: Pre-allocate memory pools to avoid allocation overhead in critical paths.

```java
public class LockFreeObjectPool<T> {
    private final Supplier<T> factory;
    private final Consumer<T> resetFunction;
    private final AtomicReferenceArray<T> pool;
    private final AtomicInteger headIndex = new AtomicInteger(0);
    private final AtomicInteger tailIndex = new AtomicInteger(0);
    private final int capacity;
    private final int mask;

    public LockFreeObjectPool(int capacity, Supplier<T> factory, Consumer<T> resetFunction) {
        // Ensure capacity is power of 2 for efficient modulo operation
        this.capacity = Integer.highestOneBit(capacity - 1) * 2;
        this.mask = this.capacity - 1;
        this.factory = factory;
        this.resetFunction = resetFunction;
        this.pool = new AtomicReferenceArray<>(this.capacity);

        // Pre-populate pool
        for (int i = 0; i < this.capacity; i++) {
            pool.set(i, factory.get());
        }
        tailIndex.set(this.capacity);
    }

    public T acquire() {
        while (true) {
            int head = headIndex.get();
            int tail = tailIndex.get();

            if (head == tail) {
                // Pool is empty, create new object (fallback)
                return factory.get();
            }

            T object = pool.get(head & mask);
            if (object != null && headIndex.compareAndSet(head, head + 1)) {
                pool.set(head & mask, null);
                return object;
            }

            // Retry if CAS failed
            Thread.onSpinWait(); // Java 9+ spin hint
        }
    }

    public void release(T object) {
        if (object == null) return;

        // Reset object state
        resetFunction.accept(object);

        while (true) {
            int tail = tailIndex.get();
            int head = headIndex.get();

            // Check if pool is full
            if (tail - head >= capacity) {
                // Pool is full, discard object
                return;
            }

            if (pool.compareAndSet(tail & mask, null, object)) {
                tailIndex.compareAndSet(tail, tail + 1);
                return;
            }

            // Retry if CAS failed
            Thread.onSpinWait();
        }
    }

    // Usage example for Order objects
    public static LockFreeObjectPool<Order> createOrderPool(int capacity) {
        return new LockFreeObjectPool<>(
            capacity,
            () -> new Order(),           // Factory
            order -> order.reset()       // Reset function
        );
    }
}
```

### 3. Cache-Aligned Data Structures

**Principle**: Align data structures to cache line boundaries to prevent false sharing.

```java
public class CacheAlignedStructures {

    // Cache line size constants for different architectures
    private static final int CACHE_LINE_SIZE = 64; // x86_64, ARM64
    private static final int CACHE_LINE_SIZE_CONSERVATIVE = 128; // Safe for all architectures

    @jdk.internal.vm.annotation.Contended
    public static class PaddedAtomicLong {
        private volatile long value;

        // Explicit padding to prevent false sharing
        private long p1, p2, p3, p4, p5, p6, p7;

        public long get() { return value; }

        public void set(long newValue) { this.value = newValue; }

        public boolean compareAndSet(long expected, long update) {
            return UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expected, update);
        }

        // Unsafe mechanics for CAS
        private static final sun.misc.Unsafe UNSAFE;
        private static final long VALUE_OFFSET;

        static {
            try {
                UNSAFE = getUnsafe();
                VALUE_OFFSET = UNSAFE.objectFieldOffset(
                    PaddedAtomicLong.class.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // High-performance market data structure
    @jdk.internal.vm.annotation.Contended
    public static class MarketDataSnapshot {
        // Hot fields - frequently accessed together
        private volatile double bidPrice;
        private volatile double askPrice;
        private volatile long bidSize;
        private volatile long askSize;
        private volatile long timestamp;

        // Padding to next cache line
        private long p1, p2, p3, p4, p5, p6, p7;

        // Cold fields - less frequently accessed
        private String symbol;
        private int symbolId;
        private MarketType marketType;
        private long sequenceNumber;

        // Update methods designed to minimize cache misses
        public void updateQuote(double bid, double ask, long bidSz, long askSz) {
            // Update all hot fields in single cache line
            this.timestamp = System.nanoTime();
            this.bidPrice = bid;
            this.askPrice = ask;
            this.bidSize = bidSz;
            this.askSize = askSz;

            // Memory barrier to ensure visibility
            VarHandle.storeStoreFence();
        }
    }

    // Lock-free ring buffer with cache padding
    public static class CacheAlignedRingBuffer<T> {
        private final int capacity;
        private final int mask;
        private final Object[] buffer;

        // Separate cache lines for head and tail to prevent false sharing
        @jdk.internal.vm.annotation.Contended("head")
        private volatile long head = 0;

        @jdk.internal.vm.annotation.Contended("tail")
        private volatile long tail = 0;

        public CacheAlignedRingBuffer(int capacity) {
            this.capacity = Integer.highestOneBit(capacity - 1) * 2;
            this.mask = this.capacity - 1;
            this.buffer = new Object[this.capacity];
        }

        public boolean offer(T item) {
            long currentTail = tail;
            long nextTail = currentTail + 1;

            if (nextTail - head > capacity) {
                return false; // Buffer full
            }

            buffer[(int)(currentTail & mask)] = item;

            // Ensure item is visible before updating tail
            VarHandle.storeStoreFence();
            tail = nextTail;
            return true;
        }

        @SuppressWarnings("unchecked")
        public T poll() {
            long currentHead = head;

            if (currentHead >= tail) {
                return null; // Buffer empty
            }

            T item = (T) buffer[(int)(currentHead & mask)];
            buffer[(int)(currentHead & mask)] = null; // Help GC

            // Ensure read is complete before updating head
            VarHandle.loadLoadFence();
            head = currentHead + 1;
            return item;
        }
    }
}
```

### 4. Memory Prefetching Strategies

**Principle**: Use software and hardware prefetching to reduce memory access latency.

```java
public class PrefetchingStrategies {

    // Prefetch hints for different data access patterns
    private static final int PREFETCH_READ = 0;
    private static final int PREFETCH_WRITE = 1;
    private static final int TEMPORAL_LOCALITY_HIGH = 3;
    private static final int TEMPORAL_LOCALITY_LOW = 0;

    public static class OrderBookProcessor {
        private final PriceLevel[] priceLevels;
        private final int maxLevels;

        public OrderBookProcessor(int maxLevels) {
            this.maxLevels = maxLevels;
            this.priceLevels = new PriceLevel[maxLevels];

            // Initialize with cache-aligned allocations
            for (int i = 0; i < maxLevels; i++) {
                priceLevels[i] = new PriceLevel();
            }
        }

        public void processOrderBookUpdate(OrderBookUpdate update) {
            // Prefetch likely next levels while processing current
            int startLevel = update.getStartLevel();
            int endLevel = Math.min(startLevel + update.getLevelCount(), maxLevels);

            for (int level = startLevel; level < endLevel; level++) {
                // Prefetch next few levels
                prefetchNextLevels(level, 3);

                // Process current level
                updatePriceLevel(level, update.getPrice(level), update.getSize(level));
            }
        }

        private void prefetchNextLevels(int currentLevel, int lookAhead) {
            for (int i = 1; i <= lookAhead && currentLevel + i < maxLevels; i++) {
                PriceLevel nextLevel = priceLevels[currentLevel + i];

                // Software prefetch hint
                prefetchForRead(nextLevel);
            }
        }

        private void updatePriceLevel(int level, double price, long size) {
            PriceLevel priceLevel = priceLevels[level];

            // Prefetch for write since we'll modify
            prefetchForWrite(priceLevel);

            priceLevel.setPrice(price);
            priceLevel.setSize(size);
            priceLevel.setTimestamp(System.nanoTime());
        }

        // JNI methods for software prefetching (platform-specific)
        private native void prefetchForRead(Object obj);
        private native void prefetchForWrite(Object obj);
    }

    // Alternative: Java-based prefetching using Unsafe
    public static class UnsafePrefetcher {
        private static final sun.misc.Unsafe UNSAFE = getUnsafe();

        public static void prefetchRead(Object obj, long offset) {
            // Read without using the value to trigger prefetch
            UNSAFE.getByte(obj, offset);
        }

        public static void prefetchWrite(Object obj, long offset) {
            // Prepare cache line for write
            byte current = UNSAFE.getByte(obj, offset);
            UNSAFE.putByte(obj, offset, current);
        }

        // Streaming prefetch for large arrays
        public static void prefetchArray(Object[] array, int start, int count) {
            int prefetchDistance = 8; // Prefetch 8 elements ahead

            for (int i = start; i < start + count; i++) {
                // Process current element
                if (i < array.length) {
                    processElement(array[i]);
                }

                // Prefetch future elements
                int prefetchIndex = i + prefetchDistance;
                if (prefetchIndex < array.length && prefetchIndex < start + count) {
                    prefetchRead(array, ARRAY_BASE_OFFSET + prefetchIndex * ARRAY_INDEX_SCALE);
                }
            }
        }

        private static final long ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(Object[].class);
        private static final int ARRAY_INDEX_SCALE = UNSAFE.arrayIndexScale(Object[].class);
    }

    // Hardware prefetching optimization
    public static class StrideBasedPrefetching {

        // Optimize for hardware stride prefetcher
        public void processMarketDataArray(MarketDataMessage[] messages) {
            // Sequential access pattern - hardware prefetcher works well
            for (int i = 0; i < messages.length; i++) {
                processMessage(messages[i]);
            }
        }

        // For non-sequential patterns, help the prefetcher
        public void processOrdersByPrice(Order[] orders, double[] prices) {
            // Sort by price to create sequential memory access
            // (preprocessing cost is amortized over processing)
            Arrays.sort(orders, Comparator.comparing(Order::getPrice));

            for (Order order : orders) {
                processOrder(order);
            }
        }

        // Struct-of-Arrays vs Array-of-Structs optimization
        public static class SoAOptimization {
            // Array-of-Structs (AoS) - cache unfriendly for partial access
            static class OrderAoS {
                final Order[] orders;
                OrderAoS(int size) { orders = new Order[size]; }

                double calculateTotalValue() {
                    double total = 0;
                    for (Order order : orders) {
                        total += order.getPrice() * order.getQuantity(); // Loads entire Order object
                    }
                    return total;
                }
            }

            // Struct-of-Arrays (SoA) - cache friendly for partial access
            static class OrderSoA {
                final double[] prices;
                final int[] quantities;
                final long[] timestamps;

                OrderSoA(int size) {
                    prices = new double[size];
                    quantities = new int[size];
                    timestamps = new long[size];
                }

                double calculateTotalValue() {
                    double total = 0;
                    for (int i = 0; i < prices.length; i++) {
                        total += prices[i] * quantities[i]; // Sequential access, better cache usage
                    }
                    return total;
                }
            }
        }
    }
}
```

---

## ⚙️ System-Level Optimizations

### 1. CPU Governor and Frequency Scaling

**Principle**: Control CPU frequency scaling to ensure consistent performance.

```java
public class CPUGovernorManager {

    public enum Governor {
        PERFORMANCE("performance"),    // Max frequency always
        POWERSAVE("powersave"),       // Min frequency always
        ONDEMAND("ondemand"),         // Dynamic scaling
        CONSERVATIVE("conservative"), // Gradual scaling
        USERSPACE("userspace");       // Manual control

        private final String name;
        Governor(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static void setGovernorForCores(Set<Integer> cores, Governor governor) {
        cores.parallelStream().forEach(core -> {
            try {
                setGovernorForCore(core, governor);
            } catch (Exception e) {
                System.err.println("Failed to set governor for core " + core + ": " + e.getMessage());
            }
        });
    }

    private static void setGovernorForCore(int core, Governor governor) throws IOException {
        Path governorPath = Paths.get("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_governor");

        if (!Files.exists(governorPath)) {
            throw new IOException("Governor control not available for core " + core);
        }

        Files.write(governorPath, governor.getName().getBytes());

        // Verify the change
        String currentGovernor = Files.readString(governorPath).trim();
        if (!currentGovernor.equals(governor.getName())) {
            throw new IOException("Failed to set governor. Current: " + currentGovernor +
                                ", Expected: " + governor.getName());
        }
    }

    public static void setFrequencyRange(Set<Integer> cores, long minFreqKHz, long maxFreqKHz) {
        cores.parallelStream().forEach(core -> {
            try {
                setFrequencyRangeForCore(core, minFreqKHz, maxFreqKHz);
            } catch (Exception e) {
                System.err.println("Failed to set frequency for core " + core + ": " + e.getMessage());
            }
        });
    }

    private static void setFrequencyRangeForCore(int core, long minFreq, long maxFreq) throws IOException {
        Path minPath = Paths.get("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_min_freq");
        Path maxPath = Paths.get("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_max_freq");

        // Set max first to avoid min > max error
        if (Files.exists(maxPath)) {
            Files.write(maxPath, String.valueOf(maxFreq).getBytes());
        }

        if (Files.exists(minPath)) {
            Files.write(minPath, String.valueOf(minFreq).getBytes());
        }
    }

    // HFT-optimized system setup
    public static void setupHFTSystem(Set<Integer> tradingCores, Set<Integer> systemCores) {
        try {
            // Trading cores: Maximum performance
            setGovernorForCores(tradingCores, Governor.PERFORMANCE);

            // Get max frequency for trading cores
            int firstTradingCore = tradingCores.iterator().next();
            long maxFreq = getMaxFrequency(firstTradingCore);
            setFrequencyRange(tradingCores, maxFreq, maxFreq); // Lock to max frequency

            // System cores: Allow dynamic scaling
            setGovernorForCores(systemCores, Governor.ONDEMAND);

            // Disable CPU idle states for trading cores
            disableCIdleStates(tradingCores);

            System.out.println("HFT system optimization completed:");
            System.out.println("  Trading cores: " + tradingCores + " - PERFORMANCE @ " + maxFreq + " kHz");
            System.out.println("  System cores: " + systemCores + " - ONDEMAND");

        } catch (Exception e) {
            throw new RuntimeException("HFT system setup failed", e);
        }
    }

    private static long getMaxFrequency(int core) throws IOException {
        Path maxFreqPath = Paths.get("/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq");
        return Long.parseLong(Files.readString(maxFreqPath).trim());
    }

    private static void disableCIdleStates(Set<Integer> cores) {
        cores.forEach(core -> {
            try {
                // Disable all C-states for consistent latency
                Path idleDir = Paths.get("/sys/devices/system/cpu/cpu" + core + "/cpuidle");

                if (Files.exists(idleDir)) {
                    Files.list(idleDir)
                        .filter(path -> path.getFileName().toString().startsWith("state"))
                        .forEach(statePath -> {
                            try {
                                Path disablePath = statePath.resolve("disable");
                                if (Files.exists(disablePath)) {
                                    Files.write(disablePath, "1".getBytes());
                                }
                            } catch (IOException e) {
                                System.err.println("Warning: Could not disable idle state " + statePath);
                            }
                        });
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not access idle states for core " + core);
            }
        });
    }
}
```

### 2. Memory System Optimization

**Principle**: Configure memory subsystem for optimal latency and bandwidth.

```java
public class MemorySystemOptimizer {

    public static class HugePagesManager {

        public static void configureHugePages(long hugePagesGB) {
            try {
                // Calculate number of 2MB huge pages needed
                long hugePagesCount = (hugePagesGB * 1024 * 1024 * 1024) / (2 * 1024 * 1024);

                // Set huge pages count
                Path hugePagesPath = Paths.get("/proc/sys/vm/nr_hugepages");
                Files.write(hugePagesPath, String.valueOf(hugePagesCount).getBytes());

                // Verify allocation
                verifyHugePagesAllocation(hugePagesCount);

                // Mount hugetlbfs if not already mounted
                mountHugeTlbFs();

                System.out.println("Configured " + hugePagesCount + " huge pages (" + hugePagesGB + " GB)");

            } catch (Exception e) {
                throw new RuntimeException("Huge pages configuration failed", e);
            }
        }

        private static void verifyHugePagesAllocation(long requested) throws IOException {
            // Check /proc/meminfo for actual allocation
            String meminfo = Files.readString(Paths.get("/proc/meminfo"));

            Pattern pattern = Pattern.compile("HugePages_Total:\\s+(\\d+)");
            Matcher matcher = pattern.matcher(meminfo);

            if (matcher.find()) {
                long allocated = Long.parseLong(matcher.group(1));
                if (allocated < requested) {
                    System.err.println("Warning: Only " + allocated + " huge pages allocated, " +
                                     requested + " requested. Check available memory.");
                }
            }
        }

        private static void mountHugeTlbFs() {
            try {
                Path mountPoint = Paths.get("/mnt/hugepages");
                Files.createDirectories(mountPoint);

                ProcessBuilder pb = new ProcessBuilder("mount", "-t", "hugetlbfs",
                                                     "nodev", mountPoint.toString());
                Process process = pb.start();
                process.waitFor();

            } catch (Exception e) {
                System.err.println("Warning: Could not mount hugetlbfs: " + e.getMessage());
            }
        }
    }

    public static class SwapOptimizer {

        public static void optimizeForHFT() {
            try {
                // Set swappiness to minimum (but not 0 to avoid OOM)
                setSwappiness(1);

                // Disable swap completely for trading processes
                disableSwapForProcess();

                // Lock memory to prevent swapping
                lockProcessMemory();

            } catch (Exception e) {
                System.err.println("Warning: Swap optimization failed: " + e.getMessage());
            }
        }

        private static void setSwappiness(int value) throws IOException {
            Path swappinessPath = Paths.get("/proc/sys/vm/swappiness");
            Files.write(swappinessPath, String.valueOf(value).getBytes());

            System.out.println("Set swappiness to " + value);
        }

        private static void disableSwapForProcess() {
            // Use mlockall to lock all current and future allocations
            try {
                // This would typically be done via JNI call to mlockall(MCL_CURRENT | MCL_FUTURE)
                System.out.println("Memory locking would be applied via JNI mlockall()");
            } catch (Exception e) {
                System.err.println("Could not lock process memory: " + e.getMessage());
            }
        }

        private static void lockProcessMemory() {
            // Alternative: Use JVM flags to lock memory
            // -XX:+AlwaysPreTouch -XX:+UseLargePages
            System.out.println("Use JVM flags: -XX:+AlwaysPreTouch -XX:+UseLargePages");
        }
    }

    public static class NumaOptimizer {

        public static void configureNumaPolicy(int preferredNode) {
            try {
                // Set NUMA policy for current process
                ProcessBuilder pb = new ProcessBuilder("numactl", "--preferred=" + preferredNode,
                                                     "--pid=" + ProcessHandle.current().pid());
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    System.out.println("Set NUMA preferred node to " + preferredNode);
                } else {
                    System.err.println("Failed to set NUMA policy");
                }

            } catch (Exception e) {
                System.err.println("NUMA policy configuration failed: " + e.getMessage());
            }
        }

        public static void bindToNumaNode(int numaNode) {
            try {
                // Bind process to specific NUMA node
                ProcessBuilder pb = new ProcessBuilder("numactl", "--membind=" + numaNode,
                                                     "--cpunodebind=" + numaNode,
                                                     "--pid=" + ProcessHandle.current().pid());
                Process process = pb.start();
                process.waitFor();

                System.out.println("Bound process to NUMA node " + numaNode);

            } catch (Exception e) {
                System.err.println("NUMA binding failed: " + e.getMessage());
            }
        }
    }

    // Complete memory optimization for HFT
    public static void optimizeMemoryForHFT(int preferredNumaNode, long hugePagesGB) {
        System.out.println("Optimizing memory subsystem for HFT...");

        // Configure huge pages
        HugePagesManager.configureHugePages(hugePagesGB);

        // Optimize swap behavior
        SwapOptimizer.optimizeForHFT();

        // Configure NUMA
        NumaOptimizer.configureNumaPolicy(preferredNumaNode);

        // Additional optimizations
        try {
            // Disable transparent huge pages (can cause latency spikes)
            disableTransparentHugePages();

            // Configure memory allocation behavior
            configureMemoryAllocation();

        } catch (Exception e) {
            System.err.println("Additional memory optimizations failed: " + e.getMessage());
        }

        System.out.println("Memory optimization completed");
    }

    private static void disableTransparentHugePages() throws IOException {
        Path thpPath = Paths.get("/sys/kernel/mm/transparent_hugepage/enabled");
        if (Files.exists(thpPath)) {
            Files.write(thpPath, "never".getBytes());
            System.out.println("Disabled transparent huge pages");
        }

        Path defragPath = Paths.get("/sys/kernel/mm/transparent_hugepage/defrag");
        if (Files.exists(defragPath)) {
            Files.write(defragPath, "never".getBytes());
            System.out.println("Disabled transparent huge page defragmentation");
        }
    }

    private static void configureMemoryAllocation() throws IOException {
        // Configure overcommit behavior
        Path overcommitPath = Paths.get("/proc/sys/vm/overcommit_memory");
        Files.write(overcommitPath, "2".getBytes()); // Don't overcommit

        // Configure dirty page behavior for consistent latency
        Path dirtyRatioPath = Paths.get("/proc/sys/vm/dirty_ratio");
        Files.write(dirtyRatioPath, "5".getBytes()); // Trigger writeback earlier

        Path dirtyBackgroundRatioPath = Paths.get("/proc/sys/vm/dirty_background_ratio");
        Files.write(dirtyBackgroundRatioPath, "2".getBytes());

        System.out.println("Configured memory allocation behavior");
    }
}
```

---

## 🌐 Network & I/O Optimization

### 1. Network Stack Optimization

**Principle**: Optimize network stack for minimal latency and jitter.

```java
public class NetworkOptimizer {

    public static class KernelBypassManager {

        public static void configureKernelBypass(String interfaceName) {
            try {
                // Configure SR-IOV if available
                configureSRIOV(interfaceName);

                // Set up DPDK-style packet processing
                configureDPDK();

                // Optimize network buffers
                optimizeNetworkBuffers();

            } catch (Exception e) {
                System.err.println("Kernel bypass configuration failed: " + e.getMessage());
            }
        }

        private static void configureSRIOV(String iface) {
            // Enable SR-IOV virtual functions for hardware isolation
            try {
                Path sRIOVPath = Paths.get("/sys/class/net/" + iface + "/device/sriov_numvfs");
                if (Files.exists(sRIOVPath)) {
                    Files.write(sRIOVPath, "4".getBytes()); // Create 4 VFs
                    System.out.println("Enabled SR-IOV for " + iface);
                }
            } catch (IOException e) {
                System.err.println("SR-IOV configuration failed: " + e.getMessage());
            }
        }

        private static void configureDPDK() {
            // Reserve huge pages for DPDK
            System.out.println("DPDK configuration would require:");
            System.out.println("  - Huge pages reservation");
            System.out.println("  - UIO/VFIO driver binding");
            System.out.println("  - CPU isolation for DPDK cores");
        }

        private static void optimizeNetworkBuffers() throws IOException {
            // Increase network buffer sizes
            Map<String, String> bufferSettings = Map.of(
                "/proc/sys/net/core/rmem_max", "134217728",           // 128MB
                "/proc/sys/net/core/wmem_max", "134217728",           // 128MB
                "/proc/sys/net/core/rmem_default", "262144",          // 256KB
                "/proc/sys/net/core/wmem_default", "262144",          // 256KB
                "/proc/sys/net/core/netdev_max_backlog", "5000",      // Queue size
                "/proc/sys/net/core/netdev_budget", "600"             // NAPI budget
            );

            for (Map.Entry<String, String> setting : bufferSettings.entrySet()) {
                try {
                    Files.write(Paths.get(setting.getKey()), setting.getValue().getBytes());
                } catch (IOException e) {
                    System.err.println("Could not set " + setting.getKey() + ": " + e.getMessage());
                }
            }

            System.out.println("Optimized network buffer settings");
        }
    }

    public static class IRQAffinityManager {

        public static void optimizeNetworkIRQs(String interfaceName, Set<Integer> allowedCores) {
            try {
                // Find network device IRQs
                Set<Integer> networkIRQs = findNetworkIRQs(interfaceName);

                // Distribute IRQs across allowed cores
                distributeIRQs(networkIRQs, allowedCores);

                // Configure RPS/RFS for multi-queue processing
                configureRPS(interfaceName, allowedCores);

            } catch (Exception e) {
                System.err.println("Network IRQ optimization failed: " + e.getMessage());
            }
        }

        private static Set<Integer> findNetworkIRQs(String interfaceName) throws IOException {
            Set<Integer> irqs = new HashSet<>();

            // Read /proc/interrupts to find network device IRQs
            List<String> interruptLines = Files.readAllLines(Paths.get("/proc/interrupts"));

            for (String line : interruptLines) {
                if (line.contains(interfaceName)) {
                    // Extract IRQ number from first column
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) {
                        try {
                            int irq = Integer.parseInt(parts[0].replaceAll(":", ""));
                            irqs.add(irq);
                        } catch (NumberFormatException e) {
                            // Skip non-numeric IRQ entries
                        }
                    }
                }
            }

            return irqs;
        }

        private static void distributeIRQs(Set<Integer> irqs, Set<Integer> cores) {
            Iterator<Integer> coreIterator = cores.iterator();

            for (Integer irq : irqs) {
                if (!coreIterator.hasNext()) {
                    coreIterator = cores.iterator(); // Wrap around
                }

                int targetCore = coreIterator.next();
                setIRQAffinity(irq, targetCore);
            }
        }

        private static void setIRQAffinity(int irq, int core) {
            try {
                Path affinityPath = Paths.get("/proc/irq/" + irq + "/smp_affinity_list");
                if (Files.exists(affinityPath)) {
                    Files.write(affinityPath, String.valueOf(core).getBytes());
                    System.out.println("Set IRQ " + irq + " affinity to core " + core);
                }
            } catch (IOException e) {
                System.err.println("Could not set IRQ " + irq + " affinity: " + e.getMessage());
            }
        }

        private static void configureRPS(String interfaceName, Set<Integer> cores) {
            try {
                // Configure Receive Packet Steering
                String coreMask = createCoreMask(cores);

                Path queuesDir = Paths.get("/sys/class/net/" + interfaceName + "/queues");
                if (Files.exists(queuesDir)) {
                    Files.list(queuesDir)
                        .filter(path -> path.getFileName().toString().startsWith("rx-"))
                        .forEach(queuePath -> {
                            try {
                                Path rpsPath = queuePath.resolve("rps_cpus");
                                if (Files.exists(rpsPath)) {
                                    Files.write(rpsPath, coreMask.getBytes());
                                }
                            } catch (IOException e) {
                                System.err.println("Could not configure RPS for " + queuePath);
                            }
                        });
                }

                System.out.println("Configured RPS for " + interfaceName + " with mask " + coreMask);

            } catch (IOException e) {
                System.err.println("RPS configuration failed: " + e.getMessage());
            }
        }

        private static String createCoreMask(Set<Integer> cores) {
            int mask = 0;
            for (Integer core : cores) {
                mask |= (1 << core);
            }
            return String.format("%x", mask);
        }
    }

    // Complete network optimization for HFT
    public static void optimizeNetworkForHFT(String primaryInterface, Set<Integer> networkCores) {
        System.out.println("Optimizing network stack for HFT...");

        // Configure kernel bypass
        KernelBypassManager.configureKernelBypass(primaryInterface);

        // Optimize IRQ distribution
        IRQAffinityManager.optimizeNetworkIRQs(primaryInterface, networkCores);

        // Additional TCP optimizations
        optimizeTCPStack();

        System.out.println("Network optimization completed");
    }

    private static void optimizeTCPStack() {
        try {
            Map<String, String> tcpSettings = Map.of(
                "/proc/sys/net/ipv4/tcp_low_latency", "1",
                "/proc/sys/net/ipv4/tcp_nodelay", "1",
                "/proc/sys/net/core/busy_read", "50",
                "/proc/sys/net/core/busy_poll", "50"
            );

            for (Map.Entry<String, String> setting : tcpSettings.entrySet()) {
                try {
                    Files.write(Paths.get(setting.getKey()), setting.getValue().getBytes());
                } catch (IOException e) {
                    // Some settings may not be available on all kernels
                    System.err.println("Could not set " + setting.getKey());
                }
            }

            System.out.println("Optimized TCP stack settings");

        } catch (Exception e) {
            System.err.println("TCP optimization failed: " + e.getMessage());
        }
    }
}
```

---

## ⚖️ Latency vs Throughput Trade-offs

### 1. Trade-off Analysis Framework

**Principle**: Systematically analyze and optimize for the right balance based on business requirements.

```java
public class LatencyThroughputOptimizer {

    public enum OptimizationTarget {
        ULTRA_LOW_LATENCY,    // < 100ns, throughput secondary
        BALANCED,             // Balanced latency and throughput
        HIGH_THROUGHPUT       // Maximize throughput, latency secondary
    }

    public static class OptimizationStrategy {
        private final OptimizationTarget target;
        private final SystemConfiguration config;

        public OptimizationStrategy(OptimizationTarget target) {
            this.target = target;
            this.config = createOptimalConfiguration(target);
        }

        private SystemConfiguration createOptimalConfiguration(OptimizationTarget target) {
            switch (target) {
                case ULTRA_LOW_LATENCY:
                    return SystemConfiguration.builder()
                        .hyperthreading(false)              // Disable for consistency
                        .cpuGovernor(Governor.PERFORMANCE)   // Max frequency always
                        .coreIsolation(true)                // Isolate critical cores
                        .batchSize(1)                       // Process immediately
                        .bufferSize(64)                     // Small buffers
                        .memoryStrategy(MemoryStrategy.PREALLOCATED)
                        .garbageCollection(GCStrategy.MINIMAL)
                        .build();

                case HIGH_THROUGHPUT:
                    return SystemConfiguration.builder()
                        .hyperthreading(true)               // Use all logical cores
                        .cpuGovernor(Governor.ONDEMAND)     // Allow frequency scaling
                        .coreIsolation(false)               // Use all cores
                        .batchSize(1000)                    // Large batches
                        .bufferSize(65536)                  // Large buffers
                        .memoryStrategy(MemoryStrategy.DYNAMIC)
                        .garbageCollection(GCStrategy.THROUGHPUT)
                        .build();

                case BALANCED:
                default:
                    return SystemConfiguration.builder()
                        .hyperthreading(false)              // Disable for critical threads
                        .cpuGovernor(Governor.PERFORMANCE)   // Performance on critical cores
                        .coreIsolation(true)                // Isolate critical cores only
                        .batchSize(10)                      // Moderate batching
                        .bufferSize(1024)                   // Moderate buffers
                        .memoryStrategy(MemoryStrategy.POOLED)
                        .garbageCollection(GCStrategy.LOW_LATENCY)
                        .build();
            }
        }

        public void applyOptimizations(AffinityLibrary affinity) {
            // Apply system-level optimizations based on strategy
            SystemOptimizer.applyConfiguration(config, affinity);
        }
    }

    public static class PerformanceProfiler {
        private final LatencyHistogram latencyHistogram;
        private final ThroughputCounter throughputCounter;
        private final OptimizationTarget currentTarget;

        public PerformanceProfiler(OptimizationTarget target) {
            this.currentTarget = target;
            this.latencyHistogram = new LatencyHistogram();
            this.throughputCounter = new ThroughputCounter();
        }

        public void recordOperation(long startNanos, long endNanos) {
            long latencyNanos = endNanos - startNanos;
            latencyHistogram.recordValue(latencyNanos);
            throughputCounter.increment();
        }

        public PerformanceReport generateReport() {
            PerformanceMetrics metrics = PerformanceMetrics.builder()
                .averageLatency(latencyHistogram.getMean())
                .p50Latency(latencyHistogram.getValueAtPercentile(50))
                .p95Latency(latencyHistogram.getValueAtPercentile(95))
                .p99Latency(latencyHistogram.getValueAtPercentile(99))
                .p999Latency(latencyHistogram.getValueAtPercentile(99.9))
                .maxLatency(latencyHistogram.getMaxValue())
                .throughputPerSecond(throughputCounter.getThroughputPerSecond())
                .build();

            return new PerformanceReport(currentTarget, metrics, generateRecommendations(metrics));
        }

        private List<OptimizationRecommendation> generateRecommendations(PerformanceMetrics metrics) {
            List<OptimizationRecommendation> recommendations = new ArrayList<>();

            switch (currentTarget) {
                case ULTRA_LOW_LATENCY:
                    if (metrics.getP99Latency() > 100_000) { // 100µs
                        recommendations.add(new OptimizationRecommendation(
                            "P99 latency too high for ultra-low latency target",
                            "Consider: CPU isolation, disable hyperthreading, check for cache misses"
                        ));
                    }
                    break;

                case HIGH_THROUGHPUT:
                    if (metrics.getThroughputPerSecond() < getExpectedThroughput()) {
                        recommendations.add(new OptimizationRecommendation(
                            "Throughput below target",
                            "Consider: Enable hyperthreading, increase batch sizes, optimize memory allocation"
                        ));
                    }
                    break;

                case BALANCED:
                    // Check both latency and throughput
                    if (metrics.getP95Latency() > 1_000_000) { // 1ms
                        recommendations.add(new OptimizationRecommendation(
                            "P95 latency exceeds balanced target",
                            "Consider: Reduce batch sizes, improve cache locality"
                        ));
                    }
                    break;
            }

            return recommendations;
        }
    }

    // Adaptive optimization based on runtime metrics
    public static class AdaptiveOptimizer {
        private final PerformanceProfiler profiler;
        private final OptimizationStrategy strategy;
        private final AtomicReference<SystemConfiguration> currentConfig;

        public AdaptiveOptimizer(OptimizationTarget initialTarget) {
            this.profiler = new PerformanceProfiler(initialTarget);
            this.strategy = new OptimizationStrategy(initialTarget);
            this.currentConfig = new AtomicReference<>(strategy.config);
        }

        public void adaptBasedOnMetrics() {
            PerformanceReport report = profiler.generateReport();
            PerformanceMetrics metrics = report.getMetrics();

            // Analyze current performance vs targets
            if (shouldAdjustConfiguration(metrics)) {
                SystemConfiguration newConfig = generateOptimizedConfiguration(metrics);

                if (!newConfig.equals(currentConfig.get())) {
                    applyConfigurationChanges(newConfig);
                    currentConfig.set(newConfig);

                    System.out.println("Adaptive optimization applied:");
                    System.out.println("  New batch size: " + newConfig.getBatchSize());
                    System.out.println("  New buffer size: " + newConfig.getBufferSize());
                }
            }
        }

        private boolean shouldAdjustConfiguration(PerformanceMetrics metrics) {
            // Define thresholds for configuration changes
            return metrics.getP99Latency() > 500_000 ||  // > 500µs
                   metrics.getThroughputPerSecond() < 10_000; // < 10K ops/sec
        }

        private SystemConfiguration generateOptimizedConfiguration(PerformanceMetrics metrics) {
            SystemConfiguration current = currentConfig.get();
            SystemConfiguration.Builder builder = current.toBuilder();

            // Adjust batch size based on latency vs throughput trade-off
            if (metrics.getP99Latency() > 200_000 && current.getBatchSize() > 1) {
                // High latency - reduce batch size
                builder.batchSize(Math.max(1, current.getBatchSize() / 2));
            } else if (metrics.getThroughputPerSecond() < 5000 && current.getBatchSize() < 1000) {
                // Low throughput - increase batch size
                builder.batchSize(Math.min(1000, current.getBatchSize() * 2));
            }

            // Adjust buffer sizes based on memory pressure
            if (isMemoryPressureHigh()) {
                builder.bufferSize(Math.max(64, current.getBufferSize() / 2));
            }

            return builder.build();
        }

        private void applyConfigurationChanges(SystemConfiguration newConfig) {
            // Apply runtime configuration changes
            // Note: Some changes may require process restart

            System.out.println("Applying configuration changes...");
            // Implementation would adjust runtime parameters
        }
    }
}
```

---

## 📊 Performance Monitoring & Measurement

### 1. Comprehensive Performance Monitoring

**Principle**: Continuous monitoring of all performance-critical metrics to detect degradation early.

```java
public class HFTPerformanceMonitor {

    public static class SystemMetricsCollector {
        private final ScheduledExecutorService scheduler;
        private final Map<String, MetricCollector> collectors;

        public SystemMetricsCollector() {
            this.scheduler = Executors.newScheduledThreadPool(2);
            this.collectors = initializeCollectors();
        }

        private Map<String, MetricCollector> initializeCollectors() {
            Map<String, MetricCollector> collectors = new HashMap<>();

            collectors.put("cpu", new CPUMetricsCollector());
            collectors.put("memory", new MemoryMetricsCollector());
            collectors.put("network", new NetworkMetricsCollector());
            collectors.put("affinity", new AffinityMetricsCollector());
            collectors.put("gc", new GCMetricsCollector());

            return collectors;
        }

        public void startMonitoring() {
            // Collect system metrics every second
            scheduler.scheduleAtFixedRate(this::collectSystemMetrics, 0, 1, TimeUnit.SECONDS);

            // Collect application metrics every 100ms for HFT sensitivity
            scheduler.scheduleAtFixedRate(this::collectApplicationMetrics, 0, 100, TimeUnit.MILLISECONDS);
        }

        private void collectSystemMetrics() {
            SystemMetrics metrics = SystemMetrics.builder()
                .timestamp(System.nanoTime())
                .cpuUsage(collectors.get("cpu").collect())
                .memoryUsage(collectors.get("memory").collect())
                .networkStats(collectors.get("network").collect())
                .build();

            // Check for performance degradation
            analyzeMetrics(metrics);
        }

        private void collectApplicationMetrics() {
            ApplicationMetrics metrics = ApplicationMetrics.builder()
                .timestamp(System.nanoTime())
                .affinityStats(collectors.get("affinity").collect())
                .gcStats(collectors.get("gc").collect())
                .threadStats(collectThreadStats())
                .build();

            // Real-time alerting for critical issues
            checkCriticalThresholds(metrics);
        }

        private void analyzeMetrics(SystemMetrics metrics) {
            // CPU utilization analysis
            double cpuUsage = (Double) metrics.getCpuUsage();
            if (cpuUsage > 0.8) {
                System.err.println("WARNING: High CPU usage detected: " +
                                 String.format("%.1f%%", cpuUsage * 100));
            }

            // Memory pressure analysis
            MemoryStats memStats = (MemoryStats) metrics.getMemoryUsage();
            if (memStats.getUsagePercent() > 0.9) {
                System.err.println("WARNING: High memory usage: " +
                                 String.format("%.1f%%", memStats.getUsagePercent() * 100));
            }
        }

        private void checkCriticalThresholds(ApplicationMetrics metrics) {
            // GC pause detection
            GCStats gcStats = (GCStats) metrics.getGcStats();
            if (gcStats.getLastPauseDuration() > 1_000_000) { // > 1ms
                System.err.println("CRITICAL: GC pause exceeded 1ms: " +
                                 gcStats.getLastPauseDuration() + "ns");
            }

            // Thread affinity validation
            AffinityStats affinityStats = (AffinityStats) metrics.getAffinityStats();
            if (affinityStats.getUnboundThreadCount() > 0) {
                System.err.println("WARNING: " + affinityStats.getUnboundThreadCount() +
                                 " threads not bound to cores");
            }
        }
    }

    public static class LatencyTracker {
        private final HdrHistogram histogram;
        private final AtomicLong operationCount;
        private final String operationName;

        public LatencyTracker(String operationName) {
            this.operationName = operationName;
            this.histogram = new HdrHistogram(1, 10_000_000, 3); // 1ns to 10ms, 3 sig figs
            this.operationCount = new AtomicLong(0);
        }

        public void recordOperation(long startNanos, long endNanos) {
            long latencyNanos = endNanos - startNanos;
            histogram.recordValue(latencyNanos);
            operationCount.incrementAndGet();

            // Real-time alerting for outliers
            if (latencyNanos > 1_000_000) { // > 1ms
                System.err.println("LATENCY SPIKE: " + operationName + " took " +
                                 latencyNanos + "ns");
            }
        }

        public LatencyStats getStats() {
            return LatencyStats.builder()
                .operationName(operationName)
                .count(operationCount.get())
                .mean(histogram.getMean())
                .p50(histogram.getValueAtPercentile(50))
                .p95(histogram.getValueAtPercentile(95))
                .p99(histogram.getValueAtPercentile(99))
                .p999(histogram.getValueAtPercentile(99.9))
                .max(histogram.getMaxValue())
                .build();
        }

        public void reset() {
            histogram.reset();
            operationCount.set(0);
        }
    }

    // Comprehensive monitoring for HFT application
    public static class HFTApplicationMonitor {
        private final Map<String, LatencyTracker> latencyTrackers;
        private final SystemMetricsCollector systemMonitor;
        private final AlertManager alertManager;

        public HFTApplicationMonitor() {
            this.latencyTrackers = initializeLatencyTrackers();
            this.systemMonitor = new SystemMetricsCollector();
            this.alertManager = new AlertManager();
        }

        private Map<String, LatencyTracker> initializeLatencyTrackers() {
            Map<String, LatencyTracker> trackers = new HashMap<>();

            // Track critical HFT operations
            trackers.put("order_processing", new LatencyTracker("Order Processing"));
            trackers.put("market_data_decode", new LatencyTracker("Market Data Decode"));
            trackers.put("risk_check", new LatencyTracker("Risk Check"));
            trackers.put("position_update", new LatencyTracker("Position Update"));
            trackers.put("affinity_set", new LatencyTracker("Affinity Set"));

            return trackers;
        }

        public void startMonitoring() {
            systemMonitor.startMonitoring();

            // Generate performance reports every minute
            ScheduledExecutorService reportScheduler = Executors.newScheduledThreadPool(1);
            reportScheduler.scheduleAtFixedRate(this::generatePerformanceReport,
                                              60, 60, TimeUnit.SECONDS);
        }

        public void recordLatency(String operation, long startNanos, long endNanos) {
            LatencyTracker tracker = latencyTrackers.get(operation);
            if (tracker != null) {
                tracker.recordOperation(startNanos, endNanos);
            }
        }

        private void generatePerformanceReport() {
            System.out.println("\n=== HFT Performance Report ===");
            System.out.println("Timestamp: " + Instant.now());

            latencyTrackers.forEach((operation, tracker) -> {
                LatencyStats stats = tracker.getStats();
                if (stats.getCount() > 0) {
                    System.out.printf("%-20s: avg=%.1fns p99=%.1fns max=%.1fns count=%d%n",
                        operation,
                        stats.getMean(),
                        stats.getP99(),
                        stats.getMax(),
                        stats.getCount()
                    );
                }
            });

            System.out.println("===============================\n");

            // Reset counters for next period
            latencyTrackers.values().forEach(LatencyTracker::reset);
        }
    }
}
```

---

## ❌ Common Anti-Patterns

### 1. Performance Anti-Patterns to Avoid

**Principle**: Recognize and avoid common mistakes that destroy HFT performance.

```java
public class HFTAntiPatterns {

    // ANTI-PATTERN 1: Blocking Operations in Critical Path
    public static class BlockingOperationsAntiPattern {

        // BAD: Blocking I/O in trading thread
        public void processOrderBad(Order order) {
            validateOrder(order);           // OK - CPU bound

            // BAD: Blocking database call
            Position position = database.getPosition(order.getSymbol());

            // BAD: Blocking network call
            RiskResult risk = riskService.checkRisk(order);

            if (risk.isApproved()) {
                executeOrder(order);
            }
        }

        // GOOD: Async operations with pre-loaded data
        public void processOrderGood(Order order) {
            validateOrder(order);           // OK - CPU bound

            // GOOD: Use pre-loaded position cache
            Position position = positionCache.get(order.getSymbol());

            // GOOD: Use pre-calculated risk limits
            boolean riskOk = order.getQuantity() <= getRiskLimit(order.getSymbol());

            if (riskOk) {
                executeOrder(order);

                // GOOD: Async position update
                positionUpdateQueue.offer(new PositionUpdate(order));
            }
        }
    }

    // ANTI-PATTERN 2: Memory Allocation in Hot Path
    public static class MemoryAllocationAntiPattern {

        // BAD: Allocations in critical path
        public void processMarketDataBad(byte[] rawData) {
            // BAD: String creation triggers allocation
            String symbol = new String(rawData, 0, 8);

            // BAD: Boxing primitives
            Map<String, Double> prices = new HashMap<>();
            prices.put(symbol, Double.valueOf(parsePrice(rawData, 8)));

            // BAD: Creating objects in loop
            List<PriceLevel> levels = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                levels.add(new PriceLevel(parsePrice(rawData, 16 + i * 8)));
            }

            updateOrderBook(symbol, levels);
        }

        // GOOD: Pre-allocated objects and primitives
        public void processMarketDataGood(byte[] rawData) {
            // GOOD: Reuse pre-allocated buffer
            symbolBuffer.clear();
            symbolBuffer.put(rawData, 0, 8);

            // GOOD: Primitive types
            double price = parsePrice(rawData, 8);

            // GOOD: Reuse pre-allocated arrays
            levelCount = 0;
            for (int i = 0; i < 10 && levelCount < preallocatedLevels.length; i++) {
                preallocatedLevels[levelCount++] = parsePrice(rawData, 16 + i * 8);
            }

            updateOrderBookFast(symbolBuffer, price, preallocatedLevels, levelCount);
        }

        // Pre-allocated reusable objects
        private final ByteBuffer symbolBuffer = ByteBuffer.allocate(8);
        private final double[] preallocatedLevels = new double[20];
        private int levelCount = 0;
    }

    // ANTI-PATTERN 3: Synchronization in Critical Path
    public static class SynchronizationAntiPattern {

        // BAD: Synchronization causing contention
        private final Object lock = new Object();
        private volatile double lastPrice;

        public void updatePriceBad(double newPrice) {
            synchronized (lock) {              // BAD: Blocking synchronization
                if (newPrice > lastPrice) {
                    lastPrice = newPrice;
                    notifyPriceUpdate(newPrice);  // BAD: Potential blocking
                }
            }
        }

        // GOOD: Lock-free atomic operations
        private final AtomicReference<Double> atomicPrice = new AtomicReference<>(0.0);

        public void updatePriceGood(double newPrice) {
            // GOOD: Lock-free compare-and-swap
            double currentPrice;
            do {
                currentPrice = atomicPrice.get();
                if (newPrice <= currentPrice) return;
            } while (!atomicPrice.compareAndSet(currentPrice, newPrice));

            // GOOD: Non-blocking notification
            priceUpdateQueue.offer(newPrice);
        }
    }

    // ANTI-PATTERN 4: Improper Thread Affinity
    public static class AffinityAntiPattern {

        // BAD: No thread affinity management
        public void startTradingSystemBad() {
            Thread marketDataThread = new Thread(this::processMarketData);
            Thread orderThread = new Thread(this::processOrders);
            Thread riskThread = new Thread(this::processRisk);

            // BAD: Threads can migrate between cores
            marketDataThread.start();
            orderThread.start();
            riskThread.start();
        }

        // GOOD: Explicit thread affinity
        public void startTradingSystemGood(AffinityLibrary affinity) {
            // GOOD: Dedicate specific cores to specific functions
            startAffinityThread("MarketData", this::processMarketData, 2, affinity);
            startAffinityThread("OrderProcessing", this::processOrders, 4, affinity);
            startAffinityThread("RiskManagement", this::processRisk, 6, affinity);
        }

        private void startAffinityThread(String name, Runnable task, int coreId,
                                       AffinityLibrary affinity) {
            Thread thread = new Thread(() -> {
                // Bind thread to specific core
                BitSet cpuMask = new BitSet();
                cpuMask.set(coreId);
                affinity.setCurrentThreadAffinity(cpuMask);

                // Set real-time priority
                try {
                    Runtime.getRuntime().exec("chrt -f 95 -p " +
                                            ProcessHandle.current().pid());
                } catch (IOException e) {
                    System.err.println("Could not set RT priority: " + e.getMessage());
                }

                // Run the actual task
                task.run();
            });

            thread.setName(name + "-Core" + coreId);
            thread.start();
        }
    }

    // ANTI-PATTERN 5: Garbage Collection Issues
    public static class GarbageCollectionAntiPattern {

        // BAD: GC-unfriendly patterns
        public class OrderProcessorBad {
            public void processOrders(List<Order> orders) {
                // BAD: Stream API creates intermediate objects
                orders.stream()
                    .filter(order -> order.getPrice() > 100.0)    // Creates lambda objects
                    .map(order -> new ProcessedOrder(order))       // Creates new objects
                    .forEach(this::executeOrder);                 // More lambda objects
            }

            // BAD: StringBuilder in hot path
            public String formatOrder(Order order) {
                StringBuilder sb = new StringBuilder();           // Allocation
                sb.append("Order: ").append(order.getId())       // String concatenation
                  .append(" Price: ").append(order.getPrice())
                  .append(" Qty: ").append(order.getQuantity());
                return sb.toString();                            // More allocation
            }
        }

        // GOOD: GC-friendly patterns
        public class OrderProcessorGood {
            // Pre-allocated arrays for processing
            private final ProcessedOrder[] processedOrders = new ProcessedOrder[1000];
            private final ThreadLocal<StringBuilder> stringBuilder =
                ThreadLocal.withInitial(() -> new StringBuilder(256));

            public void processOrders(Order[] orders, int count) {
                // GOOD: Array iteration, no intermediate objects
                int processedCount = 0;
                for (int i = 0; i < count && processedCount < processedOrders.length; i++) {
                    Order order = orders[i];
                    if (order.getPrice() > 100.0) {
                        // GOOD: Reuse pre-allocated objects
                        processedOrders[processedCount].copyFrom(order);
                        executeOrder(processedOrders[processedCount]);
                        processedCount++;
                    }
                }
            }

            // GOOD: Reuse StringBuilder
            public void formatOrder(Order order, StringBuilder sb) {
                sb.setLength(0);  // Clear without allocation
                sb.append("Order: ").append(order.getId())
                  .append(" Price: ").append(order.getPrice())
                  .append(" Qty: ").append(order.getQuantity());
                // Caller uses sb.toString() only if needed
            }
        }
    }

    // ANTI-PATTERN 6: Cache Unfriendly Data Structures
    public static class CacheUnfriendlyAntiPattern {

        // BAD: Poor cache locality
        public class OrderBookBad {
            // BAD: Linked list = poor cache locality
            private final List<PriceLevel> bidLevels = new LinkedList<>();
            private final List<PriceLevel> askLevels = new LinkedList<>();

            public double getBestBid() {
                // BAD: Traverses linked list, poor cache usage
                return bidLevels.stream()
                    .mapToDouble(PriceLevel::getPrice)
                    .max()
                    .orElse(0.0);
            }
        }

        // GOOD: Cache-friendly data structures
        public class OrderBookGood {
            // GOOD: Array = excellent cache locality
            private final PriceLevel[] bidLevels = new PriceLevel[100];
            private final PriceLevel[] askLevels = new PriceLevel[100];
            private int bidCount = 0;
            private int askCount = 0;

            public double getBestBid() {
                // GOOD: Sequential array access
                double bestBid = 0.0;
                for (int i = 0; i < bidCount; i++) {
                    if (bidLevels[i].getPrice() > bestBid) {
                        bestBid = bidLevels[i].getPrice();
                    }
                }
                return bestBid;
            }

            // GOOD: Bulk operations for cache efficiency
            public void updateLevels(PriceLevel[] newBids, int newBidCount) {
                // GOOD: Array copy is cache-friendly
                System.arraycopy(newBids, 0, bidLevels, 0, newBidCount);
                this.bidCount = newBidCount;
            }
        }
    }
}
```

---

## 🎓 Summary

This optimization guide provides battle-tested strategies for achieving ultra-low latency in HFT systems. The key principles are:

1. **Thread Affinity**: Dedicate cores to specific functions
2. **Memory Management**: Pre-allocate and optimize for NUMA
3. **System Optimization**: Configure OS for consistent performance
4. **Network Optimization**: Minimize kernel overhead
5. **Trade-off Management**: Balance latency vs throughput based on requirements
6. **Continuous Monitoring**: Track performance metrics continuously
7. **Anti-pattern Avoidance**: Recognize and avoid common performance killers

## 🔗 Next Steps

- **💼 Apply techniques**: [HFT-EXAMPLES.md](HFT-EXAMPLES.md) - Real-world implementation examples
- **🏭 Production deployment**: [PRODUCTION-GUIDE.md](PRODUCTION-GUIDE.md) - Enterprise deployment guide
- **📖 Complete API reference**: [DOCUMENTATION.md](DOCUMENTATION.md) - Full library documentation

---

*This optimization guide ensures you have the practical strategies needed to build production-grade HFT systems with predictable sub-microsecond performance.*