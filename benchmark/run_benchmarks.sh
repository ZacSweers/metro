#!/bin/bash

# Metro vs Anvil Benchmark Runner
# 
# This script automatically regenerates projects for each mode and runs
# the corresponding benchmark scenarios to compare performance.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuration
DEFAULT_MODULE_COUNT=500
RESULTS_DIR="benchmark-results"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Git refs
SINGLE_REF=""
COMPARE_REF1=""
COMPARE_REF2=""
COMPARE_BENCHMARK_TYPE="all"
ORIGINAL_GIT_REF=""
ORIGINAL_GIT_IS_BRANCH=false
# Whether to re-run non-metro modes in ref2 (default: false to save time)
RERUN_NON_METRO=false

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

# Save current git state (branch or commit)
save_git_state() {
    # Check if we're on a branch or in detached HEAD state
    local current_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "")
    if [ -n "$current_branch" ]; then
        ORIGINAL_GIT_REF="$current_branch"
        ORIGINAL_GIT_IS_BRANCH=true
        print_status "Saved current branch: $ORIGINAL_GIT_REF"
    else
        # Detached HEAD - save the commit hash
        ORIGINAL_GIT_REF=$(git rev-parse HEAD)
        ORIGINAL_GIT_IS_BRANCH=false
        print_status "Saved current commit: ${ORIGINAL_GIT_REF:0:12}"
    fi
}

# Restore to original git state
restore_git_state() {
    if [ -z "$ORIGINAL_GIT_REF" ]; then
        print_error "No git state saved to restore"
        return 1
    fi

    print_status "Restoring to original git state..."
    if [ "$ORIGINAL_GIT_IS_BRANCH" = true ]; then
        git checkout "$ORIGINAL_GIT_REF" 2>/dev/null || {
            print_error "Failed to restore to branch: $ORIGINAL_GIT_REF"
            return 1
        }
        print_success "Restored to branch: $ORIGINAL_GIT_REF"
    else
        git checkout "$ORIGINAL_GIT_REF" 2>/dev/null || {
            print_error "Failed to restore to commit: ${ORIGINAL_GIT_REF:0:12}"
            return 1
        }
        print_success "Restored to commit: ${ORIGINAL_GIT_REF:0:12}"
    fi
}

# Checkout a git ref (branch or commit)
checkout_ref() {
    local ref="$1"
    print_status "Checking out: $ref"
    git checkout "$ref" 2>/dev/null || {
        print_error "Failed to checkout: $ref"
        return 1
    }
    local short_ref=$(git rev-parse --short HEAD)
    print_success "Checked out: $ref ($short_ref)"
}

# Get a filesystem-safe name for a git ref
get_ref_safe_name() {
    local ref="$1"
    # Replace slashes and other special chars with underscores
    echo "$ref" | sed 's/[^a-zA-Z0-9._-]/_/g'
}

# Source the gradle-profiler installer script
source "$SCRIPT_DIR/install-gradle-profiler.sh"

# Get the path to gradle-profiler binary
GRADLE_PROFILER_BIN="$(get_gradle_profiler_bin)"

