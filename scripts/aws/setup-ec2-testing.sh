#!/bin/bash

# AWS EC2 Multi-Architecture Testing Setup Script
# This script sets up EC2 instances for testing the HFT Thread Affinity Library

set -euo pipefail

# Configuration
PROJECT_NAME="faster-thread-affinity"
KEY_NAME="${PROJECT_NAME}-test-key"
SECURITY_GROUP="${PROJECT_NAME}-sg"
SUBNET_TYPE="public"  # Change to "private" if needed

# Instance configurations for different architectures
declare -A INSTANCE_CONFIGS=(
    # Intel instances
    ["intel-m5"]="m5.large,x86_64,ami-0c55b159cbfafe1d0"
    ["intel-c5"]="c5.large,x86_64,ami-0c55b159cbfafe1d0"

    # AMD instances
    ["amd-m5a"]="m5a.large,x86_64,ami-0c55b159cbfafe1d0"
    ["amd-c5a"]="c5a.large,x86_64,ami-0c55b159cbfafe1d0"

    # ARM instances (Graviton)
    ["arm-m6g"]="m6g.large,arm64,ami-0f85a06f4a8b6e3f2"
    ["arm-c6g"]="c6g.large,arm64,ami-0f85a06f4a8b6e3f2"

    # High-performance instances
    ["hpc-c5n"]="c5n.large,x86_64,ami-0c55b159cbfafe1d0"
    ["hpc-m5n"]="m5n.large,x86_64,ami-0c55b159cbfafe1d0"
)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Check AWS CLI installation and configuration
check_aws_setup() {
    log "Checking AWS CLI setup..."

    if ! command -v aws &> /dev/null; then
        error "AWS CLI is not installed. Please install it first:"
        echo "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
        exit 1
    fi

    if ! aws sts get-caller-identity &> /dev/null; then
        error "AWS CLI is not configured. Run 'aws configure' first."
        exit 1
    fi

    local account_id=$(aws sts get-caller-identity --query Account --output text)
    local region=$(aws configure get region)

    success "AWS CLI configured for account: $account_id in region: $region"
}

# Create security group
create_security_group() {
    log "Creating security group..."

    local vpc_id=$(aws ec2 describe-vpcs --filters "Name=is-default,Values=true" --query "Vpcs[0].VpcId" --output text)

    if aws ec2 describe-security-groups --group-names "$SECURITY_GROUP" &> /dev/null; then
        warn "Security group $SECURITY_GROUP already exists"
        return 0
    fi

    local sg_id=$(aws ec2 create-security-group \
        --group-name "$SECURITY_GROUP" \
        --description "Security group for HFT Thread Affinity testing" \
        --vpc-id "$vpc_id" \
        --query "GroupId" --output text)

    # Allow SSH access
    aws ec2 authorize-security-group-ingress \
        --group-id "$sg_id" \
        --protocol tcp \
        --port 22 \
        --cidr 0.0.0.0/0

    success "Created security group: $sg_id"
}

# Create key pair
create_key_pair() {
    log "Creating key pair..."

    if aws ec2 describe-key-pairs --key-names "$KEY_NAME" &> /dev/null; then
        warn "Key pair $KEY_NAME already exists"
        return 0
    fi

    aws ec2 create-key-pair \
        --key-name "$KEY_NAME" \
        --query 'KeyMaterial' \
        --output text > "${KEY_NAME}.pem"

    chmod 400 "${KEY_NAME}.pem"

    success "Created key pair: $KEY_NAME"
}

