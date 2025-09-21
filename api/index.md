---
layout: default
title: "API Documentation"
---

# API Documentation

## Overview

The Faster Thread Affinity Library provides comprehensive APIs for ultra-low latency thread affinity management in high-frequency trading applications.

## Core Components

### AffinityLibrary (Main API)
The primary interface for all thread affinity operations.

- **Configuration Management**: Setup and configuration of affinity policies
- **Thread Operations**: Set and query thread affinity with full error handling
- **NUMA Operations**: NUMA-aware memory and thread management
- **Performance Monitoring**: Real-time performance metrics and statistics

### AffinityManager (Hot-Path API)
Lock-free, ultra-low latency operations for trading critical paths.

- **Fast Operations**: Nanosecond-precision affinity queries
- **Zero Allocation**: Lock-free operations with object pooling
- **Cache Optimized**: Thread-local caching for maximum performance

### Configuration (AffinityConfig)
Comprehensive configuration builder for optimal performance tuning.

- **Performance Tuning**: Cache settings, timeout configuration
- **Security Settings**: Rate limiting, audit logging
- **Platform Options**: NUMA support, performance counters

## JavaDoc Reference

The complete JavaDoc documentation is available at: [JavaDoc API Reference](./javadoc/)

## Quick Reference

### Basic Operations
```java
// Library initialization
AffinityLibrary library = AffinityLibraryFactory.create();

// Thread affinity
OperationResult<BitSet> current = library.getCurrentThreadAffinity();
OperationResult<Void> result = library.setCurrentThreadAffinity(cpuMask);

// System information
SystemCapabilities caps = library.getSystemCapabilities();
SystemTopology topology = library.getSystemTopology();
```

### Hot-Path Operations
```java
// High-frequency operations
AffinityManager manager = AffinityManager.getInstance();
OperationResult<BitSet> fast = manager.getCurrentThreadAffinityFast();
```

### NUMA Operations
```java
// NUMA-aware operations
OperationResult<BitSet> nodeCpus = library.getNumaNodeCpus(nodeId);
OperationResult<MemoryInfo> memory = library.getNumaNodeMemoryInfo(nodeId);
```

## Error Handling

All operations return `OperationResult<T>` for comprehensive error handling:

```java
OperationResult<BitSet> result = library.getCurrentThreadAffinity();
if (result.isSuccess()) {
    BitSet affinity = result.getValue();
    // Process successful result
} else {
    AffinityException error = result.getError();
    // Handle error appropriately
}
```

## Performance Annotations

The library uses performance annotations to indicate operation characteristics:

- `@HotPath` - Ultra-low latency operations (< 1μs)
- `@ColdPath` - Configuration operations (startup/shutdown)

---

**Note**: Complete JavaDoc documentation with detailed method signatures, parameters, and examples is available in the generated API reference.