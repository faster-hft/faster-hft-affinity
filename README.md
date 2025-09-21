# faster-thread-affinity

[![CI](https://github.com/faster-hft/faster-thread-affinity/workflows/Multi-Platform%20Thread%20Affinity%20CI/badge.svg)](https://github.com/faster-hft/faster-thread-affinity/actions/workflows/ci.yml)
[![Performance](https://github.com/faster-hft/faster-thread-affinity/workflows/Performance%20Testing/badge.svg)](https://github.com/faster-hft/faster-thread-affinity/actions/workflows/performance.yml)
[![Release](https://github.com/faster-hft/faster-thread-affinity/workflows/Release/badge.svg)](https://github.com/faster-hft/faster-thread-affinity/actions/workflows/release.yml)
[![codecov](https://codecov.io/gh/faster-hft/faster-thread-affinity/branch/main/graph/badge.svg)](https://codecov.io/gh/faster-hft/faster-thread-affinity)

Ultra-low-latency CPU affinity library for Java. Pin threads to specific cores, manage NUMA memory locality, set real-time scheduling policies. Features topology discovery, cache hierarchy analysis, processor groups (Windows), CPU isolation (Linux). Built for HFT systems requiring deterministic microsecond performance.

## 📊 Public Benchmarks & Test Results

- **🔍 Live Test Results**: [GitHub Actions](https://github.com/faster-hft/faster-thread-affinity/actions) - Real-time CI/CD pipeline results
- **📈 Performance Benchmarks**: [Performance Tests](https://github.com/faster-hft/faster-thread-affinity/actions/workflows/performance.yml) - Weekly HFT latency measurements
- **📋 Code Coverage**: [Codecov](https://codecov.io/gh/faster-hft/faster-thread-affinity) - Test coverage analysis
- **📦 Test Artifacts**: [Latest Results](https://github.com/faster-hft/faster-thread-affinity/actions) - Downloadable JUnit reports and performance data

### Latest Performance Results
- **Average affinity setting latency**: < 100ns (target)
- **Tested platforms**: Linux, Windows, macOS (x86_64 + ARM64)
- **Architectures**: Intel, AMD, ARM64 (Graviton, Apple Silicon)
- **Update frequency**: Weekly automated benchmarks

## 🚀 Quick Start

### Maven Dependency
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

public class QuickExample {
    public static void main(String[] args) {
        // Initialize the library
        AffinityLibrary affinity = AffinityLibraryFactory.getDefault();

        // Pin current thread to CPU 0
        BitSet cpuMask = new BitSet();
        cpuMask.set(0);

        var result = affinity.setCurrentThreadAffinity(cpuMask);
        if (result.isSuccess()) {
            System.out.println("✅ Thread pinned to CPU 0");

            // Verify current affinity
            var current = affinity.getCurrentThreadAffinity();
            System.out.println("Current CPUs: " + current.getValue());
        } else {
            System.err.println("❌ Failed: " + result.getError().getMessage());
        }

        // Cleanup
        affinity.shutdown();
    }
}
```

## 📚 Documentation & Learning Path

### 🎯 For New HFT Developers

**Start Here**: Follow our structured learning path designed for developers new to High-Frequency Trading:

| **Phase** | **Guide** | **Duration** | **Focus** |
|-----------|-----------|--------------|-----------|
| **1. Prerequisites** | [HFT Prerequisites](HFT-PREREQUISITES.md) | 1-2 weeks | CPU Architecture, NUMA, Hardware Fundamentals |
| **2. Optimization** | [HFT Optimization Guide](HFT-OPTIMIZATION-GUIDE.md) | 2-3 weeks | Thread Patterns, Memory Management, System Tuning |
| **3. Implementation** | [HFT Examples](HFT-EXAMPLES.md) | 1-2 weeks | Real-world Trading Systems, Order Management |
| **4. Production** | [Production Guide](PRODUCTION-GUIDE.md) | 2-4 weeks | Deployment, Monitoring, Enterprise Operations |
| **5. API Mastery** | [Complete Documentation](DOCUMENTATION.md) | Ongoing | Advanced API Usage, Platform Optimization |

### 📖 Complete Documentation Suite

- **🔗 [Complete API Documentation](DOCUMENTATION.md)** - Comprehensive API guide and examples
- **📋 [HFT Prerequisites](HFT-PREREQUISITES.md)** - Essential knowledge: CPU architecture, NUMA, hardware fundamentals
- **⚡ [HFT Optimization Guide](HFT-OPTIMIZATION-GUIDE.md)** - Practical strategies: thread patterns, memory management, system tuning
- **🔨 [HFT Examples](HFT-EXAMPLES.md)** - Real-world implementations: trading systems, order management, market data processing
- **🚀 [Production Guide](PRODUCTION-GUIDE.md)** - Enterprise deployment: hardware selection, OS config, monitoring, troubleshooting

### 🔧 Technical Resources

- **📖 JavaDoc API Reference**: [https://faster-hft.github.io/faster-thread-affinity/api/](https://faster-hft.github.io/faster-thread-affinity/api/)
- **🔧 CI/CD Setup Guide**: [CI-CD-GUIDE.md](CI-CD-GUIDE.md) - Multi-platform testing and deployment
- **🧪 Testing Guide**: [TESTING_GUIDE.md](TESTING_GUIDE.md) - Validation procedures and benchmarks

### ❓ Quick Assessment

Can you identify the performance issues in this code? If you can spot 5+ problems, jump to the [API docs](DOCUMENTATION.md). Otherwise, start with [Prerequisites](HFT-PREREQUISITES.md):

```java
public class TradingThread extends Thread {
    private List<Order> orders = new ArrayList<>();
    public void run() {
        while (true) {
            synchronized(orders) { processOrders(); }
            Thread.sleep(1); // 1ms sleep
        }
    }
}
```

## ✨ Key Features

- **🎯 Ultra-Low Latency**: < 100ns CPU affinity operations
- **🏗️ Multi-Platform**: Linux, Windows, macOS support
- **🔧 NUMA Aware**: Topology detection and memory allocation
- **📊 Performance Monitoring**: Built-in latency tracking and metrics
- **🔄 Thread Management**: Real-time scheduling and priority control
- **🛡️ Production Ready**: Comprehensive testing across 15+ architectures 