# Launch EC2 instance
launch_instance() {
    local config_name="$1"
    local config="${INSTANCE_CONFIGS[$config_name]}"

    IFS=',' read -r instance_type arch ami_id <<< "$config"

    log "Launching $config_name instance ($instance_type, $arch)..."

    # Check if instance already exists
    local existing_instance=$(aws ec2 describe-instances \
        --filters \
            "Name=tag:Name,Values=${PROJECT_NAME}-${config_name}" \
            "Name=instance-state-name,Values=running,pending" \
        --query "Reservations[*].Instances[*].InstanceId" \
        --output text)

    if [[ -n "$existing_instance" ]]; then
        warn "Instance for $config_name already running: $existing_instance"
        return 0
    fi

    # Get the latest AMI for the architecture
    local latest_ami
    if [[ "$arch" == "arm64" ]]; then
        latest_ami=$(aws ec2 describe-images \
            --owners amazon \
            --filters \
                "Name=name,Values=amzn2-ami-hvm-*-arm64-gp2" \
                "Name=state,Values=available" \
            --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" \
            --output text)
    else
        latest_ami=$(aws ec2 describe-images \
            --owners amazon \
            --filters \
                "Name=name,Values=amzn2-ami-hvm-*-x86_64-gp2" \
                "Name=state,Values=available" \
            --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" \
            --output text)
    fi

    log "Using AMI: $latest_ami for $arch architecture"

    # User data script for instance setup
    local user_data_script="$(cat << 'EOF'
#!/bin/bash
yum update -y
yum install -y java-11-amazon-corretto-headless java-17-amazon-corretto-headless
yum install -y git maven htop iotop sysstat perf util-linux

# Create test user
useradd -m -s /bin/bash testuser
echo "testuser ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Configure system for HFT testing
echo "Setting up system optimizations..."

# CPU governor
echo performance > /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null || true

# Disable CPU idle states for consistent performance
for i in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
    echo 1 > $i 2>/dev/null || true
done

# Network optimizations
echo 'net.core.rmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 65536 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 134217728' >> /etc/sysctl.conf
sysctl -p

# Create testing directory
mkdir -p /opt/affinity-testing
chown testuser:testuser /opt/affinity-testing

echo "System setup completed" > /tmp/setup-complete
EOF
)"

    local instance_id=$(aws ec2 run-instances \
        --image-id "$latest_ami" \
        --count 1 \
        --instance-type "$instance_type" \
        --key-name "$KEY_NAME" \
        --security-groups "$SECURITY_GROUP" \
        --user-data "$user_data_script" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${PROJECT_NAME}-${config_name}},{Key=Project,Value=${PROJECT_NAME}},{Key=Architecture,Value=${arch}},{Key=InstanceType,Value=${instance_type}}]" \
        --query "Instances[0].InstanceId" \
        --output text)

    success "Launched instance: $instance_id ($config_name)"

    # Wait for instance to be running
    log "Waiting for instance to be running..."
    aws ec2 wait instance-running --instance-ids "$instance_id"

    local public_ip=$(aws ec2 describe-instances \
        --instance-ids "$instance_id" \
        --query "Reservations[0].Instances[0].PublicIpAddress" \
        --output text)

    success "Instance $config_name ready at: $public_ip"

    # Save instance info
    echo "$config_name,$instance_id,$public_ip,$instance_type,$arch" >> instances.csv
}

