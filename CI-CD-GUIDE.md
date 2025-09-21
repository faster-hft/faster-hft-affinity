# 🚀 CI/CD Pipeline Guide for HFT Thread Affinity Library

This guide covers the complete CI/CD pipeline setup for testing the HFT Thread Affinity Library across multiple platforms and architectures.

## 📋 Table of Contents

1. [Overview](#overview)
2. [GitHub Actions (Recommended)](#github-actions-recommended)
3. [AWS EC2 Testing](#aws-ec2-testing)
4. [Google Cloud Testing](#google-cloud-testing)
5. [Cost Analysis](#cost-analysis)
6. [Local Testing](#local-testing)
7. [Troubleshooting](#troubleshooting)

## 🎯 Overview

Our CI/CD pipeline provides comprehensive testing across:

- **Architectures**: x86_64 (Intel/AMD), ARM64 (Graviton, Apple Silicon, Ampere)
- **Operating Systems**: Linux (Ubuntu, Amazon Linux), Windows, macOS
- **Java Versions**: 11, 17, 21
- **Cloud Platforms**: GitHub Actions, AWS EC2, Google Cloud

### Testing Matrix

| Platform | x86_64 | ARM64 | Cost | Notes |
|----------|--------|-------|------|-------|
| GitHub Actions | ✅ | ✅ | **FREE/Low** | Recommended for most testing |
| AWS EC2 | ✅ | ✅ | ~$50-200/month | Advanced hardware testing |
| Google Cloud | ✅ | ✅ | ~$40-180/month | Ampere Altra ARM processors |

## 🔧 GitHub Actions (Recommended)

### Features
- **FREE** for public repositories
- **Unlimited** minutes for public repos
- **2,000 minutes/month** for private repos
- Multi-platform and multi-architecture support

### Workflows

#### 1. Main CI Workflow (`.github/workflows/ci.yml`)
Runs on every push and pull request:

```yaml
# Triggered by: push, pull_request, daily schedule
# Platforms: Ubuntu, Windows, macOS (x86_64 + ARM64)
# Java versions: 11, 17, 21
# Test types: Unit, Integration, Platform-specific
```

**Key Features:**
- ✅ Multi-platform matrix testing
- ✅ Comprehensive test suite
- ✅ Security analysis with OWASP dependency check
- ✅ Code coverage reporting with Codecov
- ✅ Artifact generation and upload

#### 2. Performance Testing Workflow (`.github/workflows/performance.yml`)
Runs performance benchmarks:

```yaml
# Triggered by: main branch pushes, performance label, weekly schedule
# Focus: HFT latency measurements, regression testing
# Optimizations: CPU governor, JVM tuning, memory settings
```

**Key Features:**
- ⚡ HFT-optimized system configuration
- 📊 Latency measurements and regression detection
- 🏗️ Architecture comparison (x86_64 vs ARM64)
- 📈 Performance tracking over time

#### 3. Release Workflow (`.github/workflows/release.yml`)
Automated release management:

```yaml
# Triggered by: version tags (v*.*.*)
# Process: Build → Test → Package → Release
# Artifacts: JAR files, checksums, release notes
```

**Key Features:**
- 🏷️ Automatic version detection from git tags
- 📦 Multi-platform artifact building
- 🔐 Checksum generation (SHA256, MD5)
- 📝 Automated release notes generation
- 🚀 GitHub Releases integration

### Setup Instructions

1. **Enable GitHub Actions** (if not already enabled):
   ```bash
   # Actions are enabled by default for new repositories
   # Check repository settings → Actions → General
   ```

2. **Configure Secrets** (for private repositories):
   ```bash
   # Optional: Add CODECOV_TOKEN for coverage reporting
   # Repository → Settings → Secrets and variables → Actions
   ```

3. **Trigger First Run**:
   ```bash
   git push origin main  # Triggers CI workflow
   ```

### Cost Calculation

| Repository Type | Monthly Cost | Notes |
|----------------|--------------|-------|
| **Public** | **$0** | Unlimited minutes |
| **Private** | $0-50 | 2,000 free minutes, then $0.008/minute (Ubuntu) |

**Example for private repo:**
- 50 runs/month × 15 minutes/run = 750 minutes
- Cost: FREE (under 2,000 minute limit)

## ☁️ AWS EC2 Testing

### When to Use AWS
- Testing on specialized hardware (Intel, AMD, ARM Graviton)
- High-performance computing instances
- Specific CPU features testing
- Custom system configurations

### Setup Instructions

1. **Prerequisites**:
   ```bash
   # Install AWS CLI
   curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
   unzip awscliv2.zip
   sudo ./aws/install

   # Configure AWS CLI
   aws configure
   ```

2. **Setup AWS Resources**:
   ```bash
   cd scripts/aws
   chmod +x setup-ec2-testing.sh
   ./setup-ec2-testing.sh setup
   ```

3. **Launch and Test**:
   ```bash
   # Launch all instance types
   ./setup-ec2-testing.sh launch

   # Or launch specific configuration
   ./setup-ec2-testing.sh launch intel-c5

   # Run tests
   ./setup-ec2-testing.sh test

   # Check costs
   ./setup-ec2-testing.sh cost
   ```

4. **Cleanup**:
   ```bash
   ./setup-ec2-testing.sh cleanup
   ```

### Available Instance Configurations

| Configuration | Instance Type | Architecture | CPU Type | Hourly Cost |
|--------------|---------------|--------------|----------|-------------|
| `intel-m5` | m5.large | x86_64 | Intel | ~$0.096 |
| `intel-c5` | c5.large | x86_64 | Intel | ~$0.085 |
| `amd-m5a` | m5a.large | x86_64 | AMD | ~$0.086 |
| `amd-c5a` | c5a.large | x86_64 | AMD | ~$0.077 |
| `arm-m6g` | m6g.large | arm64 | Graviton2 | ~$0.077 |
| `arm-c6g` | c6g.large | arm64 | Graviton2 | ~$0.068 |
| `hpc-c5n` | c5n.large | x86_64 | Intel + 25 Gbps | ~$0.108 |
| `hpc-m5n` | m5n.large | x86_64 | Intel + 25 Gbps | ~$0.096 |

### Cost Estimation

**Complete testing cycle (all instances, 2 hours):**
- Total instances: 8
- Average cost: ~$0.085/hour
- **Total cost: ~$1.36 per complete test cycle**

**Monthly testing (weekly cycles):**
- 4 cycles/month × $1.36 = **~$5.44/month**

## 🌐 Google Cloud Testing

### When to Use GCP
- Testing ARM64 Ampere Altra processors
- Different cloud provider validation
- GCP-specific optimizations
- Alternative to AWS

### Setup Instructions

1. **Prerequisites**:
   ```bash
   # Install gcloud CLI
   curl https://sdk.cloud.google.com | bash
   exec -l $SHELL

   # Initialize and authenticate
   gcloud init
   gcloud auth login
   ```

2. **Setup GCP Resources**:
   ```bash
   cd scripts/gcp
   chmod +x setup-gce-testing.sh
   ./setup-gce-testing.sh setup
   ```

3. **Launch and Test**:
   ```bash
   # Launch all instance types
   ./setup-gce-testing.sh launch

   # Or launch specific configuration
   ./setup-gce-testing.sh launch arm-t2a

   # Run tests
   ./setup-gce-testing.sh test

   # Check costs
   ./setup-gce-testing.sh cost
   ```

4. **Cleanup**:
   ```bash
   ./setup-gce-testing.sh cleanup
   ```

### Available Instance Configurations

| Configuration | Machine Type | Architecture | CPU Type | Hourly Cost |
|--------------|--------------|--------------|----------|-------------|
| `intel-n1` | n1-standard-2 | x86_64 | Intel | ~$0.095 |
| `intel-n2` | n2-standard-2 | x86_64 | Intel | ~$0.098 |
| `intel-c2` | c2-standard-4 | x86_64 | Intel | ~$0.200 |
| `amd-n2d` | n2d-standard-2 | x86_64 | AMD EPYC | ~$0.078 |
| `amd-c2d` | c2d-standard-4 | x86_64 | AMD EPYC | ~$0.168 |
| `arm-t2a` | t2a-standard-2 | arm64 | Ampere Altra | ~$0.067 |
| `arm-t2a-large` | t2a-standard-4 | arm64 | Ampere Altra | ~$0.134 |

### Cost Estimation

**Complete testing cycle (all instances, 2 hours):**
- Total instances: 7
- Average cost: ~$0.120/hour
- **Total cost: ~$1.68 per complete test cycle**

**Monthly testing (weekly cycles):**
- 4 cycles/month × $1.68 = **~$6.72/month**

## 💰 Cost Analysis

### Summary

| Testing Method | Setup Time | Monthly Cost | Best For |
|----------------|------------|--------------|----------|
| **GitHub Actions** | 5 minutes | **$0-50** | Regular CI/CD, most use cases |
| **AWS EC2** | 30 minutes | **$5-200** | Specialized hardware, enterprise |
| **Google Cloud** | 30 minutes | **$5-180** | ARM64 Ampere, GCP validation |

### Cost Optimization Tips

1. **Start with GitHub Actions** - covers 90% of testing needs for free
2. **Use cloud testing selectively** - only for specific hardware or extensive testing
3. **Automate cleanup** - always terminate instances after testing
4. **Monitor costs** - set up billing alerts in AWS/GCP

### Budget Recommendations

| Project Phase | Monthly Budget | Testing Strategy |
|---------------|----------------|------------------|
| **Development** | $0-20 | GitHub Actions + occasional cloud testing |
| **Pre-release** | $20-100 | Regular cloud testing across all platforms |
| **Production** | $50-200 | Comprehensive testing + performance monitoring |

## 🏠 Local Testing

### Quick Local Setup

1. **Build and Test**:
   ```bash
   mvn clean test
   ```

2. **Platform-specific Tests**:
   ```bash
   # Linux
   mvn test -Plinux

   # Windows
   mvn test -Pwindows

   # Performance tests
   mvn test -Pperformance
   ```

3. **Comprehensive Test**:
   ```bash
   mvn verify -Ptest-all
   ```

4. **Standalone Test**:
   ```bash
   mvn package -DskipTests
   javac -cp "target/affinity-library-1.0.0-jar-with-dependencies.jar" ComprehensiveAffinityTest.java
   java -cp ".:target/affinity-library-1.0.0-jar-with-dependencies.jar" ComprehensiveAffinityTest
   ```

## 🚨 Troubleshooting

### Common Issues

#### GitHub Actions

**Issue**: Tests failing on specific platforms
```yaml
# Solution: Check platform-specific exclusions in pom.xml
<excludes>
    <exclude>**/Windows*Test.java</exclude>  <!-- Exclude on non-Windows -->
</excludes>
```

**Issue**: Out of GitHub Actions minutes
```bash
# Solution: Optimize workflow triggers
on:
  push:
    branches: [ main ]  # Only run on main branch
  pull_request:
    types: [opened, synchronize]  # Limit PR triggers
```

#### AWS EC2

**Issue**: Instance launch failures
```bash
# Check AWS limits
aws service-quotas get-service-quota \
  --service-code ec2 \
  --quota-code L-1216C47A  # Running On-Demand instances

# Solution: Request quota increase or use different instance types
```

**Issue**: SSH connection failures
```bash
# Check security group
aws ec2 describe-security-groups --group-names faster-thread-affinity-sg

# Ensure port 22 is open from your IP
```

#### Google Cloud

**Issue**: API not enabled
```bash
# Enable required APIs
gcloud services enable compute.googleapis.com
gcloud services enable cloudbuild.googleapis.com
```

**Issue**: Insufficient permissions
```bash
# Check current permissions
gcloud auth list
gcloud projects get-iam-policy PROJECT_ID

# Required roles: Compute Admin, Service Account User
```

### Performance Issues

**Issue**: High latency in affinity operations
- Check CPU governor: `cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor`
- Disable CPU idle states: Script handles this automatically
- Use real-time priority: `sudo` required for testing

**Issue**: Inconsistent performance results
- Run tests multiple times: Use performance workflow
- Check system load: `htop`, `iostat`
- Verify isolation: Use dedicated instances

### Test Failures

**Issue**: Permission denied for affinity operations
```java
// Expected on some cloud environments
// Tests should handle gracefully and report warnings
```

**Issue**: Architecture-specific failures
- Check CPU features: `cat /proc/cpuinfo | grep flags`
- Verify JVM architecture: `java -version`
- Use appropriate Maven profiles

## 📞 Support

### Getting Help

1. **Check workflow logs** in GitHub Actions
2. **Review instance logs** in AWS CloudWatch / GCP Operations
3. **Test locally** to isolate issues
4. **Create GitHub issue** with:
   - Platform/architecture details
   - Error logs and stack traces
   - Steps to reproduce

### Monitoring

- **GitHub Actions**: Built-in logs and status badges
- **AWS**: CloudWatch logs and metrics
- **GCP**: Operations suite monitoring
- **Local**: Maven Surefire reports in `target/surefire-reports/`

---

## 🎉 Success!

You now have a comprehensive CI/CD pipeline that tests your HFT Thread Affinity Library across:

- ✅ **8+ different architectures** (Intel, AMD, ARM64)
- ✅ **3 major operating systems** (Linux, Windows, macOS)
- ✅ **Multiple Java versions** (11, 17, 21)
- ✅ **Performance benchmarks** with HFT optimizations
- ✅ **Automated releases** with proper versioning
- ✅ **Cost-effective testing** starting from $0/month

The pipeline ensures your library works reliably across all target environments while maintaining the ultra-low latency requirements critical for HFT applications.