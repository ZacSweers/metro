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
        kotlin generate-projects.main.kts --mode "ANVIL" --processor "$(echo $processor | tr '[:lower:]' '[:upper:]')" --count "$count"
    elif [ "$mode" = "kotlin-inject-anvil" ]; then
        kotlin generate-projects.main.kts --mode "KOTLIN_INJECT_ANVIL" --count "$count"
    else
        kotlin generate-projects.main.kts --mode "$(echo $mode | tr '[:lower:]' '[:upper:]')" --count "$count"
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
    local mode_name
    if [ "$mode" = "metro" ]; then
        scenario_prefix="metro"
        mode_name="metro"
    elif [ "$mode" = "anvil" ] && [ "$processor" = "ksp" ]; then
        scenario_prefix="anvil_ksp"
        mode_name="anvil_ksp"
    elif [ "$mode" = "anvil" ] && [ "$processor" = "kapt" ]; then
        scenario_prefix="anvil_kapt"
        mode_name="anvil_kapt"
    elif [ "$mode" = "kotlin-inject-anvil" ]; then
        scenario_prefix="kotlin_inject_anvil"
        mode_name="kotlin_inject_anvil"
    else
        print_error "Invalid mode/processor combination: $mode/$processor"
        exit 1
    fi
    
    local scenarios=(
        "${scenario_prefix}_abi_change"
        "${scenario_prefix}_non_abi_change" 
        "${scenario_prefix}_raw_compilation"
    )
    
    # Create mode-specific results directory to avoid overwrites
    local mode_results_dir="$RESULTS_DIR/${mode_name}_${TIMESTAMP}"
    mkdir -p "$mode_results_dir"
    
    print_status "Running scenarios for $mode${processor:+ with $processor}: ${scenarios[*]}"
    print_status "Results will be saved to: $mode_results_dir"
    
    # Run each scenario individually to avoid overwriting results
    for scenario in "${scenarios[@]}"; do
        local scenario_output_dir="$mode_results_dir/$scenario"
        mkdir -p "$scenario_output_dir"
        
        print_status "Running scenario: $scenario"
        
        gradle-profiler \
            --benchmark \
            --scenario-file benchmark.scenarios \
            --output-dir "$scenario_output_dir" \
            --gradle-user-home ~/.gradle \
            "$scenario" \
            || {
                print_error "Benchmark failed for scenario $scenario in $mode mode"
                return 1
            }
        
        print_success "Completed scenario: $scenario"
    done
    
    print_success "All scenarios completed for $mode mode"
}

