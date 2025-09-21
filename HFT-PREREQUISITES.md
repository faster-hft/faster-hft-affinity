# 🧠 HFT Prerequisites: Essential Knowledge for High-Frequency Trading Optimization

## 📋 Table of Contents

1. [Overview](#overview)
2. [CPU Architecture Fundamentals](#cpu-architecture-fundamentals)
3. [NUMA Architecture Deep Dive](#numa-architecture-deep-dive)
4. [Cache Hierarchy & Performance](#cache-hierarchy--performance)
5. [HFT-Specific Performance Concepts](#hft-specific-performance-concepts)
6. [Linux Scheduler & Real-Time Systems](#linux-scheduler--real-time-systems)
7. [Memory Management Fundamentals](#memory-management-fundamentals)
8. [Hardware Considerations](#hardware-considerations)
9. [Knowledge Validation](#knowledge-validation)

## 🎯 Overview

Before diving into CPU affinity optimization for HFT systems, developers need deep understanding of modern computer architecture. This guide provides the essential knowledge foundation that separates amateur optimizations from professional-grade HFT performance tuning.

**Target Audience**: HFT developers, performance engineers, and system architects working on microsecond-sensitive applications.

**Learning Objectives**:
- Understand CPU topology and its impact on thread scheduling
- Master NUMA architecture for optimal memory placement
- Recognize cache behavior patterns affecting HFT performance
- Identify system-level bottlenecks and optimization opportunities

---

## 🖥️ CPU Architecture Fundamentals

### Physical vs Logical Cores

Modern CPUs present a complex hierarchy that HFT developers must understand:

```
Physical CPU Package
├── Physical Core 0
│   ├── Logical Core 0 (HT0)
│   └── Logical Core 1 (HT1)
├── Physical Core 1
│   ├── Logical Core 2 (HT0)
│   └── Logical Core 3 (HT1)
└── ...
```

#### **Physical Cores**
- **Definition**: Independent CPU execution units with dedicated ALUs, FPUs, and L1/L2 caches
- **HFT Impact**: True parallel execution without resource contention
- **Identification**: `cat /proc/cpuinfo | grep "cpu cores"`

#### **Logical Cores (Hyperthreading/SMT)**
- **Definition**: Virtual cores that share physical core resources
- **HFT Considerations**:
  - ✅ **Benefit**: Can utilize idle execution units during memory waits
  - ❌ **Risk**: Resource contention can cause jitter and unpredictable latencies
  - 🎯 **HFT Recommendation**: Disable or carefully manage for critical threads

#### **Hyperthreading Decision Matrix**

| Workload Type | HT Recommendation | Reasoning |
|---------------|-------------------|-----------|
| **Critical Path** (order processing) | ❌ **Disable** | Eliminates resource contention and jitter |
| **Market Data** (high throughput) | ⚠️ **Consider** | May benefit from parallel decoding |
| **Background Tasks** (logging, monitoring) | ✅ **Enable** | Can utilize otherwise idle resources |
| **Risk Calculations** | ❌ **Disable** | Needs predictable latency |

### CPU Topology Detection

```bash
# View CPU topology
lscpu
cat /proc/cpuinfo
numactl --hardware

# Example output interpretation:
# Socket(s):             2        # Physical CPU packages
# Core(s) per socket:    8        # Physical cores per package
# Thread(s) per core:    2        # Logical cores per physical core
# NUMA node(s):          2        # NUMA domains
```

### Cache Hierarchy Impact

Modern CPUs have multi-level cache hierarchies critical for HFT performance:

```
CPU Core
├── L1 Cache (32KB, ~1ns latency)
│   ├── L1i (Instruction cache)
│   └── L1d (Data cache)
├── L2 Cache (256KB-1MB, ~3ns latency)
└── L3 Cache (8-32MB, ~12ns latency, shared)
    └── Main Memory (GB, ~100ns latency)
```

#### **Cache Characteristics by Level**

| Cache Level | Size | Latency | Scope | HFT Optimization |
|-------------|------|---------|-------|------------------|
| **L1** | 32-64KB | 1-2ns | Per core | Keep hot data structures small |
| **L2** | 256KB-1MB | 3-5ns | Per core | Critical algorithm working sets |
| **L3** | 8-32MB | 12-20ns | Shared across cores | Coordination data structures |
| **Main Memory** | GBs | 100-300ns | System-wide | Minimize access through caching |

### SMT/Hyperthreading Deep Dive

#### **Shared Resources in SMT**
```
Physical Core
├── Shared Resources:
│   ├── Execution Units (ALU, FPU, SIMD)
│   ├── L1 & L2 Caches
│   ├── Translation Lookaside Buffer (TLB)
│   └── Branch Predictor
└── Per-Thread Resources:
    ├── Architectural Registers
    ├── Program Counter
    └── Stack Pointer
```

#### **HFT SMT Considerations**

**Positive Impacts**:
- Can improve throughput for memory-bound workloads
- Utilizes execution units during cache misses
- May help with parallel market data processing

**Negative Impacts**:
- **Cache Pollution**: Other thread's data evicts critical data
- **Execution Unit Contention**: Competition for ALUs/FPUs
- **Branch Predictor Interference**: Reduced prediction accuracy
- **TLB Thrashing**: Page table cache conflicts

#### **SMT Management Strategies**

```bash
# Disable hyperthreading globally
echo off > /sys/devices/system/cpu/smt/control

# Disable specific logical cores
echo 0 > /sys/devices/system/cpu/cpu1/online  # Disable HT sibling

# Check SMT status
cat /sys/devices/system/cpu/smt/active
```

**HFT Best Practice**: Use physical core siblings strategically:
- **Core 0**: Critical trading thread
- **Core 1**: Leave offline (HT sibling of Core 0)
- **Core 2**: Market data processing
- **Core 3**: Leave offline (HT sibling of Core 2)

---

## 🏗️ NUMA Architecture Deep Dive

### NUMA Fundamentals

Non-Uniform Memory Access (NUMA) architecture is critical for modern multi-socket systems:

```
NUMA System Example (2-socket)
┌─────────────────────────────────────┐
│ Socket 0                            │
│ ┌─────────────┐  ┌───────────────┐  │
│ │  CPU 0-7    │  │ Local Memory  │  │
│ │             │◄─┤   (Node 0)    │  │
│ └─────────────┘  └───────────────┘  │
└─────────────────────┬───────────────┘
                      │ Interconnect (e.g., Intel QPI, AMD Infinity Fabric)
┌─────────────────────┴───────────────┐
│ Socket 1                            │
│ ┌─────────────┐  ┌───────────────┐  │
│ │  CPU 8-15   │  │ Local Memory  │  │
│ │             │◄─┤   (Node 1)    │  │
│ └─────────────┘  └───────────────┘  │
└─────────────────────────────────────┘
```

### Memory Access Patterns

#### **Local vs Remote Memory Access**

| Access Type | Latency | Bandwidth | Use Case |
|-------------|---------|-----------|----------|
| **Local** | ~80ns | Full | Critical data structures |
| **Remote** | ~140ns | Reduced | Background/shared data |
| **Cross-socket** | ~200ns | Limited | Coordination only |

#### **NUMA Penalties in HFT Context**

```
Example: Market Data Processing
┌─────────────────────────────────────┐
│ NUMA Node 0                         │
│ ├── Market Data Thread (CPU 0)      │
│ ├── Order Processing (CPU 1)        │
│ └── Local Memory: Price feeds       │ ✅ ~80ns access
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ NUMA Node 1                         │
│ ├── Risk Engine (CPU 8)             │
│ └── Remote Memory: Price feeds      │ ❌ ~140ns access (+75% latency!)
└─────────────────────────────────────┘
```

### NUMA Topology Detection

```bash
# Detailed NUMA information
numactl --hardware
numastat
lstopo  # from hwloc package

# Example output:
# available: 2 nodes (0-1)
# node 0 cpus: 0 1 2 3 4 5 6 7
# node 0 size: 32768 MB
# node 0 free: 25600 MB
# node distances:
# node   0   1
#   0:  10  21    # Local=10, Remote=21 (2.1x penalty)
#   1:  21  10
```

### Memory Controllers and Channels

#### **Memory Controller Architecture**
```
CPU Package
├── Memory Controller 0
│   ├── Channel 0 (DIMM 0, 1)
│   └── Channel 1 (DIMM 2, 3)
├── Memory Controller 1
│   ├── Channel 2 (DIMM 4, 5)
│   └── Channel 3 (DIMM 6, 7)
└── L3 Cache (shared)
```

#### **Memory Bandwidth Optimization**

Memory bandwidth is critical for HFT applications that process large volumes of market data. Understanding how to optimize memory subsystem configuration can provide significant performance benefits:

**Interleaving - Maximizing Parallel Access**:

Memory interleaving spreads consecutive memory addresses across multiple memory channels, allowing parallel access to different parts of your data:

```cpp
// Example: How interleaving affects HFT market data processing
struct MarketData {
    double price;     // Address 0x1000 -> Channel 0
    double volume;    // Address 0x1008 -> Channel 1
    double bid;       // Address 0x1010 -> Channel 2
    double ask;       // Address 0x1018 -> Channel 3
};

// With 4-channel interleaving, accessing all fields happens in parallel
// Without interleaving, all accesses would serialize on one channel
```

**Performance Impact**: Proper interleaving can increase effective bandwidth from ~25 GB/s (single channel) to ~100 GB/s (4-channel) for streaming workloads.

**Population - Filling All Channels**:

Memory controllers achieve maximum bandwidth only when all channels are populated with DIMMs:

```
Single Channel (Suboptimal):     Multi-Channel (Optimal):
┌─────────────────┐             ┌─────────────────┐
│ Memory Controller            │ Memory Controller│
├─────────────────┤             ├─────────────────┤
│ Channel 0: 32GB │             │ Channel 0: 32GB │
│ Channel 1: Empty│ 25 GB/s     │ Channel 1: 32GB │ 100 GB/s
│ Channel 2: Empty│             │ Channel 2: 32GB │
│ Channel 3: Empty│             │ Channel 3: 32GB │
└─────────────────┘             └─────────────────┘
```

**HFT Best Practice**: Always populate all available channels, even if you don't need the full capacity immediately. The bandwidth improvement is more valuable than the memory cost.

**Speed Matching - Avoiding Bottlenecks**:

Memory operates at the speed of the slowest DIMM in the system. Mixing different speeds creates performance bottlenecks:

```
Mixed Speed Configuration (Bad):
Channel 0: DDR4-3200 (25.6 GB/s potential)
Channel 1: DDR4-2400 (19.2 GB/s potential)
Actual Performance: DDR4-2400 across all channels (19.2 GB/s total)

Matched Speed Configuration (Good):
Channel 0: DDR4-3200 (25.6 GB/s)
Channel 1: DDR4-3200 (25.6 GB/s)
Actual Performance: DDR4-3200 across all channels (51.2 GB/s total)
```

**Capacity Balancing - Equal Distribution**:

Unbalanced channel capacities can cause performance issues and limit interleaving effectiveness:

```bash
# Suboptimal: Unbalanced channels
Channel 0: 32GB
Channel 1: 16GB  # Smaller capacity limits interleaving range
Channel 2: 32GB
Channel 3: 32GB

# Optimal: Balanced channels
Channel 0: 32GB
Channel 1: 32GB
Channel 2: 32GB
Channel 3: 32GB
```

**Real-World HFT Example**:
```cpp
// Market data processor with optimized memory layout
class OptimizedMarketDataProcessor {
    // Align to maximize memory bandwidth utilization
    alignas(64) struct alignas(64) SymbolData {
        double prices[8];     // 64 bytes = 1 cache line
        char padding[0];      // No padding needed, perfect fit
    };

    // Array designed for optimal channel utilization
    SymbolData* symbols_;  // Allocated to span all memory channels

public:
    void processMarketUpdate() {
        // Sequential access pattern optimized for interleaving
        for (int i = 0; i < symbol_count_; i++) {
            // Each symbol's data likely on different channel
            updatePrices(symbols_[i]);  // Parallel memory access
        }
    }
};
```

### NUMA Distance Matrix

The `numactl --hardware` distance matrix indicates relative access costs:

```
node distances:
node   0   1   2   3
  0:  10  16  32  33
  1:  16  10  25  32
  2:  32  25  10  16
  3:  33  32  16  10
```

**Interpretation**:
- **10**: Local access (baseline)
- **16**: Same socket, different memory controller
- **25-32**: Different socket, same chassis
- **33+**: Different chassis (NUMA-over-network)

### HFT NUMA Strategies

#### **1. Data Locality Strategy**
```java
// Allocate market data on same NUMA node as processing thread
NUMAManager numa = affinity.getNUMAManager();

// Pin thread to NUMA node 0
BitSet node0Cpus = numa.getNumaNodeCpus(0).getValue();
affinity.setCurrentThreadAffinity(node0Cpus);

// Allocate data structures on same node
ByteBuffer marketData = numa.allocateNuma(bufferSize, 0);
```

#### **2. Thread-to-Node Mapping**
```
NUMA-Aware HFT Architecture:
Node 0: Market Data + Order Processing (low latency path)
Node 1: Risk Management + Position Tracking
Node 2: Logging + Monitoring + Backup processes
Node 3: Database connections + External APIs
```

---

## 💾 Cache Hierarchy & Performance

### Cache Coherency and HFT

Cache coherency protocols (MESI, MOESI) can cause significant HFT performance issues:

#### **MESI States**
- **Modified (M)**: Cache line dirty, exclusively owned
- **Exclusive (E)**: Cache line clean, exclusively owned
- **Shared (S)**: Cache line clean, potentially shared
- **Invalid (I)**: Cache line invalid

#### **Cache Line Bouncing Example**
```cpp
// PROBLEMATIC: False sharing
struct TradingData {
    volatile long price;        // Cache line 0
    volatile long volume;       // Cache line 0 (same line!)
    volatile long timestamp;    // Cache line 0 (same line!)
};

// Thread 1 updates price -> Cache line to Modified
// Thread 2 updates volume -> Cache line invalidated on Thread 1
// Result: Constant cache line bouncing = high latency
```

#### **Cache-Friendly HFT Design**
```cpp
// OPTIMIZED: Separate cache lines
struct alignas(64) TradingData {  // 64-byte alignment
    volatile long price;          // Cache line 0
    char padding1[56];            // Pad to cache line boundary

    volatile long volume;         // Cache line 1
    char padding2[56];            // Pad to cache line boundary

    volatile long timestamp;      // Cache line 2
    char padding3[56];            // Pad to cache line boundary
};
```

### Cache Line Optimization

#### **Cache Line Sizes by Architecture**
| Architecture | Cache Line Size | Alignment Strategy |
|--------------|----------------|-------------------|
| **x86_64** | 64 bytes | Align hot data to 64-byte boundaries |
| **ARM64** | 64-128 bytes | Use 128-byte alignment for safety |
| **POWER** | 128 bytes | Align to 128-byte boundaries |

#### **False Sharing Detection**
```bash
# Use perf to detect cache misses
perf stat -e cache-misses,cache-references ./hft_app

# Intel VTune for detailed cache analysis
amplxe-cl -collect memory-access ./hft_app

# Look for high cache miss ratios (>5% is concerning for HFT)
```

### Memory Ordering and Barriers

#### **Memory Ordering Models**

**x86_64 (TSO - Total Store Order)**:

Intel and AMD x86_64 processors use Total Store Order, which provides relatively strong memory ordering guarantees:

- **Store Ordering**: All stores appear in the same order to all processors. If CPU A writes to memory location X then Y, all other CPUs will observe these writes in the same order.
- **Load-Store Reordering**: Loads can be reordered to occur before earlier stores, but only if they access different memory locations. This allows out-of-order execution while maintaining correctness.
- **Store Buffer**: Each CPU has a store buffer that can delay stores to memory. This means a store might not be immediately visible to other CPUs.

```cpp
// Example: What x86_64 TSO guarantees
CPU 0:              CPU 1:
store [A] = 1       while ([B] == 0) ;  // Wait for B to be set
store [B] = 1       load r1 = [A]       // Will always see A = 1
```

**HFT Implications**: TSO makes it relatively easy to write correct lock-free code, but you still need memory barriers for specific ordering requirements.

**ARM64 (Weak Ordering)**:

ARM64 processors use a much weaker memory model that allows extensive reordering for performance:

- **Extensive Reordering**: Both loads and stores can be reordered aggressively unless prevented by explicit barriers.
- **Speculative Execution**: The processor can execute loads speculatively and might see stale data that gets corrected later.
- **Conditional Memory Model**: The ordering depends on address dependencies and control dependencies.

```cpp
// Example: ARM64 reordering behavior
CPU 0:              CPU 1:
store [A] = 1       load r1 = [B]       // Might see B = 1
store [B] = 1       load r2 = [A]       // But A = 0 (reordered!)
                    // This is legal on ARM64 without barriers
```

**HFT Implications**: ARM64 can achieve higher performance due to aggressive reordering, but requires careful barrier placement. All shared data structures need explicit synchronization.

**POWER (Weak Ordering)**:

IBM POWER processors have the weakest memory model, allowing maximum performance through aggressive reordering:

- **Complete Reordering Freedom**: Loads and stores can be reordered freely unless constrained by dependencies or barriers.
- **Coherence vs Consistency**: Cache coherence (single location) is maintained, but consistency (multiple locations) requires explicit synchronization.
- **Performance Benefits**: This weakness allows for very high performance when properly used.

```cpp
// Example: POWER extreme reordering
Thread 1:           Thread 2:           Thread 3:
store [A] = 1       load r1 = [A]       load r3 = [B]
store [B] = 1       load r2 = [B]       load r4 = [A]
                    // r1=1, r2=0 possible    // r3=1, r4=0 possible
```

**HFT Implications**: POWER can achieve the highest performance for well-designed HFT systems, but requires expert-level understanding of memory ordering and extensive use of barriers.

#### **Memory Barriers for HFT**
```cpp
// Compiler barriers (prevent compiler reordering)
#define COMPILER_BARRIER() asm volatile("" ::: "memory")

// Memory barriers (prevent CPU reordering)
#ifdef __x86_64__
    #define MEMORY_BARRIER() asm volatile("mfence" ::: "memory")
    #define LOAD_BARRIER()   asm volatile("lfence" ::: "memory")
    #define STORE_BARRIER()  asm volatile("sfence" ::: "memory")
#elif __aarch64__
    #define MEMORY_BARRIER() asm volatile("dsb sy" ::: "memory")
    #define LOAD_BARRIER()   asm volatile("dsb ld" ::: "memory")
    #define STORE_BARRIER()  asm volatile("dsb st" ::: "memory")
#endif

// Usage in HFT critical sections
void publishPrice(volatile PriceUpdate* update) {
    update->price = newPrice;
    update->timestamp = getTimestamp();
    STORE_BARRIER();  // Ensure price/timestamp written before...
    update->valid = 1; // ...marking as valid
}
```

### Prefetching Strategies

#### **Hardware Prefetching**
Modern CPUs have stride prefetchers that detect patterns:

```cpp
// CPU detects sequential access pattern and prefetches
for (int i = 0; i < orders.size(); i++) {
    processOrder(orders[i]);  // Sequential access = automatic prefetch
}
```

#### **Software Prefetching**
```cpp
// Manual prefetching for irregular access patterns
void processOrders(Order* orders, int count) {
    for (int i = 0; i < count; i++) {
        // Prefetch next order while processing current
        if (i + 1 < count) {
            __builtin_prefetch(&orders[i + 1], 0, 3);  // Read, high temporal locality
        }

        processOrder(orders[i]);
    }
}
```

#### **Prefetch Strategies**
| Pattern | Strategy | HFT Use Case |
|---------|----------|--------------|
| **Sequential** | Let hardware handle | Market data arrays |
| **Strided** | Hardware + software hints | Order book levels |
| **Random** | Software prefetch | Hash table lookups |
| **Linked Lists** | Aggressive software prefetch | Order chains |

---

## 🔥 HFT-Specific Performance Concepts

### False Sharing Deep Dive

False sharing occurs when multiple threads access different variables that reside on the same cache line:

#### **False Sharing Example in HFT**
```cpp
// PROBLEMATIC: Multiple threads, same cache line
struct MarketData {
    volatile double bid;      // Thread 1 updates
    volatile double ask;      // Thread 2 updates
    volatile long volume;     // Thread 3 updates
    volatile long timestamp;  // Thread 4 updates
};  // All in same 64-byte cache line = false sharing
```

#### **Performance Impact Measurement**
```bash
# Before optimization
perf stat -e cache-misses,cache-references ./problematic_app
# cache-misses: 45,000,000 (15% miss rate = BAD)

# After optimization
perf stat -e cache-misses,cache-references ./optimized_app
# cache-misses: 2,000,000 (0.5% miss rate = GOOD)
```

#### **False Sharing Solutions**

**1. Cache Line Padding**
```cpp
struct alignas(64) OptimizedMarketData {
    volatile double bid;
    char padding1[64 - sizeof(double)];

    volatile double ask;
    char padding2[64 - sizeof(double)];

    volatile long volume;
    char padding3[64 - sizeof(long)];

    volatile long timestamp;
    char padding4[64 - sizeof(long)];
};
```

**2. Thread-Local Storage**
```cpp
// Each thread gets its own cache-aligned data
thread_local alignas(64) struct {
    double local_bid;
    double local_ask;
    long local_volume;
} market_data_cache;
```

**3. Data Structure Redesign**
```cpp
// Separate frequently updated data by access pattern
struct ReadOnlyData {     // Shared, rarely updated
    char symbol[16];
    double tick_size;
    int lot_size;
};

struct alignas(64) WriteHeavyData {  // Per-thread, frequently updated
    volatile double current_bid;
    volatile double current_ask;
    volatile long timestamp;
};
```

### Lock-Free Programming Concepts

#### **Compare-and-Swap (CAS) Operations**
```cpp
// Atomic compare and swap for lock-free data structures
bool compareAndSwap(volatile long* ptr, long expected, long desired) {
    return __sync_bool_compare_and_swap(ptr, expected, desired);
}

// Usage in lock-free queue
bool enqueue(LockFreeQueue* queue, Order* order) {
    while (true) {
        long tail = queue->tail;
        long next_tail = (tail + 1) % QUEUE_SIZE;

        if (next_tail == queue->head) return false; // Queue full

        if (compareAndSwap(&queue->tail, tail, next_tail)) {
            queue->data[tail] = order;
            return true;
        }
        // CAS failed, retry
    }
}
```

#### **Memory Ordering for Lock-Free Structures**
```cpp
// C++11 atomic with explicit memory ordering
std::atomic<Order*> head{nullptr};
std::atomic<Order*> tail{nullptr};

void push(Order* order) {
    order->next.store(nullptr, std::memory_order_relaxed);
    Order* prev_tail = tail.exchange(order, std::memory_order_acq_rel);
    if (prev_tail != nullptr) {
        prev_tail->next.store(order, std::memory_order_release);
    } else {
        head.store(order, std::memory_order_release);
    }
}
```

### Branch Prediction Optimization

#### **Branch Prediction Basics**
Modern CPUs predict branch outcomes to maintain instruction pipeline flow:

```cpp
// Predictable branch (good)
for (int i = 0; i < 1000; i++) {
    if (i < 500) {           // Highly predictable
        process_first_half();
    } else {
        process_second_half();
    }
}

// Unpredictable branch (bad for HFT)
for (int i = 0; i < orders.size(); i++) {
    if (orders[i].price > random_threshold) {  // Unpredictable
        process_order(orders[i]);
    }
}
```

#### **Branch Optimization Techniques**

**1. Branch Hints**
```cpp
#define LIKELY(x)   __builtin_expect(!!(x), 1)
#define UNLIKELY(x) __builtin_expect(!!(x), 0)

// Optimize common case
if (LIKELY(order.isValid())) {
    process_order(order);
} else {
    handle_invalid_order(order);  // Rare case
}
```

**2. Branchless Programming**
```cpp
// Instead of branches, use arithmetic
int max_without_branch(int a, int b) {
    return a > b ? a : b;  // May compile to conditional move
}

// Or bit manipulation
int max_branchless(int a, int b) {
    int diff = a - b;
    int mask = diff >> 31;  // -1 if a < b, 0 if a >= b
    return a - (diff & mask);
}
```

**3. Switch Statement Optimization**
```cpp
// Compiler can optimize dense switch statements to jump tables
void handle_message(MessageType type) {
    switch (type) {
        case MSG_BID:    process_bid(); break;      // Case 0
        case MSG_ASK:    process_ask(); break;      // Case 1
        case MSG_TRADE:  process_trade(); break;    // Case 2
        case MSG_CANCEL: process_cancel(); break;   // Case 3
        // Dense cases = jump table optimization
    }
}
```

### Instruction-Level Parallelism (ILP)

#### **Pipeline Optimization**
```cpp
// Poor ILP: Data dependencies
int sum = 0;
for (int i = 0; i < count; i++) {
    sum += data[i];  // Each iteration depends on previous
}

// Better ILP: Multiple accumulators
int sum1 = 0, sum2 = 0, sum3 = 0, sum4 = 0;
for (int i = 0; i < count; i += 4) {
    sum1 += data[i];     // Independent
    sum2 += data[i+1];   // Independent
    sum3 += data[i+2];   // Independent
    sum4 += data[i+3];   // Independent
}
int total = sum1 + sum2 + sum3 + sum4;
```

#### **SIMD Optimization**
```cpp
// Vectorize operations using SIMD
#include <immintrin.h>

void vectorized_price_update(float* prices, float adjustment, int count) {
    __m256 adj_vec = _mm256_set1_ps(adjustment);

    for (int i = 0; i < count; i += 8) {
        __m256 price_vec = _mm256_load_ps(&prices[i]);
        __m256 result = _mm256_add_ps(price_vec, adj_vec);
        _mm256_store_ps(&prices[i], result);
    }
}
```

---

## ⚙️ Linux Scheduler & Real-Time Systems

### Completely Fair Scheduler (CFS)

The default Linux scheduler optimizes for fairness, not latency:

#### **CFS Characteristics**
- **Goal**: Fair CPU time distribution
- **Time Slice**: Dynamic, based on load
- **Preemption**: Can happen at any time
- **HFT Impact**: Unpredictable latencies

#### **CFS Problems for HFT**
```bash
# Check scheduler policy
chrt -p $$
# Output: pid 1234's current scheduling policy: SCHED_OTHER
# SCHED_OTHER = CFS = BAD for HFT
```

### Real-Time Schedulers

#### **SCHED_FIFO (First-In-First-Out)**
- **Characteristics**: No time slicing, runs until yields or blocks
- **Priority**: 1-99 (higher = more priority)
- **Use Case**: Critical HFT threads

```bash
# Set FIFO scheduling
chrt -f 80 ./hft_trading_app
```

#### **SCHED_RR (Round-Robin)**
- **Characteristics**: Time-sliced real-time scheduling
- **Time Slice**: Configurable (typically 100ms)
- **Use Case**: Multiple real-time threads with same priority

#### **SCHED_DEADLINE**
- **Characteristics**: Guaranteed CPU time within deadlines
- **Parameters**: Runtime, deadline, period
- **Use Case**: Hard real-time requirements

```bash
# Set deadline scheduling (requires root)
chrt -d --sched-runtime 50ms --sched-deadline 100ms --sched-period 100ms ./hft_app
```

### CPU Isolation

#### **isolcpus Kernel Parameter**
```bash
# Boot parameter to isolate CPUs from scheduler
# /etc/default/grub
GRUB_CMDLINE_LINUX="isolcpus=2,3,4,5 nohz_full=2,3,4,5 rcu_nocbs=2,3,4,5"

# Apply changes
update-grub
reboot
```

#### **Manual CPU Isolation**
```bash
# Move all kernel threads off isolated CPUs
for i in $(ps -eo pid,cmd | grep '\[.*\]' | awk '{print $1}'); do
    taskset -cp 0,1 $i 2>/dev/null
done

# Move IRQs off isolated CPUs
echo 0,1 > /proc/irq/24/smp_affinity_list  # Network IRQ

# Verify isolation
cat /proc/interrupts | grep -E "(CPU2|CPU3|CPU4|CPU5)"
```

### Real-Time Configuration

#### **Real-Time Kernel Configuration**
```bash
# Check if real-time kernel is available
uname -r | grep rt
# If not, install: linux-image-rt-amd64

# Real-time specific configurations
echo -1 > /proc/sys/kernel/sched_rt_runtime_us  # Unlimited RT time
echo 950000 > /proc/sys/kernel/sched_rt_period_us
```

#### **Priority Assignment Strategy**
```
Priority Hierarchy for HFT:
99: Critical order processing
95: Market data ingestion
90: Risk management
85: Position tracking
80: Logging (real-time)
50: Background tasks
 1: Non-critical processes
```

### Context Switch Optimization

#### **Context Switch Measurement**
```bash
# Measure context switches
perf stat -e context-switches ./hft_app

# Detailed context switch analysis
perf record -e sched:sched_switch ./hft_app
perf script | head -20
```

#### **Minimizing Context Switches**
```cpp
// Use busy waiting instead of blocking for ultra-low latency
void wait_for_market_data() {
    while (!data_available) {
        _mm_pause();  // Hint to CPU we're spinning
        // Don't call sleep() or yield() - causes context switch
    }
}

// Use lock-free data structures to avoid kernel calls
lockfree_queue.push(order);  // No system calls
```

---

## 💾 Memory Management Fundamentals

### Virtual Memory and TLB

#### **Translation Lookaside Buffer (TLB)**
The TLB caches virtual-to-physical address translations:

```
Virtual Address Translation:
┌─────────────┐
│ Virtual Addr│ ──┐
└─────────────┘   │
                  ▼
┌─────────────┐   ┌─────────────┐
│     TLB     │   │ Page Tables │
│   (Cache)   │◄──┤ (In Memory) │
└─────────────┘   └─────────────┘
        │                 ▲
        ▼                 │ (TLB miss)
┌─────────────┐           │
│Physical Addr│───────────┘
└─────────────┘
```

#### **TLB Optimization for HFT**

**Large Pages (Hugepages)**:
```bash
# Configure hugepages
echo 1024 > /proc/sys/vm/nr_hugepages  # 1024 * 2MB = 2GB

# Mount hugepage filesystem
mkdir /mnt/hugepages
mount -t hugetlbfs nodev /mnt/hugepages

# Allocate huge pages in application
mmap(NULL, size, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_HUGETLB, -1, 0);
```

**Benefits of Large Pages**:

Large pages (also called huge pages or superpages) provide significant performance benefits for HFT applications by reducing memory management overhead:

**Reduces TLB Pressure (Translation Lookaside Buffer)**:

The TLB is a small cache that stores virtual-to-physical address translations. With standard 4KB pages, large memory allocations require many TLB entries:

```
Standard 4KB Pages:
1GB allocation = 262,144 pages = 262,144 TLB entries needed
Typical TLB size: 64-512 entries
Result: Constant TLB misses and page table walks

2MB Large Pages:
1GB allocation = 512 pages = 512 TLB entries needed
Result: All translations fit in TLB, no page table walks
```

**Performance Impact**: TLB misses can cost 100-300 CPU cycles. Eliminating them provides consistent low-latency memory access.

**Eliminates Page Table Walks**:

When the TLB misses, the CPU must walk the page table structure to find the physical address. This is expensive:

```cpp
// Example: Impact on HFT order processing
class OrderProcessor {
    Order* orders_;  // Large array allocated with huge pages

public:
    void processOrder(int index) {
        // With 4KB pages: potential TLB miss + page table walk (100+ cycles)
        // With 2MB pages: TLB hit (0 cycles overhead)
        Order& order = orders_[index];

        // Critical path processing benefits from zero translation overhead
        validateOrder(order);
        submitOrder(order);
    }
};
```

**Page Table Walk Cost**:
- L1 TLB miss: 10-20 cycles
- L2 TLB miss: 100-300 cycles
- Full page table walk: 200-500 cycles

**Guaranteed Physical Memory (No Swapping)**:

Large pages are typically not swappable, ensuring your critical HFT data stays in physical memory:

```bash
# Standard pages can be swapped to disk
echo 3 > /proc/sys/vm/drop_caches  # May cause paging

# Large pages remain in physical memory
cat /proc/meminfo | grep -i huge
HugePages_Total:    1024    # Always resident
HugePages_Free:      512    # Never swapped
```

**HFT Implications**:
- **Latency Predictability**: No surprise page faults from swap
- **Performance Consistency**: Memory access latency remains constant
- **Resource Guarantee**: Critical data always available in RAM

**Configuration Example**:
```bash
# Allocate 1GB of 2MB huge pages
echo 512 > /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages

# Verify allocation
cat /proc/meminfo | grep -i huge
HugePages_Total:     512
HugePages_Free:      512
HugePages_Rsvd:        0
HugePages_Surp:        0
Hugepagesize:       2048 kB
```

**Application Usage**:
```cpp
// Allocate memory using huge pages
void* allocateHugePages(size_t size) {
    void* ptr = mmap(nullptr, size,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_HUGETLB,
                     -1, 0);
    if (ptr == MAP_FAILED) {
        throw std::runtime_error("Failed to allocate huge pages");
    }
    return ptr;
}

// Example: Market data buffer with huge pages
class MarketDataBuffer {
    static constexpr size_t BUFFER_SIZE = 1024 * 1024 * 1024;  // 1GB
    void* buffer_;

public:
    MarketDataBuffer() {
        // Allocate with 2MB pages for optimal TLB utilization
        buffer_ = allocateHugePages(BUFFER_SIZE);
    }

    // Fast, predictable memory access
    void storeMarketData(const MarketUpdate& update, size_t offset) {
        // Zero TLB miss overhead due to huge pages
        memcpy(static_cast<char*>(buffer_) + offset, &update, sizeof(update));
    }
};
```

#### **Memory Layout Optimization**
```cpp
// Optimize memory layout for cache efficiency
struct alignas(64) OptimizedOrder {
    // Hot data (frequently accessed) - first cache line
    uint64_t order_id;
    double price;
    uint32_t quantity;
    uint32_t symbol_id;

    // Cold data (less frequently accessed) - separate cache lines
    char symbol[16];
    char trader_id[32];
    uint64_t timestamp;
    uint32_t flags;
};
```

### Memory Allocation Strategies

#### **Custom Allocators for HFT**

**1. Pool Allocator**
```cpp
template<typename T, size_t N>
class ObjectPool {
private:
    alignas(64) T pool[N];
    std::bitset<N> available;
    size_t next_index = 0;

public:
    T* allocate() {
        for (size_t i = 0; i < N; ++i) {
            size_t idx = (next_index + i) % N;
            if (available[idx]) {
                available[idx] = false;
                next_index = (idx + 1) % N;
                return &pool[idx];
            }
        }
        return nullptr;  // Pool exhausted
    }

    void deallocate(T* ptr) {
        size_t idx = ptr - pool;
        if (idx < N) {
            available[idx] = true;
        }
    }
};

// Usage
ObjectPool<Order, 10000> order_pool;
Order* order = order_pool.allocate();  // O(1) allocation
```

**2. Stack Allocator**
```cpp
class StackAllocator {
private:
    char* memory;
    size_t size;
    size_t offset;

public:
    StackAllocator(size_t sz) : size(sz), offset(0) {
        memory = static_cast<char*>(aligned_alloc(64, sz));
    }

    template<typename T>
    T* allocate(size_t count = 1) {
        size_t bytes = sizeof(T) * count;
        size_t aligned_bytes = (bytes + 63) & ~63;  // 64-byte align

        if (offset + aligned_bytes > size) return nullptr;

        T* result = reinterpret_cast<T*>(memory + offset);
        offset += aligned_bytes;
        return result;
    }

    void reset() { offset = 0; }  // Reset entire allocator
};
```

#### **NUMA-Aware Allocation**
```cpp
// Allocate memory on specific NUMA node
void* allocate_on_node(size_t size, int node) {
    void* ptr = numa_alloc_onnode(size, node);
    if (ptr == nullptr) {
        // Fallback to local allocation
        ptr = numa_alloc_local(size);
    }
    return ptr;
}

// Bind memory policy for automatic NUMA placement
numa_set_localalloc();  // Allocate on current CPU's node
```

### Memory Bandwidth Optimization

#### **Memory Access Patterns**

**Sequential Access (Cache-Friendly)**:
```cpp
// Good: Sequential memory access
for (int i = 0; i < size; i++) {
    process(data[i]);  // CPU prefetcher works well
}
```

**Random Access (Cache-Hostile)**:
```cpp
// Bad: Random memory access
for (int i = 0; i < size; i++) {
    int index = random_indices[i];
    process(data[index]);  // CPU prefetcher fails
}
```

#### **Memory Bandwidth Measurement**
```bash
# Measure memory bandwidth
perf stat -e memory-loads,memory-stores ./hft_app

# Stream benchmark for peak bandwidth
./stream  # Measures sustained memory bandwidth
```

---

## 🖥️ Hardware Considerations

### CPU Architecture Differences

#### **Intel vs AMD vs ARM Performance Characteristics**

Understanding the architectural differences between major CPU vendors is crucial for HFT system design. Each architecture has unique strengths that affect trading system performance:

| Feature | Intel Xeon | AMD EPYC | ARM (Graviton) |
|---------|------------|----------|----------------|
| **Memory Latency** | ~80ns | ~90ns | ~85ns |
| **Core Count** | 8-56 cores | 16-64 cores | 16-64 cores |
| **Memory Channels** | 6-8 channels | 8-12 channels | 8 channels |
| **Cache Coherency** | Ring/Mesh | Infinity Fabric | CMN mesh |
| **NUMA Scaling** | Good | Excellent | Good |
| **Single Thread** | Excellent | Very Good | Good |
| **Power Efficiency** | Moderate | Good | Excellent |

**Intel Xeon - Single Thread Performance Leader**:

Intel Xeon processors excel in single-threaded performance, making them ideal for latency-critical HFT applications:

- **Architecture Strengths**:
  - Highest IPC (Instructions Per Cycle) for single threads
  - Excellent branch prediction and speculative execution
  - Mature compiler optimizations and toolchain support
  - Advanced instruction sets (AVX-512) for vectorized operations

- **Memory Subsystem**:
  - Ring bus or mesh interconnect for cache coherency
  - Lower memory latency than AMD (typically 10-15ns advantage)
  - Predictable NUMA topology with clear node boundaries

- **HFT Implications**:
  - Best choice for single-threaded order processing engines
  - Optimal for low-latency market data processing
  - Premium pricing but justified for critical path applications

```cpp
// Example: Intel-optimized order processing
class IntelOptimizedOrderProcessor {
    // Leverage Intel's strong single-thread performance
    void processOrder(const Order& order) {
        // Intel's branch predictor excels with consistent patterns
        if (likely(order.isValid())) {  // Branch hint for Intel
            // Fast single-threaded execution path
            executeOrder(order);
        }
    }

    // Use Intel-specific vectorization
    void processBatchPrices(double* prices, int count) {
        #ifdef __AVX512F__
        for (int i = 0; i < count; i += 8) {
            __m512d v = _mm512_load_pd(&prices[i]);
            v = _mm512_mul_pd(v, _mm512_set1_pd(1.001));  // Apply spread
            _mm512_store_pd(&prices[i], v);
        }
        #endif
    }
};
```

**AMD EPYC - NUMA and Parallel Processing Champion**:

AMD EPYC processors provide excellent value for multi-threaded and NUMA-aware HFT applications:

- **Architecture Strengths**:
  - Superior NUMA scaling with Infinity Fabric
  - Higher core counts at competitive pricing
  - Excellent memory bandwidth with more channels
  - Strong floating-point performance for calculations

- **Infinity Fabric**:
  - Advanced cache coherency and memory management
  - Better scaling across multiple NUMA nodes
  - Lower inter-socket communication latency

- **HFT Implications**:
  - Excellent for risk management systems with many calculations
  - Ideal for multi-symbol market data processing
  - Cost-effective for non-critical path applications

```cpp
// Example: AMD EPYC NUMA-optimized design
class EPYCOptimizedRiskEngine {
    // Leverage AMD's excellent NUMA scaling
    void distributeRiskCalculations() {
        int numa_nodes = numa_max_node() + 1;

        // AMD EPYC excels with NUMA-aware parallel processing
        for (int node = 0; node < numa_nodes; node++) {
            // Bind calculation threads to specific NUMA nodes
            std::thread risk_thread([this, node]() {
                // AMD's Infinity Fabric provides efficient inter-node communication
                numa_run_on_node(node);
                calculateRiskForNode(node);
            });
            risk_thread.detach();
        }
    }

    // Take advantage of AMD's high core counts
    void parallelPortfolioAnalysis(const Portfolio& portfolio) {
        std::vector<std::future<RiskMetrics>> futures;

        // AMD EPYC can efficiently handle many parallel tasks
        for (const auto& symbol : portfolio.symbols) {
            futures.emplace_back(std::async(std::launch::async,
                [this, symbol]() { return calculateSymbolRisk(symbol); }));
        }
    }
};
```

**ARM (Graviton) - Power Efficiency and Cloud Optimization**:

ARM processors offer compelling power efficiency for cloud-based HFT infrastructure:

- **Architecture Strengths**:
  - Best power efficiency in the market
  - Competitive performance per dollar in cloud environments
  - Clean, simple instruction set architecture
  - Excellent for containerized microservices

- **Memory Model**:
  - Weak memory ordering requires careful barrier placement
  - Good NUMA scaling but simpler topology than x86
  - Predictable latency characteristics

- **HFT Implications**:
  - Ideal for cloud-based trading infrastructure
  - Excellent for monitoring and analytics systems
  - Requires expertise in weak memory ordering

```cpp
// Example: ARM-optimized design with proper memory barriers
class ARMOptimizedMarketData {
    volatile bool data_ready = false;
    MarketUpdate latest_update;

public:
    void publishUpdate(const MarketUpdate& update) {
        latest_update = update;

        // ARM requires explicit memory barriers
        #ifdef __aarch64__
        __asm__ __volatile__("dsb sy" ::: "memory");  // Data synchronization barrier
        #endif

        data_ready = true;

        #ifdef __aarch64__
        __asm__ __volatile__("dsb sy" ::: "memory");  // Ensure ordering
        #endif
    }

    bool consumeUpdate(MarketUpdate& update) {
        if (!data_ready) return false;

        #ifdef __aarch64__
        __asm__ __volatile__("dsb sy" ::: "memory");  // Load barrier
        #endif

        update = latest_update;
        return true;
    }
};
```

#### **Architecture-Specific Optimizations**

**Intel Optimizations**:
```cpp
// Intel-specific CPU features
#ifdef __INTEL_COMPILER
    #pragma vector aligned
    #pragma ivdep  // Ignore vector dependencies
#endif

// Use Intel intrinsics
#include <immintrin.h>
__m256d prices = _mm256_load_pd(price_array);
```

**AMD Optimizations**:
```cpp
// AMD EPYC has excellent NUMA scaling
// Prefer NUMA-aware algorithms
int current_node = numa_node_of_cpu(sched_getcpu());
numa_set_preferred(current_node);
```

**ARM Optimizations**:
```cpp
// ARM64 has weaker memory ordering
// Use appropriate barriers
#ifdef __aarch64__
    __asm__ __volatile__("dsb sy" ::: "memory");
#endif
```

### Memory Subsystem Architecture

#### **Memory Controller Configurations**

**Single-Socket Configuration**:
```
CPU
├── Memory Controller
│   ├── Channel 0: DIMM 0, 1
│   ├── Channel 1: DIMM 2, 3
│   ├── Channel 2: DIMM 4, 5
│   └── Channel 3: DIMM 6, 7
└── Total Bandwidth: ~200 GB/s
```

**Dual-Socket Configuration**:
```
Socket 0                    Socket 1
├── Local Memory: 128GB     ├── Local Memory: 128GB
├── Bandwidth: 200 GB/s     ├── Bandwidth: 200 GB/s
└── Remote Access: 100 GB/s └── Remote Access: 100 GB/s
    (to Socket 1)               (to Socket 0)
```

#### **Memory Configuration Best Practices**

**Population Rules**:
1. **Fill channels evenly**: Use same number of DIMMs per channel
2. **Match speeds**: All DIMMs should run at same frequency
3. **Avoid mixing**: Don't mix different capacities or vendors
4. **Maximum capacity**: Fill all channels for maximum bandwidth

**Example Optimal Configuration (384GB)**:
```
Channel 0: 32GB + 32GB = 64GB
Channel 1: 32GB + 32GB = 64GB
Channel 2: 32GB + 32GB = 64GB
Channel 3: 32GB + 32GB = 64GB
Channel 4: 32GB + 32GB = 64GB
Channel 5: 32GB + 32GB = 64GB
Total: 384GB, Full bandwidth utilization
```

### Network Interface Considerations

#### **NIC-to-CPU Affinity**
```bash
# Check NIC NUMA affinity
cat /sys/class/net/eth0/device/numa_node

# Set NIC interrupts to specific CPUs
echo 2 > /proc/irq/24/smp_affinity_list  # NIC IRQ to CPU 2

# For multi-queue NICs, spread queues across cores
for i in {0..3}; do
    echo $i > /sys/class/net/eth0/queues/rx-$i/rps_cpus
done
```

#### **DPDK Integration**
```cpp
// DPDK for kernel bypass networking
#include <rte_eal.h>
#include <rte_ethdev.h>

// Initialize DPDK
int ret = rte_eal_init(argc, argv);

// Bind NIC to specific NUMA node and CPU
struct rte_eth_conf port_conf = {
    .rxmode = {
        .mq_mode = ETH_MQ_RX_RSS,  // Multi-queue
    },
};

// Configure queue-to-core affinity
rte_eth_rx_queue_setup(port_id, queue_id, nb_rx_desc,
                       socket_id, &rx_conf, mp);
```

---

## ✅ Knowledge Validation

### Self-Assessment Questions

#### **CPU Architecture (Score: /25)**

1. **What is the difference between physical and logical cores, and when should HFT systems disable hyperthreading?** (5 points)

2. **Explain the cache hierarchy and calculate the performance impact of an L1 cache miss vs L3 cache miss.** (5 points)

3. **Design a CPU topology for a 4-socket system optimized for an HFT trading application.** (5 points)

4. **What are the trade-offs of SMT for different types of HFT workloads?** (5 points)

5. **How do you detect and prevent false sharing in multi-threaded HFT applications?** (5 points)

#### **NUMA Architecture (Score: /25)**

1. **Explain NUMA distance matrices and their impact on memory access latency.** (5 points)

2. **Design a NUMA-aware data structure for a market data feed processor.** (5 points)

3. **Calculate the performance penalty of remote memory access in a 2-socket system.** (5 points)

4. **Describe memory controller architecture and its impact on bandwidth.** (5 points)

5. **How do you optimize memory allocation for NUMA systems?** (5 points)

#### **Performance Optimization (Score: /25)**

1. **Design a lock-free data structure suitable for HFT order processing.** (5 points)

2. **Explain memory barriers and their necessity in weakly-ordered architectures.** (5 points)

3. **Optimize a branch-heavy algorithm for better instruction pipeline utilization.** (5 points)

4. **Design a custom memory allocator optimized for HFT latency requirements.** (5 points)

5. **Explain prefetching strategies and their application in HFT systems.** (5 points)

#### **System Configuration (Score: /25)**

1. **Configure Linux real-time scheduling for an HFT application.** (5 points)

2. **Design CPU isolation strategy for a multi-component HFT system.** (5 points)

3. **Optimize memory subsystem configuration for maximum bandwidth.** (5 points)

4. **Configure NUMA policies for optimal performance.** (5 points)

5. **Design interrupt affinity configuration for network-intensive HFT applications.** (5 points)

### Practical Exercises

#### **Exercise 1: False Sharing Detection**
```cpp
// Fix the false sharing in this code
struct TradingMetrics {
    volatile long orders_processed;    // Updated by thread 1
    volatile long trades_executed;     // Updated by thread 2
    volatile long risk_checks;         // Updated by thread 3
    volatile long latency_sum;         // Updated by thread 4
};

// Your optimized version:
// TODO: Redesign to eliminate false sharing
```

#### **Exercise 2: NUMA-Aware Design**
```cpp
// Design a NUMA-aware market data processor
class MarketDataProcessor {
public:
    void processOnNode(int numa_node, MarketData* data);
    void bindToNode(int numa_node);

    // TODO: Implement NUMA-aware processing
};
```

#### **Exercise 3: Cache-Friendly Algorithm**
```cpp
// Optimize this price calculation for cache efficiency
void calculatePrices(Order* orders, int count) {
    for (int i = 0; i < count; i++) {
        orders[i].calculated_price = orders[i].price * orders[i].multiplier;
        orders[i].fees = orders[i].calculated_price * 0.001;
        orders[i].total = orders[i].calculated_price + orders[i].fees;
    }
}

// Your optimized version:
// TODO: Improve cache utilization and add prefetching
```

### Scoring Guide

- **90-100**: Expert level - Ready for advanced HFT optimization
- **80-89**: Advanced level - Strong foundation, minor gaps to fill
- **70-79**: Intermediate level - Good understanding, needs more practice
- **60-69**: Beginner level - Basic concepts understood, significant study needed
- **<60**: Insufficient knowledge - Complete study of prerequisites required

---

## 🔗 Next Steps

After mastering these prerequisites:

1. **📖 Continue to**: [HFT-OPTIMIZATION-GUIDE.md](HFT-OPTIMIZATION-GUIDE.md) - Practical optimization strategies
2. **💼 Apply knowledge**: [HFT-EXAMPLES.md](HFT-EXAMPLES.md) - Real-world implementation examples
3. **🏭 Deploy systems**: [PRODUCTION-GUIDE.md](PRODUCTION-GUIDE.md) - Enterprise deployment guide
4. **🔧 Use the library**: [DOCUMENTATION.md](DOCUMENTATION.md) - Complete API reference

---

*This prerequisite guide ensures you have the foundational knowledge necessary for effective HFT system optimization. Master these concepts before proceeding to advanced optimization techniques.*