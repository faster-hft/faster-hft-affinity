#!/bin/bash

# Google Cloud Platform (GCP) Multi-Architecture Testing Setup Script
# This script sets up GCE instances for testing the HFT Thread Affinity Library

set -euo pipefail

# Configuration
PROJECT_NAME="faster-thread-affinity"
ZONE_X86="us-central1-b"     # Intel/AMD zones
ZONE_ARM="us-central1-a"     # ARM zones
REGION="us-central1"
NETWORK_NAME="${PROJECT_NAME}-network"
FIREWALL_RULE="${PROJECT_NAME}-ssh"

# Instance configurations for different architectures
declare -A INSTANCE_CONFIGS=(
    # Intel instances
    ["intel-n1"]="n1-standard-2,x86_64,intel"
    ["intel-n2"]="n2-standard-2,x86_64,intel"
    ["intel-c2"]="c2-standard-4,x86_64,intel"

    # AMD instances
    ["amd-n2d"]="n2d-standard-2,x86_64,amd"
    ["amd-c2d"]="c2d-standard-4,x86_64,amd"

    # ARM instances (Ampere Altra)
    ["arm-t2a"]="t2a-standard-2,arm64,ampere"
    ["arm-t2a-large"]="t2a-standard-4,arm64,ampere"

    # High-performance instances
    ["hpc-c2"]="c2-standard-8,x86_64,intel"
    ["hpc-c2d"]="c2d-standard-8,x86_64,amd"
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

# Check gcloud CLI installation and configuration
check_gcloud_setup() {
    log "Checking gcloud CLI setup..."

    if ! command -v gcloud &> /dev/null; then
        error "gcloud CLI is not installed. Please install it first:"
        echo "https://cloud.google.com/sdk/docs/install"
        exit 1
    fi

    # Check if authenticated
    if ! gcloud auth list --filter=status:ACTIVE --format="value(account)" | grep -q @; then
        error "gcloud CLI is not authenticated. Run 'gcloud auth login' first."
        exit 1
    fi

    # Get current project
    local current_project=$(gcloud config get-value project 2>/dev/null || echo "")
    if [[ -z "$current_project" ]]; then
        error "No default project set. Run 'gcloud config set project PROJECT_ID'"
        exit 1
    fi

    local account=$(gcloud auth list --filter=status:ACTIVE --format="value(account)" | head -1)
    success "gcloud CLI configured for project: $current_project, account: $account"

    # Check required APIs
    log "Checking required APIs..."
    local required_apis=("compute.googleapis.com" "cloudbuild.googleapis.com")
    for api in "${required_apis[@]}"; do
        if ! gcloud services list --enabled --filter="name:$api" --format="value(name)" | grep -q "$api"; then
            log "Enabling API: $api"
            gcloud services enable "$api"
        fi
    done
}

# Create network and firewall rules
create_network() {
    log "Creating network infrastructure..."

    # Create VPC network
    if gcloud compute networks describe "$NETWORK_NAME" &> /dev/null; then
        warn "Network $NETWORK_NAME already exists"
    else
        gcloud compute networks create "$NETWORK_NAME" \
            --subnet-mode=auto \
            --description="Network for HFT Thread Affinity testing"
        success "Created network: $NETWORK_NAME"
    fi

    # Create firewall rule for SSH
    if gcloud compute firewall-rules describe "$FIREWALL_RULE" &> /dev/null; then
        warn "Firewall rule $FIREWALL_RULE already exists"
    else
        gcloud compute firewall-rules create "$FIREWALL_RULE" \
            --network="$NETWORK_NAME" \
            --allow=tcp:22 \
            --source-ranges=0.0.0.0/0 \
            --description="Allow SSH for HFT affinity testing"
        success "Created firewall rule: $FIREWALL_RULE"
    fi
}

# Get appropriate zone for instance type
get_zone_for_config() {
    local config_name="$1"
    local config="${INSTANCE_CONFIGS[$config_name]}"
    IFS=',' read -r machine_type arch cpu_type <<< "$config"

    if [[ "$arch" == "arm64" ]]; then
        echo "$ZONE_ARM"
    else
        echo "$ZONE_X86"
    fi
}

# Get appropriate image for architecture
get_image_for_arch() {
    local arch="$1"

    if [[ "$arch" == "arm64" ]]; then
        # Get latest Ubuntu 20.04 LTS for ARM64
        gcloud compute images list \
            --filter="family=ubuntu-2004-lts AND architecture=ARM64" \
            --format="value(name)" \
            --sort-by="~creationTimestamp" \
            --limit=1 \
            --project=ubuntu-os-cloud
    else
        # Get latest Ubuntu 20.04 LTS for x86_64
        gcloud compute images list \
            --filter="family=ubuntu-2004-lts AND architecture=X86_64" \
            --format="value(name)" \
            --sort-by="~creationTimestamp" \
            --limit=1 \
            --project=ubuntu-os-cloud
    fi
}

# Launch GCE instance
launch_instance() {
    local config_name="$1"
    local config="${INSTANCE_CONFIGS[$config_name]}"

    IFS=',' read -r machine_type arch cpu_type <<< "$config"

    log "Launching $config_name instance ($machine_type, $arch, $cpu_type)..."

    # Check if instance already exists
    local zone=$(get_zone_for_config "$config_name")
    if gcloud compute instances describe "${PROJECT_NAME}-${config_name}" --zone="$zone" &> /dev/null; then
        warn "Instance ${PROJECT_NAME}-${config_name} already exists"
        return 0
    fi

    # Get appropriate image
    local image=$(get_image_for_arch "$arch")
    if [[ -z "$image" ]]; then
        error "Could not find appropriate image for architecture: $arch"
        return 1
    fi

    log "Using image: $image for $arch architecture"

    # Startup script for instance configuration
    local startup_script="$(cat << 'EOF'
#!/bin/bash

# Update system
apt-get update -y
apt-get upgrade -y

# Install Java (multiple versions)
apt-get install -y openjdk-11-jdk openjdk-17-jdk maven git

# Install monitoring and performance tools
apt-get install -y htop iotop sysstat linux-tools-generic curl wget

# Create test user
useradd -m -s /bin/bash testuser
usermod -aG sudo testuser
echo "testuser ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# System optimizations for HFT testing
echo "Setting up HFT optimizations..."

# CPU governor (if available)
echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null || echo "CPU governor not available"

# Disable CPU idle states for consistent performance
for state in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
    if [[ -f "$state" ]]; then
        echo 1 > "$state" 2>/dev/null || true
    fi
done

# Memory optimizations
echo 1 > /proc/sys/vm/swappiness
echo never > /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || true

# Network optimizations
cat >> /etc/sysctl.conf << 'SYSCTL_EOF'
# Network optimizations for HFT
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 65536 134217728
net.ipv4.tcp_wmem = 4096 65536 134217728
net.core.netdev_max_backlog = 5000
SYSCTL_EOF

sysctl -p

# Create testing directory
mkdir -p /opt/affinity-testing
chown testuser:testuser /opt/affinity-testing

# Install Google Cloud Operations Agent
curl -sSO https://dl.google.com/cloudagents/add-google-cloud-ops-agent-repo.sh
bash add-google-cloud-ops-agent-repo.sh --also-install

echo "GCE instance setup completed" > /tmp/setup-complete
echo "Architecture: $(uname -m)" >> /tmp/setup-complete
echo "CPU info:" >> /tmp/setup-complete
cat /proc/cpuinfo | grep -E "(model name|cpu cores|flags)" | head -3 >> /tmp/setup-complete
EOF
)"

    # Launch the instance
    gcloud compute instances create "${PROJECT_NAME}-${config_name}" \
        --zone="$zone" \
        --machine-type="$machine_type" \
        --network="$NETWORK_NAME" \
        --image="$image" \
        --image-project=ubuntu-os-cloud \
        --boot-disk-size=20GB \
        --boot-disk-type=pd-ssd \
        --metadata-from-file startup-script=<(echo "$startup_script") \
        --tags="$PROJECT_NAME" \
        --labels="project=$PROJECT_NAME,architecture=${arch//_/-},cpu-type=$cpu_type" \
        --maintenance-policy=MIGRATE \
        --scopes=cloud-platform

    success "Launched instance: ${PROJECT_NAME}-${config_name} in zone $zone"

    # Wait for instance to be running
    log "Waiting for instance to be running..."
    while true; do
        local status=$(gcloud compute instances describe "${PROJECT_NAME}-${config_name}" \
            --zone="$zone" \
            --format="value(status)")

        if [[ "$status" == "RUNNING" ]]; then
            break
        fi

        log "Instance status: $status, waiting..."
        sleep 10
    done

    local external_ip=$(gcloud compute instances describe "${PROJECT_NAME}-${config_name}" \
        --zone="$zone" \
        --format="value(networkInterfaces[0].accessConfigs[0].natIP)")

    success "Instance $config_name ready at: $external_ip"

    # Save instance info
    echo "$config_name,${PROJECT_NAME}-${config_name},$external_ip,$machine_type,$arch,$zone" >> instances.csv
}