# Function to check if required tools are available
check_prerequisites() {
    print_header "Checking Prerequisites"
    
    local missing_tools=()
    
    if ! command -v kotlin &> /dev/null; then
        missing_tools+=("kotlin")
    fi
    
    # Check for gradle-profiler (either in PATH or in tmp/)
    if ! command -v gradle-profiler &> /dev/null && [ ! -x "$GRADLE_PROFILER_BIN" ]; then
        missing_tools+=("gradle-profiler")
    fi
    
    if ! command -v ./gradlew &> /dev/null; then
        missing_tools+=("gradlew (not executable)")
    fi
    
    if [ ${#missing_tools[@]} -gt 0 ]; then
        print_error "Missing required tools: ${missing_tools[*]}"
        print_error "Please install missing tools and try again"
        print_error "You can run benchmark/install-gradle-profiler.sh to install gradle-profiler from source"
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
    local include_clean_builds=${3:-false}
    
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
        "${scenario_prefix}_plain_abi_change"
        "${scenario_prefix}_plain_non_abi_change"
        "${scenario_prefix}_raw_compilation"
    )
    
    # Add clean build scenario if requested
    if [ "$include_clean_builds" = true ]; then
        scenarios+=("${scenario_prefix}_clean_build")
    fi
    
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

        # Use gradle-profiler from tmp/ if available, otherwise use system one
        local profiler_cmd="gradle-profiler"
        if [ -x "$GRADLE_PROFILER_BIN" ]; then
            profiler_cmd="$GRADLE_PROFILER_BIN"
        fi

        $profiler_cmd \
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
    local include_clean_builds=${2:-false}
    
    print_header "Merging Benchmark Results"
    
    # Define test types
    local test_types=("abi_change" "non_abi_change" "plain_abi_change" "plain_non_abi_change" "raw_compilation")
    
    # Add clean build test type if requested
    if [ "$include_clean_builds" = true ]; then
        test_types+=("clean_build")
    fi
    
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
    local include_clean_builds=${3:-false}
    
    print_header "Metro vs Anvil Benchmark Suite"
    print_status "Module count: $count"
    if [ "$include_clean_builds" = true ]; then
        print_status "Including clean build scenarios"
    fi
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: will run ./gradlew :app:component:run --quiet for each mode"
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
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Metro build completed!"
    else
        run_scenarios "metro" "" "$include_clean_builds"
    fi
    
    # 2. Anvil + KSP Mode  
    if [ "$build_only" = true ]; then
        print_header "Running Anvil + KSP Mode Build"
    else
        print_header "Running Anvil + KSP Mode Benchmarks"
    fi
    generate_projects "anvil" "ksp" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Anvil + KSP build completed!"
    else
        run_scenarios "anvil" "ksp" "$include_clean_builds"
    fi
    
    # 3. Anvil + KAPT Mode
    if [ "$build_only" = true ]; then
        print_header "Running Anvil + KAPT Mode Build"
    else
        print_header "Running Anvil + KAPT Mode Benchmarks"
    fi
    generate_projects "anvil" "kapt" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Anvil + KAPT build completed!"
    else
        run_scenarios "anvil" "kapt" "$include_clean_builds"
    fi
    
    # 4. Kotlin-inject + Anvil Mode
    if [ "$build_only" = true ]; then
        print_header "Running Kotlin-inject + Anvil Mode Build"
    else
        print_header "Running Kotlin-inject + Anvil Mode Benchmarks"
    fi
    generate_projects "kotlin-inject-anvil" "" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Kotlin-inject + Anvil build completed!"
    else
        run_scenarios "kotlin-inject-anvil" "" "$include_clean_builds"
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
        merge_benchmark_results "$TIMESTAMP" "$include_clean_builds"
    fi
}

# Function to run specific mode benchmarks
run_mode_benchmark() {
    local mode=$1
    local processor=${2:-""}
    local count=${3:-$DEFAULT_MODULE_COUNT}
    local build_only=${4:-false}
    local include_clean_builds=${5:-false}
    
    print_header "Running $mode${processor:+ + $processor} Mode Benchmark"
    
    # Create results directory
    mkdir -p "$RESULTS_DIR"
    
    generate_projects "$mode" "$processor" "$count"
    
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "$mode${processor:+ + $processor} build completed!"
    else
        run_scenarios "$mode" "$processor" "$include_clean_builds"
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
    echo "  single                        Run benchmarks on a single git ref"
    echo "  compare                       Compare benchmarks across two git refs"
    echo "  help                         Show this help message"
    echo ""
    echo "Options:"
    echo "  COUNT                        Number of modules to generate (default: $DEFAULT_MODULE_COUNT)"
    echo "  --build-only                 Only run ./gradlew :app:component:run --quiet, skip gradle-profiler"
    echo "  --include-clean-builds       Include clean build scenarios in benchmarks"
    echo ""
    echo "Single Options:"
    echo "  --ref <ref>                  Git ref to benchmark - branch name or commit hash"
    echo "  --modes <list>               Comma-separated list of modes to benchmark"
    echo "                               Available: metro, anvil-ksp, anvil-kapt, kotlin-inject-anvil"
    echo "                               Default: metro,anvil-ksp,kotlin-inject-anvil"
    echo ""
    echo "Compare Options:"
    echo "  --ref1 <ref>                 First git ref (baseline) - branch name or commit hash"
    echo "  --ref2 <ref>                 Second git ref to compare against baseline"
    echo "  --modes <list>               Comma-separated list of modes to benchmark"
    echo "                               Available: metro, anvil-ksp, anvil-kapt, kotlin-inject-anvil"
    echo "                               Default: metro,anvil-ksp,kotlin-inject-anvil"
    echo "  --rerun-non-metro            Re-run non-metro modes on ref2 (default: only run metro on ref2)"
    echo "                               When disabled (default), ref2 uses ref1's non-metro results for comparison"
    echo ""
    echo "Prerequisites:"
    echo "  Run benchmark/install-gradle-profiler.sh to install gradle-profiler from source"
    echo "  Or pass --install-gradle-profiler to install before running benchmarks"
    echo ""
    echo "Examples:"
    echo "  $0                           # Run all benchmarks with default settings"
    echo "  $0 all 1000                  # Run all benchmarks with 1000 modules"
    echo "  $0 metro 250                 # Run only Metro benchmarks with 250 modules"
    echo "  $0 anvil-ksp                 # Run only Anvil KSP benchmarks with default count"
    echo "  $0 metro --build-only        # Generate Metro project and run build only"
    echo "  $0 anvil-ksp 100 --build-only # Generate Anvil KSP project with 100 modules and run build only"
    echo "  $0 all --build-only          # Generate and build all projects, skip benchmarks"
    echo "  $0 all --include-clean-builds # Run all benchmarks including clean build scenarios"
    echo "  $0 metro 250 --include-clean-builds # Run Metro benchmarks with 250 modules including clean builds"
    echo ""
    echo "  # Run benchmarks on a single git ref:"
    echo "  $0 single --ref main"
    echo "  $0 single --ref feature-branch --modes metro,anvil-ksp"
    echo ""
    echo "  # Compare benchmarks across git refs:"
    echo "  $0 compare --ref1 main --ref2 feature-branch"
    echo "  $0 compare --ref1 abc123 --ref2 def456 --modes metro,anvil-ksp"
    echo "  $0 compare --ref1 main --ref2 feature --rerun-non-metro  # Re-run all modes on both refs"
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

# Default modes for comparison
COMPARE_MODES="metro,anvil-ksp,kotlin-inject-anvil"

# Run benchmarks for a specific git ref
# Arguments: ref, ref_label, count, include_clean_builds, modes, is_second_ref
run_benchmarks_for_ref() {
    local ref="$1"
    local ref_label="$2"
    local count="$3"
    local include_clean_builds="$4"
    local modes="$5"
    local is_second_ref="${6:-false}"

    print_header "Running benchmarks for: $ref_label"

    # Checkout the ref
    checkout_ref "$ref" || return 1

    # Create ref-specific results directory
    local ref_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}"
    mkdir -p "$ref_dir"

    # Save the commit hash for reference
    git rev-parse HEAD > "$ref_dir/commit.txt"
    git log -1 --format='%h %s' > "$ref_dir/commit-info.txt"

    # Run benchmarks for each mode
    IFS=',' read -ra MODE_ARRAY <<< "$modes"
    for mode in "${MODE_ARRAY[@]}"; do
        # Skip non-metro modes on second ref unless RERUN_NON_METRO is true
        if [ "$is_second_ref" = true ] && [ "$mode" != "metro" ] && [ "$RERUN_NON_METRO" != true ]; then
            print_status "Skipping $mode for $ref_label (using ref1 results for comparison)"
            continue
        fi

        print_header "Benchmarking $mode for $ref_label"

        case "$mode" in
            "metro")
                generate_projects "metro" "" "$count"
                run_scenarios "metro" "" "$include_clean_builds"
                # Move results to ref-specific directory
                for scenario_dir in "$RESULTS_DIR"/metro_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            "anvil-ksp")
                generate_projects "anvil" "ksp" "$count"
                run_scenarios "anvil" "ksp" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/anvil_ksp_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            "anvil-kapt")
                generate_projects "anvil" "kapt" "$count"
                run_scenarios "anvil" "kapt" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/anvil_kapt_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            "kotlin-inject-anvil")
                generate_projects "kotlin-inject-anvil" "" "$count"
                run_scenarios "kotlin-inject-anvil" "" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/kotlin_inject_anvil_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            *)
                print_warning "Unknown mode: $mode, skipping"
                ;;
        esac
    done

    print_success "Completed benchmarks for $ref_label"
}