# Function to merge benchmark results
merge_benchmark_results() {
    local timestamp=$1
    
    print_header "Merging Benchmark Results"
    
    # Define test types
    local test_types=("abi_change" "non_abi_change" "raw_compilation")
    
    for test_type in "${test_types[@]}"; do
        print_status "Checking for $test_type results to merge"
        
        # Check if we have multiple mode directories for this timestamp
        local mode_count=0
        for mode_dir in "$RESULTS_DIR"/*"$timestamp"; do
            # Look for scenario subdirectories with the test type
            if [ -d "$mode_dir" ]; then
                for scenario_dir in "$mode_dir"/*"$test_type"; do
                    if [ -d "$scenario_dir" ] && [ -f "$scenario_dir/benchmark.html" ]; then
                        ((mode_count++))
                        break  # Only count each mode once per test type
                    fi
                done
            fi
        done
        
        if [ $mode_count -gt 1 ]; then
            print_status "Merging $test_type results from $mode_count modes"
            
            if ./merge_benchmarks.sh "$test_type" "$timestamp" "$RESULTS_DIR"; then
                print_success "Successfully merged $test_type results"
            else
                print_warning "Failed to merge $test_type results"
            fi
        else
            print_warning "Not enough modes to merge for $test_type (found $mode_count)"
        fi
    done
}

# Function to run all benchmarks
run_all_benchmarks() {
    local count=${1:-$DEFAULT_MODULE_COUNT}
    local build_only=${2:-false}
    
    print_header "Metro vs Anvil Benchmark Suite"
    print_status "Module count: $count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: will run ./gradlew :app:component:run for each mode"
    else
        print_status "Results directory: $RESULTS_DIR"
        print_status "Timestamp: $TIMESTAMP"
        
        # Wipe existing results directory if present
        if [ -d "$RESULTS_DIR" ]; then
            print_status "Wiping existing results directory"
            rm -rf "$RESULTS_DIR"
        fi
        
        # Create results directory
        mkdir -p "$RESULTS_DIR"
    fi
    
    # 1. Metro Mode
    if [ "$build_only" = true ]; then
        print_header "Running Metro Mode Build"
    else
        print_header "Running Metro Mode Benchmarks"
    fi
    generate_projects "metro" "" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run"
        ./gradlew :app:component:run
        print_success "Metro build completed!"
    else
        run_scenarios "metro"
    fi
    
    # 2. Anvil + KSP Mode  
    if [ "$build_only" = true ]; then
        print_header "Running Anvil + KSP Mode Build"
    else
        print_header "Running Anvil + KSP Mode Benchmarks"
    fi
    generate_projects "anvil" "ksp" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run"
        ./gradlew :app:component:run
        print_success "Anvil + KSP build completed!"
    else
        run_scenarios "anvil" "ksp"
    fi
    
    # 3. Anvil + KAPT Mode
    if [ "$build_only" = true ]; then
        print_header "Running Anvil + KAPT Mode Build"
    else
        print_header "Running Anvil + KAPT Mode Benchmarks"
    fi
    generate_projects "anvil" "kapt" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run"
        ./gradlew :app:component:run
        print_success "Anvil + KAPT build completed!"
    else
        run_scenarios "anvil" "kapt"
    fi
    
    # 4. Kotlin-inject + Anvil Mode
    if [ "$build_only" = true ]; then
        print_header "Running Kotlin-inject + Anvil Mode Build"
    else
        print_header "Running Kotlin-inject + Anvil Mode Benchmarks"
    fi
    generate_projects "kotlin-inject-anvil" "" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run"
        ./gradlew :app:component:run
        print_success "Kotlin-inject + Anvil build completed!"
    else
        run_scenarios "kotlin-inject-anvil"
    fi
    
    if [ "$build_only" = true ]; then
        print_header "All Builds Complete"
        print_success "All builds completed successfully!"
    else
        print_header "Benchmark Suite Complete"
        print_success "All benchmarks completed successfully!"
        print_status "Results are available in: $RESULTS_DIR"
        
        # List generated result files
        if ls "$RESULTS_DIR"/*"$TIMESTAMP"* 1> /dev/null 2>&1; then
            print_status "Generated result files:"
            ls -la "$RESULTS_DIR"/*"$TIMESTAMP"* | sed 's/^/  /'
        fi
        
        # Merge results across modes
        merge_benchmark_results "$TIMESTAMP"
    fi
}

# Function to run specific mode benchmarks
run_mode_benchmark() {
    local mode=$1
    local processor=${2:-""}
    local count=${3:-$DEFAULT_MODULE_COUNT}
    local build_only=${4:-false}
    
    print_header "Running $mode${processor:+ + $processor} Mode Benchmark"
    
    # Create results directory
    mkdir -p "$RESULTS_DIR"
    
    generate_projects "$mode" "$processor" "$count"
    
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run"
        ./gradlew :app:component:run
        print_success "$mode${processor:+ + $processor} build completed!"
    else
        run_scenarios "$mode" "$processor"
        print_success "$mode${processor:+ + $processor} benchmark completed!"
        ./generate_performance_summary.sh "${TIMESTAMP}" "$RESULTS_DIR"
    fi
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
    echo "  kotlin-inject-anvil [COUNT]  Run only Kotlin-inject + Anvil mode benchmarks"
    echo "  help                         Show this help message"
    echo ""
    echo "Options:"
    echo "  COUNT                        Number of modules to generate (default: $DEFAULT_MODULE_COUNT)"
    echo "  --build-only                 Only run ./gradlew :app:component:run, skip gradle-profiler"
    echo ""
    echo "Examples:"
    echo "  $0                           # Run all benchmarks with default settings"
    echo "  $0 all 1000                  # Run all benchmarks with 1000 modules"
    echo "  $0 metro 250                 # Run only Metro benchmarks with 250 modules"
    echo "  $0 anvil-ksp                 # Run only Anvil KSP benchmarks with default count"
    echo "  $0 metro --build-only        # Generate Metro project and run build only"
    echo "  $0 anvil-ksp 100 --build-only # Generate Anvil KSP project with 100 modules and run build only"
    echo "  $0 all --build-only          # Generate and build all projects, skip benchmarks"
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

# Function to parse arguments and handle --build-only flag
parse_args() {
    local args=("$@")
    local parsed_args=()
    local build_only=false
    
    for arg in "${args[@]}"; do
        if [ "$arg" = "--build-only" ]; then
            build_only=true
        else
            parsed_args+=("$arg")
        fi
    done
    
    echo "$build_only"
    printf '%s\n' "${parsed_args[@]}"
}

# Main script logic
main() {
    # Change to script directory
    cd "$(dirname "$0")"
    
    # Parse arguments to extract --build-only flag
    local parsed_output
    parsed_output=$(parse_args "$@")
    local build_only
    build_only=$(echo "$parsed_output" | head -n1)
    local args
    readarray -t args < <(echo "$parsed_output" | tail -n+2)
    
    # Check prerequisites (skip gradle-profiler check if build-only mode)
    if [ "$build_only" = true ]; then
        print_header "Checking Prerequisites (Build-only mode)"
        
        local missing_tools=()
        
        if ! command -v kotlin &> /dev/null; then
            missing_tools+=("kotlin")
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
    else
        check_prerequisites
    fi
    
    case "${args[0]:-all}" in
        "all")
            local count=${args[1]:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_all_benchmarks "$count" "$build_only"
            ;;
        "metro")
            local count=${args[1]:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "metro" "" "$count" "$build_only"
            ;;
        "anvil-ksp")
            local count=${args[1]:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "anvil" "ksp" "$count" "$build_only"
            ;;
        "anvil-kapt")
            local count=${args[1]:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "anvil" "kapt" "$count" "$build_only"
            ;;
        "kotlin-inject-anvil")
            local count=${args[1]:-$DEFAULT_MODULE_COUNT}
            validate_count "$count"
            run_mode_benchmark "kotlin-inject-anvil" "" "$count" "$build_only"
            ;;
        "help"|"-h"|"--help")
            show_usage
            ;;
        *)
            print_error "Unknown command: ${args[0]}"
            echo ""
            show_usage
            exit 1
            ;;
    esac
}

# Execute main function with all arguments
main "$@"