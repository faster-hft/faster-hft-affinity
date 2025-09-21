---
layout: default
title: "Faster Thread Affinity Library"
---

# Faster Thread Affinity Library

Ultra-low latency thread affinity library designed for high-frequency trading (HFT) applications.

## 🚀 Key Features

- **Ultra-Low Latency**: Nanosecond-precision thread affinity operations
- **Lock-Free Operations**: Zero-allocation hot-path APIs for maximum performance
- **NUMA Aware**: Intelligent memory and CPU topology management
- **Cross-Platform**: Native support for Linux and Windows
- **HFT Optimized**: Purpose-built for high-frequency trading requirements
- **Production Ready**: Comprehensive error handling, security, and monitoring

## 📊 Performance Highlights

- **Hot-Path Latency**: < 100ns for cached operations
- **Standard API**: < 500μs for configuration operations
- **Zero Allocation**: Lock-free operations with object pooling
- **Cache Optimization**: Thread-local caching with configurable TTL
- **Rate Limiting**: Token bucket algorithm for production safety

## 🎯 HFT Use Cases

### Trading Applications
- **Order Book Processing**: Pin critical threads to dedicated CPU cores
- **Market Data Handling**: Isolate data feed processing threads
- **Risk Management**: Ensure risk calculation threads have consistent performance

### Performance Critical Operations
- **Latency Reduction**: Eliminate OS scheduler interference
- **Jitter Minimization**: Consistent execution timing through CPU isolation
- **Cache Optimization**: NUMA-aware memory allocation and thread placement

## 🔧 Quick Start

### Basic Thread Affinity
```java
// Create high-performance configuration
AffinityConfig config = new AffinityConfig.Builder()
    .enableCaching(true)
    .enableThreadLocalCaching(true)
    .build();

AffinityLibrary library = AffinityLibraryFactory.create(config);

// Set thread to run on specific CPU cores
BitSet cpuMask = new BitSet();
cpuMask.set(0); // Use CPU core 0
library.setCurrentThreadAffinity(cpuMask);
```

### Hot-Path Operations (for HFT)
```java
// Direct access to lock-free operations
AffinityManager manager = AffinityManager.getInstance();

// Ultra-fast current thread affinity query
OperationResult<BitSet> result = manager.getCurrentThreadAffinityFast();
```

### NUMA-Aware Setup
```java
// Get optimal CPU cores for current NUMA node
OperationResult<BitSet> nodeCpus = library.getNumaNodeCpus(0);
if (nodeCpus.isSuccess()) {
    library.setCurrentThreadAffinity(nodeCpus.getValue());
}
```

## 📚 Documentation

- **[HFT Developer Guide](./hft-guide/)** - Comprehensive guide for trading applications
- **[API Documentation](./api/)** - Complete API reference with JavaDoc
- **[Performance Guide](./performance/)** - Optimization strategies and benchmarks
- **[Examples](./examples/)** - Real-world usage examples

## 🏗️ Architecture

### Core Components

1. **AffinityLibrary** - Main facade providing all functionality
2. **LockFreeAffinityOperations** - Hot-path optimized operations
3. **NUMAManager** - NUMA topology and memory management
4. **ThreadLocalManager** - Memory leak prevention and cleanup
5. **PerformanceMonitor** - Real-time performance metrics

### Platform Support

- **Linux**: Full feature support including CPU governor control
- **Windows**: Core affinity operations with performance monitoring
- **Cross-Platform**: Unified API with platform-specific optimizations

## 📈 Performance Benchmarks

| Operation | Latency (P99) | Throughput |
|-----------|---------------|------------|
| Hot-Path Affinity Query | < 100ns | > 10M ops/sec |
| Standard Affinity Set | < 500μs | > 1K ops/sec |
| NUMA Memory Allocation | < 1μs | > 100K ops/sec |
| System Topology Query | < 10μs | > 10K ops/sec |

## 🔐 Security Features

- **Input Validation**: Comprehensive parameter validation
- **Rate Limiting**: Token bucket protection against abuse
- **Audit Logging**: Security event tracking
- **Privilege Validation**: Safe elevation handling

## 🚀 Getting Started

1. **Add Dependency** - Include the library in your project
2. **Configure** - Set up for your specific HFT requirements
3. **Initialize** - Create and configure the affinity library
4. **Optimize** - Fine-tune for your hardware and workload

## 📞 Support

For HFT-specific optimizations and enterprise support, please refer to our comprehensive documentation and examples.

---

**Author**: Amar Mond
**Date**: September 21, 2025
**License**: Apache License 2.0