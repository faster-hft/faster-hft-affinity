---
layout: default
title: "HFT Developer Guide"
---

# HFT Developer Guide

## Table of Contents
1. [Overview](#overview)
2. [Performance Architecture](#performance-architecture)
3. [Hot-Path vs Standard APIs](#hot-path-vs-standard-apis)
4. [Latency Optimization](#latency-optimization)
5. [NUMA Optimization](#numa-optimization)
6. [Real-World Examples](#real-world-examples)
7. [Best Practices](#best-practices)
8. [Troubleshooting](#troubleshooting)

## Overview

The Faster Thread Affinity Library is specifically designed for high-frequency trading applications where microsecond and nanosecond latencies matter. This guide explains how to leverage the library's advanced features for maximum performance.

## Performance Architecture

### Two-Tier API Design

The library provides two distinct API layers optimized for different use cases:

#### 1. Configuration API (Standard)
- **Use Case**: Application startup, configuration, monitoring
- **Latency**: 50-500 microseconds
- **Features**: Full validation, comprehensive error handling, audit logging
- **Thread Safety**: Full concurrency support

#### 2. Hot-Path API (Ultra-Low Latency)
- **Use Case**: Trading critical path, order processing, market data handling
- **Latency**: < 100 nanoseconds (cached operations)
- **Features**: Lock-free operations, zero allocation, minimal overhead
- **Thread Safety**: Lock-free, wait-free algorithms

### Why This Design?

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

## Hot-Path vs Standard APIs

### Standard API - AffinityLibrary

**When to Use**: Configuration, setup, monitoring, non-critical operations

```java
AffinityLibrary library = AffinityLibraryFactory.create(config);

// Full featured operations with comprehensive error handling
OperationResult<BitSet> result = library.getCurrentThreadAffinity();
if (!result.isSuccess()) {
    handleError(result.getError());
}

// NUMA operations
OperationResult<BitSet> numaCpus = library.getNumaNodeCpus(0);
OperationResult<MemoryInfo> memory = library.getNumaNodeMemoryInfo(0);

// Performance monitoring
OperationResult<Double> utilization = library.getCoreUtilization(2);
```

### Hot-Path API - AffinityManager

**When to Use**: Trading loops, order processing, latency-critical operations

```java
AffinityManager hotPath = AffinityManager.getInstance();

// Lock-free, cache-optimized operations
@HotPath(expectedFrequency = 1000000, targetLatencyNs = 100)
OperationResult<BitSet> fastAffinity = hotPath.getCurrentThreadAffinityFast();

// Zero-allocation operations using object pools
try (PooledBitSet pooledMask = PooledBitSet.acquire()) {
    pooledMask.set(2);
    hotPath.setThreadAffinityFast(Thread.currentThread().getId(), pooledMask);
}
```

## Latency Optimization

### Understanding Latency Sources

1. **JNA Overhead**: ~1.3μs per native call
2. **System Call Overhead**: ~2μs for kernel transitions
3. **Java Runtime Overhead**: ~0.5μs for method calls
4. **Cache Misses**: Variable, 10-100ns

### Optimization Strategies

#### 1. Use Caching Aggressively

```java
AffinityConfig config = new AffinityConfig.Builder()
    .enableCaching(true)
    .enableThreadLocalCaching(true)
    .cacheExpiryMs(10000) // 10 second cache TTL
    .build();
```

#### 2. Minimize Native Calls

```java
// Bad: Multiple native calls
for (int i = 0; i < 1000; i++) {
    library.getCurrentThreadAffinity(); // 1000 native calls!
}

// Good: Cache and reuse
OperationResult<BitSet> cached = library.getCurrentThreadAffinity();
BitSet affinity = cached.getValue();
for (int i = 0; i < 1000; i++) {
    // Use cached value - zero native calls
    processWithAffinity(affinity);
}
```

#### 3. Use Object Pooling

```java
// Avoid allocation in hot path
try (PooledBitSet pooled = PooledBitSet.acquire()) {
    pooled.set(2, 6); // Set CPUs 2-5
    hotPath.setThreadAffinityFast(threadId, pooled);
    // Automatically returned to pool
}
```

#### 4. Prefetch Critical Data

```java
// Warm up caches during startup
for (int core = 0; core < numCores; core++) {
    library.getCoreUtilization(core); // Populate cache
}

// Hot path now uses cached data
double util = library.getCoreUtilization(2).getValue(); // Cache hit
```

## NUMA Optimization

### Topology-Aware Thread Placement

```java
// Discover NUMA topology
SystemTopology topology = library.getSystemTopology();
int numaNodes = topology.getNumaNodeCount();

// Pin trading threads to same NUMA node as network interface
int networkNuma = detectNetworkNumaNode(); // Your implementation
OperationResult<BitSet> tradingCpus = library.getNumaNodeCpus(networkNuma);

if (tradingCpus.isSuccess()) {
    // Create trading thread pool on optimal NUMA node
    ExecutorService tradingPool = createAffinityAwareExecutor(
        tradingCpus.getValue()
    );
}
```

### Memory-Aware Allocation

```java
// Allocate memory on same NUMA node as processing thread
int currentNuma = getCurrentNumaNode();
ByteBuffer orderBuffer = library.allocateNumaMemory(
    currentNuma,
    ORDER_BUFFER_SIZE
);

// Process orders with optimal memory locality
processOrdersWithBuffer(orderBuffer);
```

## Real-World Examples

### Order Processing System

```java
public class HFTOrderProcessor {
    private final AffinityLibrary config;
    private final AffinityManager hotPath;
    private final BitSet tradingCpus;

    public void initialize() {
        // Configuration phase - use standard API
        AffinityConfig config = new AffinityConfig.Builder()
            .enableCaching(true)
            .enableThreadLocalCaching(true)
            .enableNumaOperations(true)
            .build();

        this.config = AffinityLibraryFactory.create(config);
        this.hotPath = AffinityManager.getInstance();

        // Setup dedicated CPUs for trading
        this.tradingCpus = new BitSet();
        tradingCpus.set(2, 6); // CPUs 2-5
        config.setCurrentThreadAffinity(tradingCpus);

        // Warm up caches
        warmUpCaches();
    }

    @HotPath(targetLatencyNs = 500)
    public void processOrder(Order order) {
        // Verify we're still on correct CPU (cached lookup)
        OperationResult<BitSet> currentAffinity = hotPath.getCurrentThreadAffinityFast();

        if (!tradingCpus.equals(currentAffinity.getValue())) {
            // Emergency: re-pin thread (should be rare)
            hotPath.setCurrentThreadAffinityFast(Thread.currentThread().getId(), tradingCpus);
        }

        // Process order with guaranteed CPU isolation
        executeOrder(order);
    }
}
```

### Market Data Handler

```java
public class MarketDataHandler {
    private final BitSet dataCpus;
    private final PerformanceMonitor perfMon;

    public void setupDataPath() {
        // Isolate market data threads on dedicated cores
        dataCpus = new BitSet();
        dataCpus.set(0, 2); // CPUs 0-1 for market data

        AffinityLibrary library = AffinityLibraryFactory.getDefault();
        library.setCurrentThreadAffinity(dataCpus);

        // Setup performance monitoring
        perfMon = new PerformanceMonitor(library);
        perfMon.enableRealTimeTracking();
    }

    @HotPath(expectedFrequency = 100000, targetLatencyNs = 1000)
    public void handleMarketData(MarketData data) {
        // Minimal overhead data processing
        parseAndDistribute(data);

        // Optional: track performance (low overhead)
        perfMon.recordDataPoint();
    }
}
```

## Best Practices

### 1. Separate Configuration from Hot-Path

```java
// Good: Clear separation
class TradingEngine {
    // Configuration phase
    void initialize() {
        AffinityLibrary config = AffinityLibraryFactory.create(settings);
        setupThreadAffinity(config);
    }

    // Hot-path phase
    @HotPath
    void tradingLoop() {
        AffinityManager hotPath = AffinityManager.getInstance();
        // Ultra-fast operations only
    }
}
```

### 2. Monitor Performance in Production

```java
// Setup performance monitoring
PerformanceMonitor monitor = new PerformanceMonitor(library);
monitor.enableRealTimeTracking();

// Regular health checks (not in hot path)
CompletableFuture.runAsync(() -> {
    while (running) {
        PerformanceStats stats = monitor.getStats();
        if (stats.getAverageLatency() > TARGET_LATENCY) {
            alertLatencyViolation(stats);
        }
        Thread.sleep(1000);
    }
});
```

### 3. Handle Failures Gracefully

```java
@HotPath
public void criticalOperation() {
    OperationResult<BitSet> result = hotPath.getCurrentThreadAffinityFast();

    if (!result.isSuccess()) {
        // Fast failure path - don't break trading
        fallbackToDefaultAffinity();
        logWarning(result.getError());
        return;
    }

    // Continue with normal processing
    processWithOptimalAffinity(result.getValue());
}
```

### 4. Use Rate Limiting in Production

```java
AffinityConfig prodConfig = new AffinityConfig.Builder()
    .testMode(false) // Enable rate limiting
    .maxOperationsPerSecond(1000) // Reasonable limit
    .enableAuditLogging(true) // Track usage
    .build();
```

## Troubleshooting

### Common Performance Issues

#### 1. Unexpected High Latency

```java
// Check if caching is enabled
AffinityConfig config = library.getConfiguration();
if (!config.isCachingEnabled()) {
    // Problem: No caching, every call hits native code
    // Solution: Enable caching
}

// Check cache hit rates
CacheStats stats = library.getCacheStats();
if (stats.getHitRate() < 0.9) {
    // Problem: Low cache hit rate
    // Solution: Increase cache TTL or reduce operation frequency
}
```

#### 2. Thread Affinity Not Working

```java
// Verify current affinity
OperationResult<BitSet> current = library.getCurrentThreadAffinity();
OperationResult<BitSet> expected = getExpectedAffinity();

if (!current.getValue().equals(expected.getValue())) {
    // Check for permission issues
    if (current.getError() instanceof PermissionDeniedException) {
        // Solution: Run with elevated privileges or adjust security policy
    }

    // Check for OS scheduler interference
    SystemCapabilities caps = library.getSystemCapabilities();
    if (!caps.isRealtimeSupported()) {
        // Solution: Enable real-time scheduling or use process isolation
    }
}
```

#### 3. Memory Leaks

```java
// Monitor ThreadLocal usage
ThreadLocalStats tlStats = ThreadLocalManager.getStats();
if (tlStats.getActiveCount() > EXPECTED_COUNT) {
    // Problem: ThreadLocal instances not being cleaned up
    // Solution: Ensure proper cleanup in thread pools
    ThreadLocalManager.cleanupCurrentThread();
}

// Check object pool usage
ObjectPoolStats poolStats = ObjectPoolManager.getStats();
if (poolStats.getLeakedObjects() > 0) {
    // Problem: Pooled objects not being returned
    // Solution: Use try-with-resources for all pooled objects
}
```

---

**Author**: Amar Mond
**Date**: September 21, 2025
**Version**: 1.0.0