# Extract median time from benchmark CSV for a specific test type
extract_median_for_ref() {
    local ref_label="$1"
    local mode_prefix="$2"
    local test_type="$3"

    local csv_file="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/${mode_prefix}_${TIMESTAMP}/${mode_prefix}_${test_type}/benchmark.csv"

    if [ -f "$csv_file" ]; then
        # Extract measured build times (skip header and warm-up builds)
        local times=$(awk -F, '/^measured build/ {print $2}' "$csv_file" | sort -n)

        if [ -z "$times" ]; then
            echo ""
            return
        fi

        # Convert to array and calculate median
        local times_array=($times)
        local count=${#times_array[@]}

        if [ $count -eq 0 ]; then
            echo ""
            return
        fi

        local median_index=$((count / 2))

        if [ $((count % 2)) -eq 1 ]; then
            echo "${times_array[$median_index]}"
        else
            local mid1_index=$((median_index - 1))
            local mid1=${times_array[$mid1_index]}
            local mid2=${times_array[$median_index]}
            echo "scale=2; ($mid1 + $mid2) / 2" | bc 2>/dev/null || echo ""
        fi
    else
        echo ""
    fi
}

# Check if a mode was run for a given ref (by checking if results exist)
mode_was_run_for_ref() {
    local ref_label="$1"
    local mode_prefix="$2"
    local ref_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}"

    # Check if any results exist for this mode
    if ls "$ref_dir"/${mode_prefix}_* 1> /dev/null 2>&1; then
        return 0
    fi
    return 1
}

