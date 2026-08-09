# 🔧 Testing Scripts for HFT Thread Affinity Library

This directory contains scripts for testing the HFT Thread Affinity Library across different cloud platforms and architectures.

## 📁 Directory Structure

```
scripts/
├── aws/                    # AWS EC2 testing scripts
│   ├── setup-ec2-testing.sh      # Main EC2 testing script
│   └── aws-batch-testing.yml     # CloudFormation for AWS Batch
├── gcp/                    # Google Cloud testing scripts
│   └── setup-gce-testing.sh      # Main GCE testing script
└── README.md              # This file
```

## 🚀 Quick Start

### AWS EC2 Testing

```bash
cd scripts/aws

# 1. Setup AWS resources
./setup-ec2-testing.sh setup

# 2. Launch instances
./setup-ec2-testing.sh launch

# 3. Run tests
./setup-ec2-testing.sh test

# 4. Check costs
./setup-ec2-testing.sh cost

# 5. Cleanup
./setup-ec2-testing.sh cleanup
```

### Google Cloud Testing

```bash
cd scripts/gcp

# 1. Setup GCP resources
./setup-gce-testing.sh setup

# 2. Launch instances
./setup-gce-testing.sh launch

# 3. Run tests
./setup-gce-testing.sh test

# 4. Check costs
./setup-gce-testing.sh cost

# 5. Cleanup
./setup-gce-testing.sh cleanup
```

## 🏗️ Architecture Support

### AWS EC2 Instances

| Configuration | Instance Type | Architecture | CPU Type | Use Case |
|--------------|---------------|--------------|----------|----------|
| `intel-m5` | m5.large | x86_64 | Intel Xeon | General purpose |
| `intel-c5` | c5.large | x86_64 | Intel Xeon | Compute optimized |
| `amd-m5a` | m5a.large | x86_64 | AMD EPYC | AMD testing |
| `amd-c5a` | c5a.large | x86_64 | AMD EPYC | AMD compute optimized |
| `arm-m6g` | m6g.large | arm64 | AWS Graviton2 | ARM64 general |
| `arm-c6g` | c6g.large | arm64 | AWS Graviton2 | ARM64 compute |
| `hpc-c5n` | c5n.large | x86_64 | Intel + 25Gbps | High performance |
| `hpc-m5n` | m5n.large | x86_64 | Intel + 25Gbps | Network optimized |

### Google Cloud Instances

| Configuration | Machine Type | Architecture | CPU Type | Use Case |
|--------------|--------------|--------------|----------|----------|
| `intel-n1` | n1-standard-2 | x86_64 | Intel Xeon | Legacy Intel |
| `intel-n2` | n2-standard-2 | x86_64 | Intel Cascade Lake | Modern Intel |
| `intel-c2` | c2-standard-4 | x86_64 | Intel Xeon | High-performance |
| `amd-n2d` | n2d-standard-2 | x86_64 | AMD EPYC | AMD Rome/Milan |
| `amd-c2d` | c2d-standard-4 | x86_64 | AMD EPYC | AMD compute |
| `arm-t2a` | t2a-standard-2 | arm64 | Ampere Altra | ARM64 standard |
| `arm-t2a-large` | t2a-standard-4 | arm64 | Ampere Altra | ARM64 large |

## 📋 Prerequisites

### AWS EC2 Testing

1. **AWS CLI Installation**:
   ```bash
   # Linux/macOS
   curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
   unzip awscliv2.zip
   sudo ./aws/install

   # Windows
   # Download and install from: https://aws.amazon.com/cli/
   ```

2. **AWS Configuration**:
   ```bash
   aws configure
   # Enter: Access Key ID, Secret Access Key, Region, Output format
   ```

3. **Required IAM Permissions**:
   - EC2FullAccess (or custom policy with EC2 permissions)
   - IAMReadOnlyAccess (for security group creation)

### Google Cloud Testing

1. **gcloud CLI Installation**:
   ```bash
   # Linux/macOS
   curl https://sdk.cloud.google.com | bash
   exec -l $SHELL

   # Windows
   # Download from: https://cloud.google.com/sdk/docs/install
   ```

2. **GCP Configuration**:
   ```bash
   gcloud init
   gcloud auth login
   gcloud config set project YOUR_PROJECT_ID
   ```

3. **Required APIs**:
   - Compute Engine API
   - Cloud Build API (optional)

4. **Required IAM Roles**:
   - Compute Admin
   - Service Account User

## 💰 Cost Estimates

### AWS EC2 Costs (us-east-1)

| Instance Type | Hourly Cost | Daily Cost (8h) | Monthly Cost (weekly 8h tests) |
|---------------|-------------|----------------|-------------------------------|
| m5.large | $0.096 | $0.77 | $3.08 |
| c5.large | $0.085 | $0.68 | $2.72 |
| m5a.large | $0.086 | $0.69 | $2.76 |
| c5a.large | $0.077 | $0.62 | $2.48 |
| m6g.large | $0.077 | $0.62 | $2.48 |
| c6g.large | $0.068 | $0.54 | $2.16 |
| c5n.large | $0.108 | $0.86 | $3.44 |
| m5n.large | $0.096 | $0.77 | $3.08 |

**Total for all instances: ~$22.20/month** (weekly 8-hour test cycles)

### Google Cloud Costs (us-central1)

| Machine Type | Hourly Cost | Daily Cost (8h) | Monthly Cost (weekly 8h tests) |
|--------------|-------------|----------------|-------------------------------|
| n1-standard-2 | $0.095 | $0.76 | $3.04 |
| n2-standard-2 | $0.098 | $0.78 | $3.12 |
| c2-standard-4 | $0.200 | $1.60 | $6.40 |
| n2d-standard-2 | $0.078 | $0.62 | $2.48 |
| c2d-standard-4 | $0.168 | $1.34 | $5.36 |
| t2a-standard-2 | $0.067 | $0.54 | $2.16 |
| t2a-standard-4 | $0.134 | $1.07 | $4.28 |