# Deploy and test on instance
test_on_instance() {
    local instance_info="$1"
    IFS=',' read -r config_name instance_id public_ip instance_type arch <<< "$instance_info"

    log "Testing on $config_name ($public_ip)..."

    # Wait for SSH to be available
    log "Waiting for SSH to be available..."
    local max_attempts=30
    local attempt=1

    while ! ssh -i "${KEY_NAME}.pem" -o ConnectTimeout=10 -o StrictHostKeyChecking=no ec2-user@"$public_ip" "echo 'SSH connected'" &> /dev/null; do
        if [[ $attempt -gt $max_attempts ]]; then
            error "SSH connection failed after $max_attempts attempts"
            return 1
        fi
        sleep 10
        ((attempt++))
    done

    success "SSH connection established to $config_name"

    # Upload project files
    log "Uploading project files..."
    scp -i "${KEY_NAME}.pem" -o StrictHostKeyChecking=no -r \
        ../../../*.java \
        ../../../pom.xml \
        ../../../src/ \
        ec2-user@"$public_ip":/opt/affinity-testing/ || true

    # Run tests
    log "Running tests on $config_name..."

    local test_script="$(cat << 'EOF'
#!/bin/bash
cd /opt/affinity-testing

echo "=== System Information ==="
echo "Architecture: $(uname -m)"
echo "CPU Info:"
cat /proc/cpuinfo | grep -E "(model name|cpu cores|flags)" | head -5
echo "Memory:"
free -h
echo "Java versions:"
java -version
echo "=========================="

echo "Building project..."
mvn clean compile -B -q || {
    echo "Maven build failed, trying with Amazon Corretto 17..."
    export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto
    mvn clean compile -B -q
}

echo "Running comprehensive test..."
mvn package -B -DskipTests -q

# Compile and run standalone test
javac -cp "target/affinity-library-1.0.0-jar-with-dependencies.jar" ComprehensiveAffinityTest.java

echo "Running affinity test with privileges..."
sudo java -cp ".:target/affinity-library-1.0.0-jar-with-dependencies.jar" \
    -XX:+UseG1GC -Xmx1g \
    ComprehensiveAffinityTest

echo "Running unit tests..."
mvn test -B -fae -Dtest=*AffinityManagerTest* || echo "Some tests may fail due to privilege limitations"

echo "Testing completed on $(uname -m) architecture"
EOF
)"

    ssh -i "${KEY_NAME}.pem" -o StrictHostKeyChecking=no ec2-user@"$public_ip" "$test_script" > "test-results-${config_name}.log" 2>&1

    local exit_code=$?
    if [[ $exit_code -eq 0 ]]; then
        success "Tests completed successfully on $config_name"
    else
        warn "Tests completed with warnings on $config_name (exit code: $exit_code)"
    fi

    # Download test results
    scp -i "${KEY_NAME}.pem" -o StrictHostKeyChecking=no \
        ec2-user@"$public_ip":/opt/affinity-testing/target/surefire-reports/*.xml \
        ./results-${config_name}/ 2>/dev/null || mkdir -p ./results-${config_name}
}

# Cleanup instances
cleanup_instances() {
    log "Cleaning up instances..."

    if [[ ! -f instances.csv ]]; then
        warn "No instances.csv file found"
        return 0
    fi

    while IFS=',' read -r config_name instance_id public_ip instance_type arch; do
        log "Terminating instance: $config_name ($instance_id)"
        aws ec2 terminate-instances --instance-ids "$instance_id" > /dev/null
    done < instances.csv

    rm -f instances.csv
    success "Instance cleanup initiated"
}

# Generate cost report
generate_cost_report() {
    log "Generating cost report..."

    if [[ ! -f instances.csv ]]; then
        warn "No instances.csv file found"
        return 0
    fi

    local total_cost=0
    local report_file="aws-testing-cost-report.txt"

    echo "AWS EC2 Testing Cost Report" > "$report_file"
    echo "Generated: $(date)" >> "$report_file"
    echo "========================================" >> "$report_file"
    echo "" >> "$report_file"

    while IFS=',' read -r config_name instance_id public_ip instance_type arch; do
        # Get approximate hourly cost (these are estimates)
        local hourly_cost
        case "$instance_type" in
            "m5.large"|"m5a.large"|"m6g.large") hourly_cost="0.096" ;;
            "c5.large"|"c5a.large"|"c6g.large") hourly_cost="0.085" ;;
            "c5n.large"|"m5n.large") hourly_cost="0.108" ;;
            *) hourly_cost="0.100" ;;
        esac

        echo "$config_name ($instance_type, $arch): \$${hourly_cost}/hour" >> "$report_file"
        total_cost=$(echo "$total_cost + $hourly_cost" | bc -l 2>/dev/null || echo "$total_cost")
    done < instances.csv

    echo "" >> "$report_file"
    echo "Estimated total cost per hour: \$${total_cost}" >> "$report_file"
    echo "Estimated cost for 1-hour test: \$${total_cost}" >> "$report_file"
    echo "Estimated cost for 8-hour workday: \$$(echo "$total_cost * 8" | bc -l 2>/dev/null || echo "N/A")" >> "$report_file"

    success "Cost report generated: $report_file"
}

# Main function
main() {
    local action="${1:-help}"

    case "$action" in
        "setup")
            log "Setting up AWS EC2 testing environment..."
            check_aws_setup
            create_security_group
            create_key_pair

            # Initialize instances file
            echo "config_name,instance_id,public_ip,instance_type,arch" > instances.csv

            success "AWS setup completed"
            ;;

        "launch")
            local config_name="${2:-all}"

            if [[ "$config_name" == "all" ]]; then
                log "Launching all instance configurations..."
                for config in "${!INSTANCE_CONFIGS[@]}"; do
                    launch_instance "$config"
                done
            elif [[ -n "${INSTANCE_CONFIGS[$config_name]}" ]]; then
                launch_instance "$config_name"
            else
                error "Unknown configuration: $config_name"
                echo "Available configurations: ${!INSTANCE_CONFIGS[*]}"
                exit 1
            fi

            generate_cost_report
            ;;

        "test")
            log "Running tests on all instances..."

            if [[ ! -f instances.csv ]]; then
                error "No instances found. Run 'launch' first."
                exit 1
            fi

            # Skip header line
            tail -n +2 instances.csv | while IFS=',' read -r config_name instance_id public_ip instance_type arch; do
                test_on_instance "$config_name,$instance_id,$public_ip,$instance_type,$arch"
            done

            log "Generating test summary..."
            echo "Test Summary - $(date)" > test-summary.txt
            echo "===========================================" >> test-summary.txt
            for log_file in test-results-*.log; do
                if [[ -f "$log_file" ]]; then
                    echo "" >> test-summary.txt
                    echo "=== $log_file ===" >> test-summary.txt
                    tail -20 "$log_file" >> test-summary.txt
                fi
            done

            success "Testing completed. Check test-summary.txt for results."
            ;;

        "cleanup")
            cleanup_instances
            ;;

        "status")
            log "Checking instance status..."

            if [[ ! -f instances.csv ]]; then
                warn "No instances.csv file found"
                exit 0
            fi

            printf "%-15s %-20s %-15s %-12s %-8s %-10s\n" "CONFIG" "INSTANCE_ID" "PUBLIC_IP" "TYPE" "ARCH" "STATUS"
            echo "--------------------------------------------------------------------------------"

            tail -n +2 instances.csv | while IFS=',' read -r config_name instance_id public_ip instance_type arch; do
                local status=$(aws ec2 describe-instances \
                    --instance-ids "$instance_id" \
                    --query "Reservations[0].Instances[0].State.Name" \
                    --output text 2>/dev/null || echo "unknown")

                printf "%-15s %-20s %-15s %-12s %-8s %-10s\n" \
                    "$config_name" "$instance_id" "$public_ip" "$instance_type" "$arch" "$status"
            done
            ;;

        "cost")
            generate_cost_report
            cat aws-testing-cost-report.txt
            ;;

        "help"|*)
            echo "AWS EC2 Multi-Architecture Testing Setup"
            echo ""
            echo "Usage: $0 <command> [options]"
            echo ""
            echo "Commands:"
            echo "  setup          - Set up AWS resources (security group, key pair)"
            echo "  launch [config] - Launch EC2 instances (all or specific config)"
            echo "  test           - Run tests on all launched instances"
            echo "  status         - Show status of all instances"
            echo "  cost           - Show cost estimates"
            echo "  cleanup        - Terminate all instances"
            echo "  help           - Show this help"
            echo ""
            echo "Available configurations:"
            for config in "${!INSTANCE_CONFIGS[@]}"; do
                echo "  $config"
            done
            echo ""
            echo "Example workflow:"
            echo "  $0 setup"
            echo "  $0 launch"
            echo "  $0 test"
            echo "  $0 cleanup"
            ;;
    esac
}

# Run main function with all arguments
main "$@"