# Generate comparison summary between two refs
generate_comparison_summary() {
    local ref1_label="$1"
    local ref2_label="$2"
    local modes="$3"

    local summary_file="$RESULTS_DIR/${TIMESTAMP}/comparison-summary.md"
    local ref1_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    local ref2_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Comparison Summary"

    # Determine which modes were actually run on ref2
    local ref2_modes=""
    IFS=',' read -ra MODE_ARRAY <<< "$modes"
    for mode in "${MODE_ARRAY[@]}"; do
        local mode_prefix
        case "$mode" in
            "metro") mode_prefix="metro" ;;
            "anvil-ksp") mode_prefix="anvil_ksp" ;;
            "anvil-kapt") mode_prefix="anvil_kapt" ;;
            "kotlin-inject-anvil") mode_prefix="kotlin_inject_anvil" ;;
            *) continue ;;
        esac
        if mode_was_run_for_ref "$ref2_label" "$mode_prefix"; then
            if [ -n "$ref2_modes" ]; then
                ref2_modes="${ref2_modes},"
            fi
            ref2_modes="${ref2_modes}${mode}"
        fi
    done

    cat > "$summary_file" << EOF
# Benchmark Comparison: $ref1_label vs $ref2_label

**Date:** $(date)
**Module Count:** $DEFAULT_MODULE_COUNT
**Modes benchmarked on ref1:** $modes
**Modes benchmarked on ref2:** ${ref2_modes:-metro}

## Git Refs

| Ref | Commit |
|-----|--------|
| $ref1_label (baseline) | $ref1_commit |
| $ref2_label | $ref2_commit |