**Total for all instances: ~$26.84/month** (weekly 8-hour test cycles)

## 🔍 Script Features

### Common Features

- ✅ **Automated instance provisioning** across multiple architectures
- ✅ **System optimization** for HFT workloads (CPU governor, memory settings)
- ✅ **Comprehensive testing** (unit, integration, performance tests)
- ✅ **Cost tracking** and reporting
- ✅ **Automatic cleanup** to prevent unnecessary charges
- ✅ **Test result collection** and aggregation
- ✅ **Status monitoring** of all instances

### AWS-Specific Features

- 🔐 **Security group management** with SSH access
- 🔑 **Key pair creation** for secure access
- 📊 **Instance status monitoring** via AWS CLI
- 📁 **Test result archival** to S3 (optional)
- 🏗️ **AWS Batch integration** for large-scale testing

### GCP-Specific Features

- 🌐 **VPC network setup** with firewall rules
- 🔍 **Metadata service integration** for instance information
- 📈 **Google Cloud Operations** monitoring integration
- 🏷️ **Comprehensive labeling** for resource management
- 🔄 **Sustained use discount** awareness in cost calculations

## 📊 Test Results

### Output Files

Each script generates the following files:

```
test-results-{config-name}.log     # Detailed test output for each instance
results-{config-name}/             # JUnit XML reports and artifacts
instances.csv                      # Instance tracking information
{platform}-testing-cost-report.txt # Cost analysis and estimates
test-summary.txt                   # Aggregated test results
```

### Test Coverage

The scripts run the following test suites:

1. **System Information Collection**:
   - CPU architecture and features
   - Memory configuration
   - Operating system details
   - Java version and JVM settings

2. **Unit Tests**:
   - Core affinity management functions
   - NUMA topology detection
   - Performance monitoring components

3. **Integration Tests**:
   - Platform-specific affinity operations
   - Cross-platform compatibility
   - Error handling and recovery

4. **Performance Tests**:
   - Affinity setting latency measurements
   - CPU isolation effectiveness
   - Memory allocation performance

5. **Comprehensive Standalone Test**:
   - Real-world usage scenarios
   - End-to-end functionality validation
   - System optimization verification

## 🚨 Troubleshooting

### Common Issues

#### AWS EC2

**Issue**: "Instance limit exceeded"
```bash
# Check your EC2 limits
aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A

# Solution: Request limit increase or use fewer instances
```

**Issue**: "Security group already exists"
```bash
# This is normal - the script will reuse existing security groups
# To start fresh, delete the security group manually:
aws ec2 delete-security-group --group-name faster-hft-affinity-sg
```

#### Google Cloud

**Issue**: "API not enabled"
```bash
# Enable required APIs
gcloud services enable compute.googleapis.com
gcloud services enable cloudbuild.googleapis.com
```

**Issue**: "Insufficient quota"
```bash
# Check quotas
gcloud compute project-info describe --format="table(quotas.metric,quotas.limit,quotas.usage)"

# Request quota increase in GCP Console
```

### Test Failures

**Issue**: Permission denied for affinity operations
- This is expected in some cloud environments
- Tests should complete with warnings rather than failures
- Check test logs for specific error messages

**Issue**: Java compilation failures
- Verify Java installation: `java -version`
- Check JAVA_HOME: `echo $JAVA_HOME`
- Try different Java version: Scripts install multiple versions

### Cost Management

**Issue**: Unexpected charges
- Always run cleanup scripts after testing
- Set up billing alerts in cloud consoles
- Monitor instance status regularly

**Issue**: Instances not terminating
```bash
# AWS
aws ec2 describe-instances --filters "Name=tag:Project,Values=faster-hft-affinity"
aws ec2 terminate-instances --instance-ids i-1234567890abcdef0

# GCP
gcloud compute instances list --filter="labels.project=faster-hft-affinity"
gcloud compute instances delete INSTANCE_NAME --zone=ZONE
```

## 📈 Best Practices

### Testing Strategy

1. **Start Small**: Test with one or two instance types first
2. **Regular Testing**: Run weekly tests during development
3. **Performance Baseline**: Establish performance benchmarks early
4. **Cost Monitoring**: Set up billing alerts and quotas

### Resource Management

1. **Tag Everything**: Use consistent labeling/tagging
2. **Automate Cleanup**: Always use provided cleanup scripts
3. **Monitor Costs**: Check costs after each test cycle
4. **Use Spot Instances**: Consider spot/preemptible instances for cost savings

### Security

1. **Minimal Permissions**: Use least-privilege IAM policies
2. **Secure Access**: Rely on cloud-native authentication
3. **Clean Up Credentials**: Remove temporary access keys
4. **Network Security**: Use appropriate security groups/firewall rules

## 🤝 Contributing

To add support for new cloud providers or instance types:

1. **Fork the repository**
2. **Add new configuration** to the appropriate script
3. **Test thoroughly** across architectures
4. **Update documentation**
5. **Submit pull request**

### Adding New Instance Types

#### AWS
```bash
# Add to INSTANCE_CONFIGS array in setup-ec2-testing.sh
["new-config"]="instance-type,architecture,cpu-type"
```

#### GCP
```bash
# Add to INSTANCE_CONFIGS array in setup-gce-testing.sh
["new-config"]="machine-type,architecture,cpu-type"
```

---

## 📞 Support

For issues with these scripts:

1. Check the troubleshooting section above
2. Review cloud provider documentation
3. Create an issue in the main repository
4. Include platform details and error logs

Happy testing! 🚀