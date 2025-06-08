#!/bin/bash

# Metro vs Anvil Benchmark Runner
# 
# This script automatically regenerates projects for each mode and runs
# the corresponding benchmark scenarios to compare performance.

set -euo pipefail

# Configuration
DEFAULT_MODULE_COUNT=500
RESULTS_DIR="benchmark-results"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE} $1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

# Function to check if required tools are available
check_prerequisites() {
    print_header "Checking Prerequisites"
    
    local missing_tools=()
    
    if ! command -v kotlin &> /dev/null; then
        missing_tools+=("kotlin")
    fi
    
    if ! command -v gradle-profiler &> /dev/null; then
        missing_tools+=("gradle-profiler")
    fi
    
    if ! command -v ./gradlew &> /dev/null; then
        missing_tools+=("gradlew (not executable)")
    fi
    
    if [ ${#missing_tools[@]} -gt 0 ]; then
        print_error "Missing required tools: ${missing_tools[*]}"
        print_error "Please install missing tools and try again"
        exit 1
    fi
    
    print_success "All prerequisites available"
}

# Function to generate projects for a specific mode
generate_projects() {
    local mode=$1
    local processor=$2
    local count=${3:-$DEFAULT_MODULE_COUNT}
    
    print_status "Generating $count modules for $mode mode"
    if [ "$mode" = "anvil" ]; then
        print_status "Using $processor processor"
        kotlin generate-projects.main.kts --mode "$mode" --processor "$processor" --count "$count"
    else
        kotlin generate-projects.main.kts --mode "$mode" --count "$count"
    fi
    
    if [ $? -eq 0 ]; then
        print_success "Project generation completed for $mode mode"
    else
        print_error "Project generation failed for $mode mode"
        exit 1
    fi
}

# Function to run benchmark scenarios for a specific mode
run_scenarios() {
    local mode=$1
    local processor=${2:-""}
    
    local scenario_prefix
    if [ "$mode" = "metro" ]; then
        scenario_prefix="metro"
    elif [ "$mode" = "anvil" ] && [ "$processor" = "ksp" ]; then
        scenario_prefix="anvil_ksp"
    elif [ "$mode" = "anvil" ] && [ "$processor" = "kapt" ]; then
        scenario_prefix="anvil_kapt"
    else
        print_error "Invalid mode/processor combination: $mode/$processor"
        exit 1
    fi
    
    local scenarios=(
        "${scenario_prefix}_abi_change"
        "${scenario_prefix}_non_abi_change" 
        "${scenario_prefix}_raw_compilation"
    )
    
    local output_file="$RESULTS_DIR/${mode}_${processor}_${TIMESTAMP}.html"
    
    print_status "Running scenarios for $mode${processor:+ with $processor}: ${scenarios[*]}"
    print_status "Results will be saved to: $output_file"
    
    # Run gradle-profiler with the scenarios
    gradle-profiler \
        --benchmark \
        --scenario-file benchmark.scenarios \
        --output-dir "$RESULTS_DIR" \
        --gradle-user-home ~/.gradle \
        "${scenarios[@]}" \
        || {
            print_error "Benchmark failed for $mode mode"
            return 1
        }
    
    print_success "Benchmark completed for $mode mode"
}

# Function to run all benchmarks
run_all_benchmarks() {
    local count=${1:-$DEFAULT_MODULE_COUNT}
    
    print_header "Metro vs Anvil Benchmark Suite"
    print_status "Module count: $count"
    print_status "Results directory: $RESULTS_DIR"
    print_status "Timestamp: $TIMESTAMP"
    
    # Create results directory
    mkdir -p "$RESULTS_DIR"
    
    # 1. Metro Mode
    print_header "Running Metro Mode Benchmarks"
    generate_projects "metro" "" "$count"
    run_scenarios "metro"
    
    # 2. Anvil + KSP Mode  
    print_header "Running Anvil + KSP Mode Benchmarks"
    generate_projects "anvil" "ksp" "$count"
    run_scenarios "anvil" "ksp"
    
    # 3. Anvil + KAPT Mode
    print_header "Running Anvil + KAPT Mode Benchmarks"
    generate_projects "anvil" "kapt" "$count"
    run_scenarios "anvil" "kapt"
    
    print_header "Benchmark Suite Complete"
    print_success "All benchmarks completed successfully!"
    print_status "Results are available in: $RESULTS_DIR"
    
    # List generated result files
    if ls "$RESULTS_DIR"/*"$TIMESTAMP"* 1> /dev/null 2>&1; then
        print_status "Generated result files:"
        ls -la "$RESULTS_DIR"/*"$TIMESTAMP"* | sed 's/^/  /'
    fi
}

# Function to run specific mode benchmarks
run_mode_benchmark() {
    local mode=$1
    local processor=${2:-""}
    local count=${3:-$DEFAULT_MODULE_COUNT}
    
    print_header "Running $mode${processor:+ + $processor} Mode Benchmark"
    
    # Create results directory
    mkdir -p "$RESULTS_DIR"
    
    generate_projects "$mode" "$processor" "$count"
    run_scenarios "$mode" "$processor"
    
    print_success "$mode${processor:+ + $processor} benchmark completed!"
}

# Function to show usage information
show_usage() {
    echo "Metro vs Anvil Benchmark Runner"
    echo ""
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  all                           Run all benchmark modes (default)"
    echo "  metro [COUNT]                 Run only Metro mode benchmarks"
    echo "  anvil-ksp [COUNT]            Run only Anvil + KSP mode benchmarks"
    echo "  anvil-kapt [COUNT]           Run only Anvil + KAPT mode benchmarks"
    echo "  help                         Show this help message"
    echo ""
    echo "Options:"
    echo "  COUNT                        Number of modules to generate (default: $DEFAULT_MODULE_COUNT)"
    echo ""
    echo "Examples:"
    echo "  $0                           # Run all benchmarks with default settings"
    echo "  $0 all 1000                  # Run all benchmarks with 1000 modules"
    echo "  $0 metro 250                 # Run only Metro benchmarks with 250 modules"
    echo "  $0 anvil-ksp                 # Run only Anvil KSP benchmarks with default count"
    echo ""
    echo "Results will be saved to the '$RESULTS_DIR' directory with timestamps."
}

# Function to validate module count
validate_count() {
    local count=$1
    if ! [[ "$count" =~ ^[0-9]+$ ]] || [ "$count" -lt 10 ] || [ "$count" -gt 10000 ]; then
        print_error "Invalid module count: $count"
        print_error "Count must be a number between 10 and 10000"
        exit 1
    fi
}

# Main script logic
main() {
    # Change to script directory
    cd "$(dirname "$0")"
    
    # Check prerequisites
    check_prerequisites
    
    case "${1:-all}" in
        "all")
            local count=${2:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_all_benchmarks "$count"
            ;;
        "metro")
            local count=${2:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "metro" "" "$count"
            ;;
        "anvil-ksp")
            local count=${2:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "anvil" "ksp" "$count"
            ;;
        "anvil-kapt")
            local count=${2:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "anvil" "kapt" "$count"
            ;;
        "help"|"-h"|"--help")
            show_usage
            ;;
        *)
            print_error "Unknown command: $1"
            echo ""
            show_usage
            exit 1
            ;;
    esac
}

# Execute main function with all arguments
main "$@"