EOF

    # Test types to compare
    local test_types=("abi_change" "non_abi_change" "plain_abi_change" "plain_non_abi_change" "raw_compilation")
    local test_names=("ABI Change" "Non-ABI Change" "Plain Kotlin ABI" "Plain Kotlin Non-ABI" "Graph Processing")

    for i in "${!test_types[@]}"; do
        local test_type="${test_types[$i]}"
        local test_name="${test_names[$i]}"

        cat >> "$summary_file" << EOF
## $test_name

| Framework | $ref1_label (baseline) | $ref2_label | Difference |
|-----------|------------------------|-------------|------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local mode_prefix
            case "$mode" in
                "metro") mode_prefix="metro" ;;
                "anvil-ksp") mode_prefix="anvil_ksp" ;;
                "anvil-kapt") mode_prefix="anvil_kapt" ;;
                "kotlin-inject-anvil") mode_prefix="kotlin_inject_anvil" ;;
                *) continue ;;
            esac

            local score1=$(extract_median_for_ref "$ref1_label" "$mode_prefix" "$test_type")

            # Check if this mode was run on ref2
            local mode_ran_on_ref2=false
            if mode_was_run_for_ref "$ref2_label" "$mode_prefix"; then
                mode_ran_on_ref2=true
            fi

            local score2=""
            if [ "$mode_ran_on_ref2" = true ]; then
                score2=$(extract_median_for_ref "$ref2_label" "$mode_prefix" "$test_type")
            fi

            local display1="N/A"
            local display2="N/A"
            local diff="-"

            if [ -n "$score1" ]; then
                local secs1=$(echo "scale=1; $score1 / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs1" ]; then
                    display1="${secs1}s"
                fi
            fi

            if [ "$mode_ran_on_ref2" = true ]; then
                if [ -n "$score2" ]; then
                    local secs2=$(echo "scale=1; $score2 / 1000" | bc 2>/dev/null || echo "")
                    if [ -n "$secs2" ]; then
                        display2="${secs2}s"
                    fi
                fi

                if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                    local pct=$(echo "scale=2; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null || echo "")
                    if [ -n "$pct" ]; then
                        # Check if negative (faster)
                        if [[ "$pct" == -* ]]; then
                            diff="${pct}%"
                        elif [[ "$pct" == "0" ]] || [[ "$pct" == "0.00" ]] || [[ "$pct" == ".00" ]]; then
                            diff="+0.00% (no change)"
                        else
                            diff="+${pct}%"
                        fi
                    fi
                fi
            else
                # Mode was not run on ref2 - show ref1 value as reference
                display2="(not run)"
                diff="n/a"
            fi

            echo "| $mode | $display1 | $display2 | $diff |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    done

    cat >> "$summary_file" << EOF
## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`${ref1_label}/\` - Results for baseline ($ref1_commit)
- \`${ref2_label}/\` - Results for comparison ($ref2_commit)
EOF

    print_success "Comparison summary saved to $summary_file"
    echo ""
    cat "$summary_file"
}

# Generate summary for single ref benchmarks
generate_single_summary() {
    local ref_label="$1"
    local modes="$2"

    local summary_file="$RESULTS_DIR/${TIMESTAMP}/single-summary.md"
    local ref_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Single Ref Summary"

    cat > "$summary_file" << EOF
# Benchmark Results: $ref_label

**Date:** $(date)
**Module Count:** $DEFAULT_MODULE_COUNT
**Modes:** $modes
**Commit:** $ref_commit

EOF

    # Test types to show
    local test_types=("abi_change" "non_abi_change" "plain_abi_change" "plain_non_abi_change" "raw_compilation")
    local test_names=("ABI Change" "Non-ABI Change" "Plain Kotlin ABI" "Plain Kotlin Non-ABI" "Graph Processing")

    IFS=',' read -ra MODE_ARRAY <<< "$modes"

    for i in "${!test_types[@]}"; do
        local test_type="${test_types[$i]}"
        local test_name="${test_names[$i]}"

        cat >> "$summary_file" << EOF
## $test_name

| Framework | Time |
|-----------|------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local mode_prefix
            case "$mode" in
                "metro") mode_prefix="metro" ;;
                "anvil-ksp") mode_prefix="anvil_ksp" ;;
                "anvil-kapt") mode_prefix="anvil_kapt" ;;
                "kotlin-inject-anvil") mode_prefix="kotlin_inject_anvil" ;;
                *) continue ;;
            esac

            local score=$(extract_median_for_ref "$ref_label" "$mode_prefix" "$test_type")

            local display="N/A"
            if [ -n "$score" ]; then
                local secs=$(echo "scale=1; $score / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs" ]; then
                    display="${secs}s"
                fi
            fi

            echo "| $mode | $display |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    done

    cat >> "$summary_file" << EOF
## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`${ref_label}/\` - Results ($ref_commit)
EOF

    print_success "Summary saved to $summary_file"
    echo ""
    cat "$summary_file"
}

# Run single ref command
run_single() {
    local count="${1:-$DEFAULT_MODULE_COUNT}"
    local include_clean_builds="${2:-false}"

    if [ -z "$SINGLE_REF" ]; then
        print_error "Single requires --ref argument"
        show_usage
        exit 1
    fi

    # Validate ref exists
    if ! git rev-parse --verify "$SINGLE_REF" > /dev/null 2>&1; then
        print_error "Invalid git ref: $SINGLE_REF"
        exit 1
    fi

    # Check for uncommitted changes
    if ! git diff-index --quiet HEAD -- 2>/dev/null; then
        print_error "You have uncommitted changes. Please commit or stash them before running benchmarks."
        exit 1
    fi

    print_header "Running Benchmarks on Single Git Ref"
    print_status "Ref: $SINGLE_REF"
    print_status "Modes: $COMPARE_MODES"
    print_status "Module count: $count"
    echo ""

    # Save current git state
    save_git_state

    # Create safe label for directory name
    local ref_label=$(get_ref_safe_name "$SINGLE_REF")

    # Create results directory
    mkdir -p "$RESULTS_DIR/${TIMESTAMP}"

    # Set up trap to restore git state on exit
    trap 'restore_git_state' EXIT

    # Run benchmarks for the ref (all modes, not second ref)
    run_benchmarks_for_ref "$SINGLE_REF" "$ref_label" "$count" "$include_clean_builds" "$COMPARE_MODES" false || {
        print_error "Failed to run benchmarks for $SINGLE_REF"
        exit 1
    }

    # Generate summary
    generate_single_summary "$ref_label" "$COMPARE_MODES"

    print_header "Benchmarks Complete"
    echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
    echo ""
}

# Run compare command
run_compare() {
    local count="${1:-$DEFAULT_MODULE_COUNT}"
    local include_clean_builds="${2:-false}"

    if [ -z "$COMPARE_REF1" ] || [ -z "$COMPARE_REF2" ]; then
        print_error "Compare requires both --ref1 and --ref2 arguments"
        show_usage
        exit 1
    fi

    # Validate refs exist
    if ! git rev-parse --verify "$COMPARE_REF1" > /dev/null 2>&1; then
        print_error "Invalid git ref: $COMPARE_REF1"
        exit 1
    fi
    if ! git rev-parse --verify "$COMPARE_REF2" > /dev/null 2>&1; then
        print_error "Invalid git ref: $COMPARE_REF2"
        exit 1
    fi

    # Check for uncommitted changes
    if ! git diff-index --quiet HEAD -- 2>/dev/null; then
        print_error "You have uncommitted changes. Please commit or stash them before comparing."
        exit 1
    fi

    print_header "Comparing Benchmarks Across Git Refs"
    print_status "Baseline (ref1): $COMPARE_REF1"
    print_status "Compare (ref2):  $COMPARE_REF2"
    print_status "Modes:           $COMPARE_MODES"
    print_status "Module count:    $count"
    if [ "$RERUN_NON_METRO" = true ]; then
        print_status "Re-run non-metro on ref2: yes"
    else
        print_status "Re-run non-metro on ref2: no (using ref1 results)"
    fi
    echo ""

    # Save current git state
    save_git_state

    # Create safe labels for directory names
    local ref1_label=$(get_ref_safe_name "$COMPARE_REF1")
    local ref2_label=$(get_ref_safe_name "$COMPARE_REF2")

    # Ensure unique labels if they resolve to the same name
    if [ "$ref1_label" = "$ref2_label" ]; then
        ref1_label="${ref1_label}_base"
        ref2_label="${ref2_label}_compare"
    fi

    # Create results directory
    mkdir -p "$RESULTS_DIR/${TIMESTAMP}"

    # Set up trap to restore git state on exit
    trap 'restore_git_state' EXIT

    # Run benchmarks for ref1 (baseline) - run all modes
    run_benchmarks_for_ref "$COMPARE_REF1" "$ref1_label" "$count" "$include_clean_builds" "$COMPARE_MODES" false || {
        print_error "Failed to run benchmarks for $COMPARE_REF1"
        exit 1
    }

    # Run benchmarks for ref2 - only metro by default (is_second_ref=true)
    run_benchmarks_for_ref "$COMPARE_REF2" "$ref2_label" "$count" "$include_clean_builds" "$COMPARE_MODES" true || {
        print_error "Failed to run benchmarks for $COMPARE_REF2"
        exit 1
    }

    # Generate comparison summary
    generate_comparison_summary "$ref1_label" "$ref2_label" "$COMPARE_MODES"

    print_header "Comparison Complete"
    echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
    echo ""
}

# Main script logic
main() {
    # Change to script directory
    cd "$(dirname "$0")"

    local command="${1:-all}"
    shift || true

    local build_only=false
    local include_clean_builds=false
    local install_profiler=false
    local count="$DEFAULT_MODULE_COUNT"

    # Parse options
    while [[ $# -gt 0 ]]; do
        case $1 in
            --build-only)
                build_only=true
                shift
                ;;
            --include-clean-builds)
                include_clean_builds=true
                shift
                ;;
            --install-gradle-profiler)
                install_profiler=true
                shift
                ;;
            --ref)
                SINGLE_REF="$2"
                shift 2
                ;;
            --ref1)
                COMPARE_REF1="$2"
                shift 2
                ;;
            --ref2)
                COMPARE_REF2="$2"
                shift 2
                ;;
            --modes)
                COMPARE_MODES="$2"
                shift 2
                ;;
            --rerun-non-metro)
                RERUN_NON_METRO=true
                shift
                ;;
            [0-9]*)
                # Positional count argument
                count="$1"
                shift
                ;;
            *)
                print_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done

    # Install gradle-profiler if requested
    if [ "$install_profiler" = true ]; then
        if ! install_gradle_profiler; then
            print_error "Failed to install gradle-profiler"
            exit 1
        fi
    fi

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

    validate_count "$count"

    case "$command" in
        all)
            run_all_benchmarks "$count" "$build_only" "$include_clean_builds"
            ;;
        metro)
            run_mode_benchmark "metro" "" "$count" "$build_only" "$include_clean_builds"
            ;;
        anvil-ksp)
            run_mode_benchmark "anvil" "ksp" "$count" "$build_only" "$include_clean_builds"
            ;;
        anvil-kapt)
            run_mode_benchmark "anvil" "kapt" "$count" "$build_only" "$include_clean_builds"
            ;;
        kotlin-inject-anvil)
            run_mode_benchmark "kotlin-inject-anvil" "" "$count" "$build_only" "$include_clean_builds"
            ;;
        single)
            run_single "$count" "$include_clean_builds"
            ;;
        compare)
            run_compare "$count" "$include_clean_builds"
            ;;
        help|-h|--help)
            show_usage
            ;;
        *)
            print_error "Unknown command: $command"
            echo ""
            show_usage
            exit 1
            ;;
    esac
}

# Execute main function with all arguments
main "$@"