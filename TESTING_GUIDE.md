# HFT Thread Affinity - Testing Guide

## Overview
This guide covers the comprehensive testing infrastructure for the HFT Thread Affinity library, including cross-platform testing, performance validation, and automated deployment.

## IntelliJ IDEA Run Configurations

### Available Configurations

1. **Quick Unit Tests** - Fast unit tests for core components
   - Tests: AffinityManager, CPUGovernorManager, NUMAManager, PerformanceMonitor, TopologyDetector
   - Runtime: ~30 seconds
   - Use case: Development cycle testing

2. **Unit Tests Only** - Complete unit test suite
   - Tests: All unit tests across all packages
   - Runtime: ~2 minutes
   - Use case: Pre-commit validation

3. **All Tests - Windows** - Complete test suite for Windows platform
   - Tests: Unit + Integration + Platform-specific tests
   - Runtime: ~5 minutes
   - Use case: Full Windows validation

4. **Integration Tests - Windows** - Windows integration tests only
   - Tests: Cross-platform compatibility, real hardware interaction
   - Runtime: ~3 minutes
   - Use case: Hardware-specific testing

5. **HFT Performance Tests** - Performance regression testing
   - Tests: Latency validation (&lt;50μs P99), throughput benchmarks
   - Runtime: ~10 minutes
   - Use case: Performance validation before releases

6. **External Test Harness** - Comprehensive test orchestration
   - Tests: Full feature matrix, compatibility testing, report generation
   - Runtime: ~15 minutes
   - Use case: Release validation

7. **Maven Build and Deploy WSL** - Cross-platform build and deployment
   - Action: Build JAR + Deploy to WSL Ubuntu
   - Runtime: ~3 minutes
   - Use case: Cross-platform development

8. **Maven Test All Profiles** - Maven-based complete testing
   - Tests: All profiles with coverage reporting
   - Runtime: ~8 minutes
   - Use case: CI/CD pipeline simulation

## Maven Profiles

### Core Profiles
- `test-all` - Runs all test categories
- `wsl-deploy` - Builds and deploys to WSL Ubuntu
- `hft-quality` - Enforces HFT-specific quality gates

### Usage Examples

```bash
# Quick development testing
mvn test -Dtest=AffinityManagerTest

# Full Windows testing
mvn test -P test-all

# Build and deploy to WSL
mvn clean package assembly:single -P wsl-deploy

# Performance validation
mvn test -P hft-quality -Dtest=HFTPerformanceRegressionTest

# Complete quality gate check
mvn clean test jacoco:report -P test-all,hft-quality
```

## Cross-Platform Testing

### Windows Testing
1. Run any IntelliJ configuration directly
2. Use Maven profiles: `mvn test -P test-all`
3. Performance testing with Windows-specific optimizations

### WSL Ubuntu Testing
1. Build and deploy: Use "Maven Build and Deploy WSL" configuration
2. SSH to WSL: `wsl`
3. Navigate: `cd /home/amar/affinity-testing`
4. Run: `java -jar faster-thread-affinity-*-jar-with-dependencies.jar`

### Automated Cross-Platform Workflow
```bash
# Step 1: Build and test on Windows
mvn clean test -P test-all

# Step 2: Deploy to WSL
mvn package assembly:single -P wsl-deploy

# Step 3: Test on Linux (from WSL)
cd /home/amar/affinity-testing
java -cp faster-thread-affinity-*-jar-with-dependencies.jar com.faster.affinity.harness.TestHarness --platform=linux
```

## Quality Gates

### Mandatory Requirements
- **Test Coverage**: Minimum 85% line coverage (enforced by JaCoCo)
- **Performance**: P99 latency &lt; 50μs for affinity operations
- **Regression**: &lt; 5% performance degradation vs baseline
- **Platform Compatibility**: Pass on both Windows and Linux

### Build Failure Conditions
- Any test failures
- Coverage below 85%
- Performance regression &gt; 5%
- Memory leaks detected

## Test Categories

### Unit Tests
- **Purpose**: Component isolation testing
- **Scope**: Individual managers and utilities
- **Mocking**: Platform providers, hardware dependencies
- **Speed**: Fast (&lt; 2 minutes total)

### Integration Tests
- **Purpose**: Cross-platform compatibility
- **Scope**: Real hardware interaction
- **Platform-specific**: Linux governor control, Windows limitations
- **Speed**: Medium (3-5 minutes)

### Performance Tests
- **Purpose**: HFT latency requirements
- **Scope**: Regression detection, benchmark validation
- **Requirements**: P99 &lt; 50μs, avg &lt; 10μs
- **Speed**: Slow (10+ minutes)

## Troubleshooting

### Common Issues

1. **NoClassDefFoundError**
   - Solution: Rebuild JAR with `mvn clean package assembly:single`
   - Verify: Check target/ directory for jar-with-dependencies

2. **WSL Deployment Fails**
   - Solution: Ensure WSL is running and path exists
   - Create directory: `mkdir -p //wsl.localhost/Ubuntu/home/amar/affinity-testing`

3. **Performance Tests Fail**
   - Solution: Close unnecessary applications, set CPU to performance mode
   - Windows: Use Task Manager → Details → Set Priority → High

4. **Governor Tests Fail on Windows**
   - Expected: Windows has limited governor control
   - Solution: Tests will skip gracefully with warnings

### Debug Configuration
Add to VM parameters for detailed logging:
```
-Dcom.faster.affinity.debug=true
-Dcom.faster.affinity.verbose=true
-Djava.util.logging.level=FINEST
```

## Continuous Integration

### Pre-commit Hook
```bash
#!/bin/bash
mvn clean test -P test-all -q
if [ $? -ne 0 ]; then
    echo "Tests failed - commit blocked"
    exit 1
fi
```

### Release Validation
```bash
# Complete validation pipeline
mvn clean test jacoco:report -P test-all,hft-quality
mvn package assembly:single -P wsl-deploy
# Manual WSL testing
java -jar target/faster-thread-affinity-*-jar-with-dependencies.jar
```

## Performance Monitoring

### Key Metrics
- **Affinity Set Latency**: Target &lt; 10μs average
- **Affinity Get Latency**: Target &lt; 5μs average
- **Governor Query**: Target &lt; 15μs average
- **Memory Allocation**: &lt; 1MB per 10K operations

### Baseline Establishment
Run `HFTPerformanceRegressionTest.establishBaseline()` on each new hardware configuration to set platform-specific performance expectations.

## Best Practices

1. **Development Cycle**: Use "Quick Unit Tests" for rapid feedback
2. **Pre-commit**: Run "Unit Tests Only" before committing
3. **Pre-push**: Run "All Tests - Windows" before pushing
4. **Release**: Full "External Test Harness" + WSL validation
5. **Performance**: Weekly "HFT Performance Tests" on production hardware

## Integration with IDE

All run configurations are pre-configured for:
- Proper classpath handling
- Platform-specific VM parameters
- HFT-optimized JVM settings (ZGC, large pages)
- Debug and profiling support

Use IntelliJ's "Run Configurations" dropdown to select the appropriate test level for your current development phase.