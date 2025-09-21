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