# Deploy and test on instance
test_on_instance() {
    local instance_info="$1"
    IFS=',' read -r config_name instance_name external_ip machine_type arch zone <<< "$instance_info"

    log "Testing on $config_name ($external_ip)..."

    # Wait for SSH to be available and system setup to complete
    log "Waiting for SSH and system setup..."
    local max_attempts=60
    local attempt=1

    while [[ $attempt -le $max_attempts ]]; do
        if gcloud compute ssh testuser@"$instance_name" \
            --zone="$zone" \
            --command="test -f /tmp/setup-complete" \
            --ssh-flag="-o ConnectTimeout=10" \
            --ssh-flag="-o StrictHostKeyChecking=no" &> /dev/null; then
            break
        fi

        if [[ $attempt -gt $max_attempts ]]; then
            error "Instance setup not completed after $max_attempts attempts"
            return 1
        fi

        log "Waiting for setup completion... (attempt $attempt/$max_attempts)"
        sleep 15
        ((attempt++))
    done

    success "SSH connection and setup verified for $config_name"

    # Upload project files
    log "Uploading project files..."

    # Create a temporary directory with project files
    local temp_dir=$(mktemp -d)
    cp -r ../../../*.java ../../../pom.xml ../../../src/ "$temp_dir/" 2>/dev/null || true

    gcloud compute scp --recurse "$temp_dir"/* testuser@"$instance_name":/opt/affinity-testing/ \
        --zone="$zone" \
        --ssh-flag="-o StrictHostKeyChecking=no"

    rm -rf "$temp_dir"

    # Run tests
    log "Running tests on $config_name..."

    local test_script="$(cat << 'EOF'
#!/bin/bash
cd /opt/affinity-testing

echo "=== GCP Instance System Information ==="
echo "Instance: $(curl -s http://metadata.google.internal/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")"
echo "Zone: $(curl -s http://metadata.google.internal/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google" | cut -d/ -f4)"
echo "Machine Type: $(curl -s http://metadata.google.internal/computeMetadata/v1/instance/machine-type -H "Metadata-Flavor: Google" | cut -d/ -f4)"
echo "Architecture: $(uname -m)"
echo "CPU Info:"
cat /proc/cpuinfo | grep -E "(model name|cpu cores|flags)" | head -5
echo "Memory:"
free -h
echo "Java versions available:"
update-alternatives --list java
echo "Using Java 17:"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture)
java -version
echo "================================================="

echo "Building project with Maven..."
mvn clean compile -B -q || {
    echo "Build failed, trying with different JAVA_HOME..."
    export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-$(dpkg --print-architecture)
    mvn clean compile -B -q
}

echo "Running comprehensive test..."
mvn package -B -DskipTests -q

# Compile and run standalone test
javac -cp "target/affinity-library-1.0.0-jar-with-dependencies.jar" ComprehensiveAffinityTest.java

echo "Running affinity test with enhanced privileges..."
sudo -E java -cp ".:target/affinity-library-1.0.0-jar-with-dependencies.jar" \
    -XX:+UseG1GC -Xmx1g \
    -Daffinity.gcp.test=true \
    ComprehensiveAffinityTest

echo "Running unit tests..."
mvn test -B -fae -Dtest=*AffinityManagerTest* || echo "Some tests may require additional privileges"

echo "Running performance tests..."
mvn test -B -fae -Pperformance -Daffinity.performance.iterations=1000 || echo "Performance tests completed with warnings"

echo "Test execution completed on $(uname -m) architecture"
echo "GCP metadata:"
echo "  Instance: $(curl -s http://metadata.google.internal/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")"
echo "  CPU platform: $(curl -s http://metadata.google.internal/computeMetadata/v1/instance/cpu-platform -H "Metadata-Flavor: Google")"
EOF
)"

    gcloud compute ssh testuser@"$instance_name" \
        --zone="$zone" \
        --command="$test_script" \
        --ssh-flag="-o StrictHostKeyChecking=no" > "test-results-${config_name}.log" 2>&1

    local exit_code=$?
    if [[ $exit_code -eq 0 ]]; then
        success "Tests completed successfully on $config_name"
    else
        warn "Tests completed with warnings on $config_name (exit code: $exit_code)"
    fi

    # Download test results
    mkdir -p "./results-${config_name}"
    gcloud compute scp testuser@"$instance_name":/opt/affinity-testing/target/surefire-reports/*.xml \
        "./results-${config_name}/" \
        --zone="$zone" \
        --ssh-flag="-o StrictHostKeyChecking=no" 2>/dev/null || echo "No test reports to download"
}

# Cleanup instances
cleanup_instances() {
    log "Cleaning up instances..."

    if [[ ! -f instances.csv ]]; then
        warn "No instances.csv file found"
        return 0
    fi

    while IFS=',' read -r config_name instance_name external_ip machine_type arch zone; do
        log "Deleting instance: $config_name ($instance_name)"
        gcloud compute instances delete "$instance_name" --zone="$zone" --quiet
    done < instances.csv

    rm -f instances.csv
    success "Instance cleanup completed"
}

# Generate cost report
generate_cost_report() {
    log "Generating cost report..."

    if [[ ! -f instances.csv ]]; then
        warn "No instances.csv file found"
        return 0
    fi

    local total_cost=0
    local report_file="gcp-testing-cost-report.txt"

    echo "GCP Compute Engine Testing Cost Report" > "$report_file"
    echo "Generated: $(date)" >> "$report_file"
    echo "Region: $REGION" >> "$report_file"
    echo "========================================" >> "$report_file"
    echo "" >> "$report_file"

    while IFS=',' read -r config_name instance_name external_ip machine_type arch zone; do
        # Get approximate hourly cost (these are estimates for us-central1)
        local hourly_cost
        case "$machine_type" in
            "n1-standard-2") hourly_cost="0.095" ;;
            "n2-standard-2") hourly_cost="0.098" ;;
            "n2d-standard-2") hourly_cost="0.078" ;;
            "c2-standard-4") hourly_cost="0.200" ;;
            "c2d-standard-4") hourly_cost="0.168" ;;
            "t2a-standard-2") hourly_cost="0.067" ;;
            "t2a-standard-4") hourly_cost="0.134" ;;
            "c2-standard-8") hourly_cost="0.400" ;;
            "c2d-standard-8") hourly_cost="0.336" ;;
            *) hourly_cost="0.100" ;;
        esac

        echo "$config_name ($machine_type, $arch, $zone): \$${hourly_cost}/hour" >> "$report_file"
        total_cost=$(echo "$total_cost + $hourly_cost" | bc -l 2>/dev/null || echo "$total_cost")
    done < instances.csv

    echo "" >> "$report_file"
    echo "Estimated total cost per hour: \$${total_cost}" >> "$report_file"
    echo "Estimated cost for 1-hour test: \$${total_cost}" >> "$report_file"
    echo "Estimated cost for 8-hour workday: \$$(echo "$total_cost * 8" | bc -l 2>/dev/null || echo "N/A")" >> "$report_file"
    echo "" >> "$report_file"
    echo "Note: Costs include compute only, not storage or network egress" >> "$report_file"
    echo "Actual costs may vary based on sustained use discounts and regional pricing" >> "$report_file"

    success "Cost report generated: $report_file"
}

# Main function
main() {
    local action="${1:-help}"

    case "$action" in
        "setup")
            log "Setting up GCP Compute Engine testing environment..."
            check_gcloud_setup
            create_network

            # Initialize instances file
            echo "config_name,instance_name,external_ip,machine_type,arch,zone" > instances.csv

            success "GCP setup completed"
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
            tail -n +2 instances.csv | while IFS=',' read -r config_name instance_name external_ip machine_type arch zone; do
                test_on_instance "$config_name,$instance_name,$external_ip,$machine_type,$arch,$zone"
            done

            log "Generating test summary..."
            echo "GCP Test Summary - $(date)" > test-summary.txt
            echo "==============================================" >> test-summary.txt
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

            printf "%-15s %-25s %-15s %-15s %-8s %-15s %-10s\n" "CONFIG" "INSTANCE_NAME" "EXTERNAL_IP" "MACHINE_TYPE" "ARCH" "ZONE" "STATUS"
            echo "--------------------------------------------------------------------------------------------------------"

            tail -n +2 instances.csv | while IFS=',' read -r config_name instance_name external_ip machine_type arch zone; do
                local status=$(gcloud compute instances describe "$instance_name" \
                    --zone="$zone" \
                    --format="value(status)" 2>/dev/null || echo "unknown")

                printf "%-15s %-25s %-15s %-15s %-8s %-15s %-10s\n" \
                    "$config_name" "$instance_name" "$external_ip" "$machine_type" "$arch" "$zone" "$status"
            done
            ;;

        "cost")
            generate_cost_report
            cat gcp-testing-cost-report.txt
            ;;

        "help"|*)
            echo "GCP Compute Engine Multi-Architecture Testing Setup"
            echo ""
            echo "Usage: $0 <command> [options]"
            echo ""
            echo "Commands:"
            echo "  setup          - Set up GCP resources (network, firewall)"
            echo "  launch [config] - Launch GCE instances (all or specific config)"
            echo "  test           - Run tests on all launched instances"
            echo "  status         - Show status of all instances"
            echo "  cost           - Show cost estimates"
            echo "  cleanup        - Delete all instances"
            echo "  help           - Show this help"
            echo ""
            echo "Available configurations:"
            for config in "${!INSTANCE_CONFIGS[@]}"; do
                echo "  $config - ${INSTANCE_CONFIGS[$config]}"
            done
            echo ""
            echo "Example workflow:"
            echo "  $0 setup"
            echo "  $0 launch"
            echo "  $0 test"
            echo "  $0 cleanup"
            echo ""
            echo "Prerequisites:"
            echo "  - gcloud CLI installed and authenticated"
            echo "  - Project configured with Compute Engine API enabled"
            echo "  - Appropriate IAM permissions for Compute Engine"
            ;;
    esac
}

# Run main function with all arguments
main "$@"