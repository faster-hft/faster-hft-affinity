# 📖 HFT Thread Affinity Library - Complete Documentation

## 🎯 Table of Contents

1. [Quick Start](#quick-start)
2. [HFT Learning Path](#hft-learning-path)
3. [API Overview](#api-overview)
4. [Core Components](#core-components)
5. [Usage Examples](#usage-examples)
6. [Performance Tuning](#performance-tuning)
7. [Platform Support](#platform-support)
8. [Troubleshooting](#troubleshooting)
9. [Advanced Features](#advanced-features)
10. [Production Deployment](#production-deployment)
11. [Real-World Examples](#real-world-examples)

## 🚀 Quick Start

### Maven Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.hft.systems</groupId>
    <artifactId>affinity-library</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic Usage

```java
import com.faster.affinity.factory.AffinityLibraryFactory;
import com.faster.affinity.factory.AffinityLibrary;
import java.util.BitSet;

public class QuickStart {
    public static void main(String[] args) {
        // Initialize the library
        AffinityLibrary affinity = AffinityLibraryFactory.getDefault();

        // Pin current thread to CPU 0
        BitSet cpuMask = new BitSet();
        cpuMask.set(0);

        var result = affinity.setCurrentThreadAffinity(cpuMask);
        if (result.isSuccess()) {
            System.out.println("✅ Thread pinned to CPU 0");
        } else {
            System.err.println("❌ Failed: " + result.getError().getMessage());
        }

        // Cleanup
        affinity.shutdown();
    }
}
```

## 📚 HFT Learning Path

### For New HFT Developers

If you're new to High-Frequency Trading development, we recommend following this structured learning path:

#### 1. **Prerequisites** 📋
Start with the foundational concepts that are essential for HFT development:
- **[HFT Prerequisites Guide](HFT-PREREQUISITES.md)** - Essential knowledge covering:
  - CPU Architecture & Cache Hierarchy
  - NUMA Architecture & Memory Topology
  - Hardware Fundamentals for HFT
  - Operating System Concepts
  - Java Performance & GC Tuning

#### 2. **Practical Optimization** ⚡
Learn hands-on optimization techniques:
- **[HFT Optimization Guide](HFT-OPTIMIZATION-GUIDE.md)** - Practical strategies including:
  - Thread Design Patterns
  - Memory Management & NUMA Optimization
  - System-Level Tuning
  - JVM Performance Optimization
  - Latency Measurement & Analysis

#### 3. **Real-World Implementation** 🔨
Study production-ready examples:
- **[HFT Examples](HFT-EXAMPLES.md)** - Working implementations featuring:
  - Complete Trading System Architecture
  - Order Management Systems
  - Market Data Processing
  - Risk Management Systems
  - Performance Monitoring

#### 4. **Production Deployment** 🚀
Deploy and maintain HFT systems:
- **[Production Guide](PRODUCTION-GUIDE.md)** - Enterprise deployment covering:
  - Hardware Selection & Configuration
  - Operating System Optimization
  - Application Deployment
  - Monitoring & Alerting
  - Troubleshooting Playbooks

#### 5. **API Mastery** 🎯
Master this library's API (this document):
- Core Components & Interfaces
- Advanced Features & Customization
- Platform-Specific Optimizations

### Learning Timeline

| Phase | Duration | Focus |
|-------|----------|-------|
| **Prerequisites** | 1-2 weeks | Theory & Fundamentals |
| **Optimization** | 2-3 weeks | Hands-on Practice |
| **Examples** | 1-2 weeks | Code Implementation |
| **Production** | 2-4 weeks | Deployment & Operations |
| **API Mastery** | Ongoing | Advanced Usage |

### Quick Assessment

Before diving into implementation, assess your current knowledge:

```java
// Can you explain why this code might be problematic for HFT?
public class TradingThread extends Thread {
    private List<Order> orders = new ArrayList<>();

    public void run() {
        while (true) {
            synchronized(orders) {
                processOrders();
            }
            Thread.sleep(1); // 1ms sleep
        }
    }
}
```

If you can identify 5+ performance issues in the above code, you're ready to proceed with the API documentation. Otherwise, start with the [Prerequisites Guide](HFT-PREREQUISITES.md).

## 🔧 API Overview

### Core Interfaces

| Interface | Purpose | Key Methods |
|-----------|---------|-------------|
| `AffinityLibrary` | Main API entry point | `setCurrentThreadAffinity()`, `getCurrentThreadAffinity()` |
| `AffinityManager` | Core affinity operations | `setThreadAffinity()`, `getThreadAffinity()` |
| `NUMAManager` | NUMA topology management | `getNumaNodeCpus()`, `allocateNuma()` |
| `PerformanceMonitor` | Performance tracking | `measureLatency()`, `getMetrics()` |

### Factory Pattern

```java
// Get default implementation (auto-detects platform)
AffinityLibrary library = AffinityLibraryFactory.getDefault();

// Get platform-specific implementation
AffinityLibrary linuxLib = AffinityLibraryFactory.getLinuxImplementation();
AffinityLibrary windowsLib = AffinityLibraryFactory.getWindowsImplementation();
```

## 🏗️ Core Components

### 1. Affinity Manager

**Purpose**: Core CPU affinity operations

```java
AffinityManager manager = library.getAffinityManager();

// Set affinity for current thread
BitSet mask = new BitSet();
mask.set(0, 4); // CPUs 0-3
OperationResult<Void> result = manager.setCurrentThreadAffinity(mask);

// Set affinity for specific thread
long threadId = Thread.currentThread().getId();
manager.setThreadAffinity(threadId, mask);

// Get current affinity
OperationResult<BitSet> current = manager.getCurrentThreadAffinity();
if (current.isSuccess()) {
    BitSet currentMask = current.getValue();
    System.out.println("Current CPUs: " + currentMask);
}
```

### 2. NUMA Manager

**Purpose**: NUMA topology detection and memory allocation

```java
NUMAManager numa = library.getNUMAManager();

// Get NUMA topology
OperationResult<SystemTopology> topology = numa.getSystemTopology();
if (topology.isSuccess()) {
    SystemTopology topo = topology.getValue();
    System.out.println("NUMA nodes: " + topo.getNumaNodeCount());
    System.out.println("CPUs per node: " + topo.getCpusPerNode());
}

// Get CPUs for specific NUMA node
OperationResult<BitSet> node0Cpus = numa.getNumaNodeCpus(0);
if (node0Cpus.isSuccess()) {
    BitSet cpus = node0Cpus.getValue();
    System.out.println("NUMA node 0 CPUs: " + cpus);
}

// Allocate memory on specific NUMA node
OperationResult<ByteBuffer> buffer = numa.allocateNuma(1024 * 1024, 0); // 1MB on node 0
```

### 3. Performance Monitor

**Purpose**: Real-time performance tracking and latency measurement

```java
PerformanceMonitor monitor = library.getPerformanceMonitor();

// Measure affinity setting latency
long startTime = System.nanoTime();
manager.setCurrentThreadAffinity(mask);
long latency = System.nanoTime() - startTime;

monitor.recordLatency("affinity_set", latency);

// Get performance metrics
PerformanceMetrics metrics = monitor.getMetrics();
System.out.println("Average latency: " + metrics.getAverageLatency("affinity_set") + " ns");
System.out.println("P99 latency: " + metrics.getP99Latency("affinity_set") + " ns");
```

## 💡 Usage Examples

### Example 1: HFT Trading Thread Setup

```java
public class HFTTradingThread implements Runnable {
    private final AffinityLibrary affinity;
    private final int dedicatedCpu;

    public HFTTradingThread(int cpu) {
        this.affinity = AffinityLibraryFactory.getDefault();
        this.dedicatedCpu = cpu;
    }

    @Override
    public void run() {
        try {
            // Pin to dedicated CPU
            BitSet cpuMask = new BitSet();
            cpuMask.set(dedicatedCpu);

            var result = affinity.setCurrentThreadAffinity(cpuMask);
            if (!result.isSuccess()) {
                throw new RuntimeException("Failed to set CPU affinity: " +
                    result.getError().getMessage());
            }

            // Set high priority (platform-specific)
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

            // HFT trading loop
            while (!Thread.currentThread().isInterrupted()) {
                processMarketData();
                executeOrders();
                updatePositions();

                // Minimal sleep to yield CPU
                Thread.yield();
            }

        } finally {
            affinity.shutdown();
        }
    }

    private void processMarketData() { /* HFT logic */ }
    private void executeOrders() { /* Order execution */ }
    private void updatePositions() { /* Position management */ }
}

// Usage
Thread tradingThread = new Thread(new HFTTradingThread(2)); // Use CPU 2
tradingThread.start();
```

### Example 2: NUMA-Aware Data Processing

```java
public class NumaAwareProcessor {
    private final AffinityLibrary affinity;
    private final NUMAManager numa;

    public NumaAwareProcessor() {
        this.affinity = AffinityLibraryFactory.getDefault();
        this.numa = affinity.getNUMAManager();
    }

    public void processData(byte[] data) {
        // Get optimal NUMA node for current thread
        var topology = numa.getSystemTopology();
        if (!topology.isSuccess()) {
            throw new RuntimeException("NUMA not supported");
        }

        int numaNode = getCurrentNumaNode();

        // Allocate processing buffer on same NUMA node
        var buffer = numa.allocateNuma(data.length * 2, numaNode);
        if (!buffer.isSuccess()) {
            throw new RuntimeException("NUMA allocation failed");
        }

        ByteBuffer processingBuffer = buffer.getValue();

        try {
            // Set thread affinity to NUMA node CPUs
            var nodeCpus = numa.getNumaNodeCpus(numaNode);
            if (nodeCpus.isSuccess()) {
                affinity.setCurrentThreadAffinity(nodeCpus.getValue());
            }

            // Process data with optimal memory locality
            processDataInternal(data, processingBuffer);

        } finally {
            // Cleanup NUMA allocation
            numa.deallocateNuma(processingBuffer);
        }
    }

    private int getCurrentNumaNode() {
        // Implementation to detect current NUMA node
        return 0; // Simplified
    }

    private void processDataInternal(byte[] input, ByteBuffer output) {
        // High-performance data processing
    }
}
```

### Example 3: Performance Monitoring Setup

```java
public class PerformanceTracker {
    private final PerformanceMonitor monitor;
    private final ScheduledExecutorService scheduler;

    public PerformanceTracker(AffinityLibrary affinity) {
        this.monitor = affinity.getPerformanceMonitor();
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Start periodic performance reporting
        scheduler.scheduleAtFixedRate(this::reportMetrics, 1, 1, TimeUnit.SECONDS);
    }

    public void measureOperation(String operation, Runnable task) {
        long startTime = System.nanoTime();
        try {
            task.run();
        } finally {
            long latency = System.nanoTime() - startTime;
            monitor.recordLatency(operation, latency);
        }
    }

    private void reportMetrics() {
        PerformanceMetrics metrics = monitor.getMetrics();

        System.out.println("=== Performance Report ===");
        for (String operation : metrics.getOperations()) {
            System.out.printf("%s: avg=%.1f ns, p99=%.1f ns, count=%d%n",
                operation,
                metrics.getAverageLatency(operation),
                metrics.getP99Latency(operation),
                metrics.getOperationCount(operation)
            );
        }
        System.out.println("========================");
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}

// Usage
PerformanceTracker tracker = new PerformanceTracker(affinity);

// Measure affinity operations
tracker.measureOperation("set_affinity", () -> {
    BitSet mask = new BitSet();
    mask.set(1);
    affinity.setCurrentThreadAffinity(mask);
});
```

## ⚡ Performance Tuning

### 1. CPU Governor Settings

```bash
# Linux: Set CPU governor to performance
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# Disable CPU idle states for consistent latency
for i in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
    echo 1 | sudo tee $i 2>/dev/null
done
```

### 2. JVM Optimization

```bash
# Recommended JVM flags for HFT
java -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=1 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseTransparentHugePages \
     -XX:+AlwaysPreTouch \
     -XX:-UseBiasedLocking \
     -Xmx4g \
     -Xms4g \
     YourApplication
```

### 3. System Tuning

```bash
# Reduce swappiness
echo 1 | sudo tee /proc/sys/vm/swappiness

# Disable transparent huge pages defrag
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/defrag

# Set network buffer sizes
sysctl -w net.core.rmem_max=134217728
sysctl -w net.core.wmem_max=134217728
```

### 4. Library Configuration

```java
// Configure library for maximum performance
AffinityLibraryConfiguration config = new AffinityLibraryConfiguration()
    .enablePerformanceMode(true)
    .setLatencyTarget(100) // Target 100ns latency
    .enableNUMAOptimizations(true)
    .setMonitoringLevel(MonitoringLevel.MINIMAL);

AffinityLibrary affinity = AffinityLibraryFactory.create(config);
```

## 🖥️ Platform Support

### Linux

**Supported Features**:
- ✅ CPU affinity (sched_setaffinity)
- ✅ NUMA topology detection
- ✅ Real-time scheduling policies
- ✅ CPU isolation (isolcpus)
- ✅ IRQ affinity management

**Requirements**:
- Linux kernel 2.6.0+
- libc with sched_setaffinity support
- Root privileges for real-time scheduling

**Example**:
```java
// Linux-specific features
LinuxAffinityManager linux = (LinuxAffinityManager) affinity.getAffinityManager();

// Set real-time scheduling
linux.setSchedulingPolicy(SchedulingPolicy.SCHED_FIFO, 99);

// Set CPU isolation
linux.isolateCPUs(BitSet.valueOf(new long[]{0b1100})); // Isolate CPUs 2,3
```

### Windows

**Supported Features**:
- ✅ CPU affinity (SetThreadAffinityMask)
- ✅ Processor groups (>64 CPUs)
- ✅ NUMA topology detection
- ✅ High-priority threads
- ⚠️ Limited real-time capabilities

**Requirements**:
- Windows 7/Server 2008+
- Administrator privileges for high priority

**Example**:
```java
// Windows-specific features
WindowsAffinityManager windows = (WindowsAffinityManager) affinity.getAffinityManager();

// Set processor group affinity (for >64 CPU systems)
windows.setProcessorGroupAffinity(1, BitSet.valueOf(new long[]{0xFF})); // Group 1, CPUs 0-7

// Set high priority
windows.setThreadPriority(ThreadPriority.TIME_CRITICAL);
```

### macOS

**Supported Features**:
- ⚠️ Limited CPU affinity (thread_policy_set)
- ✅ Basic NUMA detection
- ⚠️ No real-time scheduling
- ✅ Performance monitoring

**Requirements**:
- macOS 10.10+
- Limited effectiveness due to OS restrictions

**Example**:
```java
// macOS has limited affinity support
MacOSAffinityManager macos = (MacOSAffinityManager) affinity.getAffinityManager();

// Best effort CPU hints
macos.setThreadAffinityHint(CPUHint.PERFORMANCE_CORES);
```

## 🚨 Troubleshooting

### Common Issues

#### 1. Permission Denied

**Problem**: `setCurrentThreadAffinity()` returns permission error

**Solutions**:
```java
// Check if running with sufficient privileges
if (!affinity.hasRequiredPrivileges()) {
    System.err.println("Insufficient privileges for CPU affinity");
    // Fallback to best-effort mode
    affinity.enableBestEffortMode(true);
}
```

#### 2. NUMA Not Detected

**Problem**: `getSystemTopology()` returns single node

**Solutions**:
```bash
# Linux: Check NUMA info
numactl --hardware

# Enable NUMA in BIOS if available
# Verify with:
cat /proc/cpuinfo | grep -i numa
```

#### 3. High Latency

**Problem**: Affinity operations taking >1µs

**Diagnostics**:
```java
// Enable detailed monitoring
monitor.enableDetailedProfiling(true);

// Check system load
SystemInfo info = affinity.getSystemInfo();
if (info.getCpuLoad() > 0.8) {
    System.out.println("High CPU load detected: " + info.getCpuLoad());
}

// Verify CPU governor
if (!info.isPerformanceGovernor()) {
    System.out.println("CPU governor not set to performance");
}
```

#### 4. Memory Allocation Failures

**Problem**: NUMA allocation fails

**Solutions**:
```java
// Check available memory per NUMA node
for (int node = 0; node < topology.getNumaNodeCount(); node++) {
    long available = numa.getAvailableMemory(node);
    System.out.println("NUMA node " + node + ": " + available + " bytes available");
}

// Use fallback allocation
if (numaBuffer.isFailure()) {
    ByteBuffer fallback = ByteBuffer.allocateDirect(size);
    System.out.println("Using fallback allocation");
}
```

## 🔐 Security Features

The library includes comprehensive security features designed for production HFT environments:

### Input Validation

All API calls undergo rigorous validation to prevent malicious or malformed input:

```java
// Comprehensive parameter validation
public class SecurityValidation {
    public static void validateCpuMask(BitSet cpuMask) {
        if (cpuMask == null) {
            throw new SecurityException("CPU mask cannot be null");
        }

        if (cpuMask.cardinality() > MAX_CPU_COUNT) {
            throw new SecurityException("CPU mask exceeds system limits");
        }

        // Validate against system capabilities
        SystemCapabilities caps = getSystemCapabilities();
        if (!caps.isValidCpuMask(cpuMask)) {
            throw new SecurityException("Invalid CPU mask for current system");
        }
    }
}
```

### Rate Limiting

Token bucket algorithm protects against API abuse and system overload:

```java
AffinityConfig secureConfig = new AffinityConfig.Builder()
    .testMode(false) // Enable rate limiting in production
    .maxOperationsPerSecond(1000) // Reasonable limit
    .enableRateLimiting(true)
    .tokenBucketCapacity(100)
    .build();

// Rate limiting is automatically enforced
try {
    library.setCurrentThreadAffinity(cpuMask);
} catch (RateLimitExceededException e) {
    // Handle rate limit violation
    handleRateLimit(e);
}
```

### Audit Logging

Security event tracking for compliance and monitoring:

```java
AffinityConfig auditConfig = new AffinityConfig.Builder()
    .enableAuditLogging(true)
    .auditLogLevel(AuditLevel.FULL)
    .auditDestination("/var/log/affinity-audit.log")
    .build();

// All operations are automatically logged
library.setCurrentThreadAffinity(cpuMask);
// Logs: [2025-09-21 16:30:45] USER:admin OPERATION:setAffinity CPU_MASK:0-3 RESULT:SUCCESS
```

### Privilege Validation

Safe handling of elevated privileges with automatic validation:

```java
// Check privileges before attempting operations
if (!library.hasRequiredPrivileges()) {
    throw new SecurityException("Insufficient privileges for CPU affinity operations");
}

// Safe privilege elevation when needed
try {
    library.requestElevatedPrivileges();
    // Perform privileged operations
    library.setRealtimePriority(RealtimePriority.HIGH);
} finally {
    library.dropElevatedPrivileges();
}
```

### Security Monitoring

Real-time security event monitoring and alerting:

```java
SecurityMonitor secMonitor = library.getSecurityMonitor();

// Register security event handlers
secMonitor.onSecurityViolation(event -> {
    if (event.getSeverity() == SecuritySeverity.HIGH) {
        alertSecurityTeam(event);
        // Temporarily disable operations if needed
        library.enableSafeMode();
    }
});

// Monitor for suspicious patterns
secMonitor.enableAnomalyDetection();
```

## 🔬 Advanced Features

### 1. Custom Affinity Strategies

```java
// Implement custom affinity strategy
public class RoundRobinAffinityStrategy implements AffinityStrategy {
    private final AtomicInteger nextCpu = new AtomicInteger(0);
    private final int cpuCount;

    public RoundRobinAffinityStrategy(int cpuCount) {
        this.cpuCount = cpuCount;
    }

    @Override
    public BitSet getAffinityMask(Thread thread) {
        int cpu = nextCpu.getAndIncrement() % cpuCount;
        BitSet mask = new BitSet();
        mask.set(cpu);
        return mask;
    }
}

// Use custom strategy
affinity.setAffinityStrategy(new RoundRobinAffinityStrategy(4));
```

### 2. Interrupt Handling

```java
// Linux: Manage IRQ affinity
IRQManager irqManager = affinity.getIRQManager();

// Move network IRQs to specific CPUs
BitSet irqCpus = new BitSet();
irqCpus.set(0, 2); // CPUs 0-1 for IRQs

irqManager.setNetworkIRQAffinity(irqCpus);

// Isolate application CPUs from IRQs
BitSet appCpus = new BitSet();
appCpus.set(2, 8); // CPUs 2-7 for application

irqManager.isolateCPUsFromIRQs(appCpus);
```

### 3. CPU Frequency Management

```java
// Linux: Control CPU frequency
CPUGovernorManager governor = affinity.getCPUGovernorManager();

// Set specific CPUs to performance mode
BitSet performanceCpus = new BitSet();
performanceCpus.set(2, 6); // CPUs 2-5

governor.setGovernor(performanceCpus, CPUGovernor.PERFORMANCE);

// Set frequency limits
governor.setFrequencyRange(performanceCpus, 2400000, 3200000); // 2.4-3.2 GHz
```

## 📊 Performance Benchmarks

### Production Performance Targets

| Operation | Latency (P99) | Throughput | Use Case |
|-----------|---------------|------------|----------|
| Hot-Path Affinity Query | < 100ns | > 10M ops/sec | Trading loops, order processing |
| Standard Affinity Set | < 500μs | > 1K ops/sec | Configuration, setup operations |
| NUMA Memory Allocation | < 1μs | > 100K ops/sec | Buffer allocation, data structures |
| System Topology Query | < 10μs | > 10K ops/sec | Initialization, monitoring |
| Cache Hit Operations | < 50ns | > 20M ops/sec | Cached affinity queries |
| Context Switch Time | < 2μs | N/A | OS scheduler overhead |

### Built-in Benchmarks

```java
// Run comprehensive benchmarks
BenchmarkSuite benchmarks = affinity.getBenchmarkSuite();

BenchmarkResults results = benchmarks.runAll();

System.out.println("=== Benchmark Results ===");
System.out.println("Affinity set latency: " + results.getAffinityLatency() + " ns");
System.out.println("NUMA allocation: " + results.getNumaLatency() + " ns");
System.out.println("Context switch time: " + results.getContextSwitchTime() + " ns");
```

### Hot-Path vs Standard API Performance

#### Two-Tier API Design

The library provides two distinct API layers optimized for different performance requirements:

**Configuration API (Standard)**
- **Use Case**: Application startup, configuration, monitoring
- **Latency**: 50-500 microseconds
- **Features**: Full validation, comprehensive error handling, audit logging
- **Thread Safety**: Full concurrency support

**Hot-Path API (Ultra-Low Latency)**
- **Use Case**: Trading critical path, order processing, market data handling
- **Latency**: < 100 nanoseconds (cached operations)
- **Features**: Lock-free operations, zero allocation, minimal overhead
- **Thread Safety**: Lock-free, wait-free algorithms

```java
// Configuration Phase (Startup) - Use Standard API
AffinityConfig config = new AffinityConfig.Builder()
    .enableCaching(true)
    .enableThreadLocalCaching(true)
    .testMode(false)
    .build();

AffinityLibrary library = AffinityLibraryFactory.create(config);

// Setup phase - higher latency acceptable
BitSet tradingCpus = new BitSet();
tradingCpus.set(2, 6); // CPUs 2-5 for trading threads
library.setCurrentThreadAffinity(tradingCpus);

// Hot-Path Phase (Trading) - Use Hot-Path API
AffinityManager hotPath = AffinityManager.getInstance();

// Ultra-fast operations in trading loop
while (marketOpen) {
    // < 100ns latency for cached queries
    OperationResult<BitSet> affinity = hotPath.getCurrentThreadAffinityFast();

    // Process order...
    processOrder(order);
}
```

### @HotPath Annotations

Use the `@HotPath` annotation to mark performance-critical methods:

```java
public class HFTOrderProcessor {
    @HotPath(expectedFrequency = 1000000, targetLatencyNs = 100)
    public void processOrder(Order order) {
        // Ultra-low latency order processing
        AffinityManager hotPath = AffinityManager.getInstance();
        OperationResult<BitSet> affinity = hotPath.getCurrentThreadAffinityFast();

        // Process with guaranteed CPU isolation
        executeOrder(order);
    }

    @HotPath(targetLatencyNs = 500)
    public void handleMarketData(MarketData data) {
        // Minimal overhead data processing
        parseAndDistribute(data);
    }
}
```

### Object Pooling for Zero-Allocation

Use object pooling to eliminate allocations in hot paths:

```java
// Zero-allocation operations using object pools
try (PooledBitSet pooledMask = PooledBitSet.acquire()) {
    pooledMask.set(2, 6); // Set CPUs 2-5
    hotPath.setThreadAffinityFast(Thread.currentThread().getId(), pooledMask);
    // Automatically returned to pool
}

// Check pool statistics
ObjectPoolStats poolStats = ObjectPoolManager.getStats();
System.out.println("Pool efficiency: " + poolStats.getHitRate());
System.out.println("Leaked objects: " + poolStats.getLeakedObjects());
```

### Custom Benchmarks

```java
// Benchmark specific operations
public class CustomBenchmark {
    public void benchmarkAffinitySwitch() {
        BitSet cpu0 = new BitSet(); cpu0.set(0);
        BitSet cpu1 = new BitSet(); cpu1.set(1);

        long iterations = 100_000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            affinity.setCurrentThreadAffinity(i % 2 == 0 ? cpu0 : cpu1);
        }

        long totalTime = System.nanoTime() - startTime;
        double avgLatency = (double) totalTime / iterations;

        System.out.println("Average affinity switch: " + avgLatency + " ns");
    }
}
```

## 🚀 Production Deployment

For comprehensive production deployment guidance, see **[Production Guide](PRODUCTION-GUIDE.md)** which covers:

### Hardware Requirements
- **CPU Selection**: Intel vs AMD vs ARM64 for HFT workloads
- **Memory Configuration**: DDR4/DDR5, ECC, NUMA topology
- **Network Hardware**: 10/25/100GbE NICs, kernel bypass
- **Storage**: NVMe, RAID configuration, persistence strategies

### Operating System Configuration
- **Linux Optimization**: Real-time kernels, CPU isolation, IRQ tuning
- **Windows Configuration**: High-performance timer resolution, process priorities
- **Network Stack Tuning**: TCP bypass, DPDK integration

### Application Deployment
- **JVM Configuration**: GC tuning, memory management, compiler optimizations
- **Container Strategies**: Docker, Kubernetes, bare-metal considerations
- **Service Mesh**: Low-latency networking, service discovery

### Monitoring & Alerting
- **Performance Metrics**: Latency percentiles, throughput monitoring
- **System Health**: CPU utilization, memory pressure, network saturation
- **Application Monitoring**: Thread affinity validation, NUMA compliance

## 🔨 Real-World Examples

Explore production-ready implementations in **[HFT Examples](HFT-EXAMPLES.md)**:

### Complete Trading Systems
```java
// High-level example from the examples guide
public class LatencyOptimizedTradingSystem {
    private final AffinityLibrary affinity;
    private final LockFreeOrderBook orderBook;
    private final RingBuffer<MarketDataEvent> marketDataRing;

    // Ultra-low latency order processing pipeline
    // See HFT-EXAMPLES.md for complete implementation
}
```

### Specialized Components
- **Market Data Processors**: Lock-free, NUMA-optimized data ingestion
- **Order Management Systems**: Sub-microsecond order routing
- **Risk Management**: Real-time position monitoring and limits
- **Latency Monitoring**: Continuous performance validation

### Architecture Patterns
- **Single-threaded Event Loops**: Eliminating context switches
- **Lock-free Data Structures**: Avoiding synchronization overhead
- **Memory Pool Management**: Reducing GC pressure
- **NUMA-aware Algorithms**: Optimizing memory locality

---

## 🔗 Additional Resources

### Documentation Suite
- **[HFT Prerequisites](HFT-PREREQUISITES.md)** - Foundational knowledge for HFT development
- **[HFT Optimization Guide](HFT-OPTIMIZATION-GUIDE.md)** - Practical performance optimization strategies
- **[HFT Examples](HFT-EXAMPLES.md)** - Real-world implementation examples
- **[Production Guide](PRODUCTION-GUIDE.md)** - Enterprise deployment and operations

### External Resources
- **JavaDoc API**: [https://faster-hft.github.io/faster-thread-affinity/api/](https://faster-hft.github.io/faster-thread-affinity/api/)
- **GitHub Repository**: [https://github.com/faster-hft/faster-thread-affinity](https://github.com/faster-hft/faster-thread-affinity)
- **Performance Benchmarks**: [https://github.com/faster-hft/faster-thread-affinity/actions/workflows/performance.yml](https://github.com/faster-hft/faster-thread-affinity/actions/workflows/performance.yml)
- **CI/CD Guide**: [CI-CD-GUIDE.md](CI-CD-GUIDE.md)
- **Testing Guide**: [TESTING_GUIDE.md](TESTING_GUIDE.md)

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/faster-hft/faster-thread-affinity/issues)
- **Discussions**: [GitHub Discussions](https://github.com/faster-hft/faster-thread-affinity/discussions)
- **Email**: dev@faster-hft.com

---

*This documentation is automatically updated with each release. Last updated: $(date)*