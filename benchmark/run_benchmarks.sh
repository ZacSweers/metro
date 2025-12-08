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

# Collect build environment metadata and save to JSON file
collect_build_metadata() {
    local output_dir="$1"
    local metadata_file="$output_dir/build-metadata.json"

    print_status "Collecting build environment metadata..."

    # Get repo root for libs.versions.toml
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local versions_file="$repo_root/gradle/libs.versions.toml"

    # Helper to extract version from libs.versions.toml
    get_version() {
        local key="$1"
        grep "^${key} = " "$versions_file" 2>/dev/null | sed 's/.*= *"\([^"]*\)".*/\1/' | head -1
    }

    # Git info
    local git_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "detached")
    local git_sha=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
    local git_sha_short=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")

    # Versions from libs.versions.toml
    local kotlin_version=$(get_version "kotlin")
    local dagger_version=$(get_version "dagger")
    local ksp_version=$(get_version "ksp")
    local kotlin_inject_version=$(get_version "kotlinInject")
    local anvil_version=$(get_version "anvil")
    local kotlin_inject_anvil_version=$(get_version "kotlinInject-anvil")
    local jvm_target=$(get_version "jvmTarget")
    local jdk_version=$(get_version "jdk")

    # Gradle version
    local gradle_version=$("$repo_root/gradlew" --version 2>/dev/null | grep "^Gradle " | awk '{print $2}' || echo "unknown")

    # Gradle-profiler version (check if built from source)
    local profiler_version="unknown"
    local profiler_sha=""
    local profiler_source_dir="$repo_root/tmp/gradle-profiler-source"
    if [ -d "$profiler_source_dir/.git" ]; then
        profiler_sha=$(cd "$profiler_source_dir" && git rev-parse --short HEAD 2>/dev/null || echo "")
        profiler_version="source ($profiler_sha)"
    elif command -v gradle-profiler &> /dev/null; then
        profiler_version=$(gradle-profiler --version 2>/dev/null | head -1 || echo "unknown")
    fi

    # JDK info
    local java_version=$(java -version 2>&1 | head -1 | sed 's/.*"\([^"]*\)".*/\1/' || echo "unknown")
    local java_home_info=$(java -XshowSettings:properties -version 2>&1 | grep "java.home" | awk '{print $NF}' || echo "unknown")

    # System info
    local cpu_info=""
    local ram_info=""
    local os_info=$(uname -s 2>/dev/null || echo "unknown")

    if [ "$os_info" = "Darwin" ]; then
        cpu_info=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo "unknown")
        ram_info=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0f GB", $1/1024/1024/1024}' || echo "unknown")
    elif [ "$os_info" = "Linux" ]; then
        cpu_info=$(grep "model name" /proc/cpuinfo 2>/dev/null | head -1 | cut -d: -f2 | xargs || echo "unknown")
        ram_info=$(free -h 2>/dev/null | awk '/^Mem:/ {print $2}' || echo "unknown")
    fi

    # Gradle daemon JVM args (from gradle.properties or default)
    local daemon_jvm_args=""
    if [ -f "$repo_root/gradle.properties" ]; then
        daemon_jvm_args=$(grep "org.gradle.jvmargs" "$repo_root/gradle.properties" 2>/dev/null | cut -d= -f2- || echo "")
    fi

    # Write JSON
    cat > "$metadata_file" << EOF
{
  "git": {
    "branch": "$git_branch",
    "sha": "$git_sha",
    "shaShort": "$git_sha_short"
  },
  "versions": {
    "kotlin": "$kotlin_version",
    "dagger": "$dagger_version",
    "ksp": "$ksp_version",
    "kotlinInject": "$kotlin_inject_version",
    "anvil": "$anvil_version",
    "kotlinInjectAnvil": "$kotlin_inject_anvil_version"
  },
  "build": {
    "gradle": "$gradle_version",
    "gradleProfiler": "$profiler_version",
    "jdk": "$java_version",
    "jvmTarget": "$jvm_target"
  },
  "system": {
    "os": "$os_info",
    "cpu": "$cpu_info",
    "ram": "$ram_info",
    "daemonJvmArgs": "$daemon_jvm_args"
  },
  "timestamp": "$(date -Iseconds)"
}
EOF

    print_success "Build metadata saved to $metadata_file"
}

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
    if [ "$mode" = "dagger" ]; then
        print_status "Using $processor processor"
        kotlin generate-projects.main.kts --mode "DAGGER" --processor "$(echo $processor | tr '[:lower:]' '[:upper:]')" --count "$count"
    elif [ "$mode" = "kotlin-inject-anvil" ]; then
        kotlin generate-projects.main.kts --mode "KOTLIN_INJECT_ANVIL" --count "$count"
    elif [ "$mode" = "noop" ]; then
        kotlin generate-projects.main.kts --mode "NOOP" --count "$count"
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
    elif [ "$mode" = "noop" ]; then
        scenario_prefix="noop"
        mode_name="noop"
    elif [ "$mode" = "dagger" ] && [ "$processor" = "ksp" ]; then
        scenario_prefix="dagger_ksp"
        mode_name="dagger_ksp"
    elif [ "$mode" = "dagger" ] && [ "$processor" = "kapt" ]; then
        scenario_prefix="dagger_kapt"
        mode_name="dagger_kapt"
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
    
    # 2. Dagger (KSP) Mode
    if [ "$build_only" = true ]; then
        print_header "Running Dagger (KSP) Mode Build"
    else
        print_header "Running Dagger (KSP) Mode Benchmarks"
    fi
    generate_projects "dagger" "ksp" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Dagger (KSP) build completed!"
    else
        run_scenarios "dagger" "ksp" "$include_clean_builds"
    fi

    # 3. Dagger (KAPT) Mode
    if [ "$build_only" = true ]; then
        print_header "Running Dagger (KAPT) Mode Build"
    else
        print_header "Running Dagger (KAPT) Mode Benchmarks"
    fi
    generate_projects "dagger" "kapt" "$count"
    if [ "$build_only" = true ]; then
        print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
        ./gradlew :app:component:run --quiet
        print_success "Dagger (KAPT) build completed!"
    else
        run_scenarios "dagger" "kapt" "$include_clean_builds"
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
    echo "  noop [COUNT]                  Run only NOOP mode benchmarks (baseline, no compiler plugin)"
    echo "  dagger-ksp [COUNT]           Run only Dagger (KSP) mode benchmarks"
    echo "  dagger-kapt [COUNT]          Run only Dagger (KAPT) mode benchmarks"
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
    echo "                               Available: metro, dagger-ksp, dagger-kapt, kotlin-inject-anvil"
    echo "                               Default: metro,dagger-ksp,kotlin-inject-anvil"
    echo ""
    echo "Compare Options:"
    echo "  --ref1 <ref>                 First git ref (baseline) - branch name or commit hash"
    echo "  --ref2 <ref>                 Second git ref to compare against baseline"
    echo "  --modes <list>               Comma-separated list of modes to benchmark"
    echo "                               Available: metro, dagger-ksp, dagger-kapt, kotlin-inject-anvil"
    echo "                               Default: metro,dagger-ksp,kotlin-inject-anvil"
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
    echo "  $0 noop 500                  # Run NOOP baseline benchmarks (no compiler plugin)"
    echo "  $0 dagger-ksp                # Run only Dagger (KSP) benchmarks with default count"
    echo "  $0 metro --build-only        # Generate Metro project and run build only"
    echo "  $0 dagger-ksp 100 --build-only # Generate Dagger (KSP) project with 100 modules and run build only"
    echo "  $0 all --build-only          # Generate and build all projects, skip benchmarks"
    echo "  $0 all --include-clean-builds # Run all benchmarks including clean build scenarios"
    echo "  $0 metro 250 --include-clean-builds # Run Metro benchmarks with 250 modules including clean builds"
    echo ""
    echo "  # Run benchmarks on a single git ref:"
    echo "  $0 single --ref main"
    echo "  $0 single --ref feature-branch --modes metro,dagger-ksp"
    echo ""
    echo "  # Compare benchmarks across git refs:"
    echo "  $0 compare --ref1 main --ref2 feature-branch"
    echo "  $0 compare --ref1 abc123 --ref2 def456 --modes metro,dagger-ksp"
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
            "noop")
                generate_projects "noop" "" "$count"
                run_scenarios "noop" "" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/noop_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            "dagger-ksp")
                generate_projects "dagger" "ksp" "$count"
                run_scenarios "dagger" "ksp" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/dagger_ksp_*"$TIMESTAMP"*; do
                    if [ -d "$scenario_dir" ]; then
                        mv "$scenario_dir" "$ref_dir/" 2>/dev/null || true
                    fi
                done
                ;;
            "dagger-kapt")
                generate_projects "dagger" "kapt" "$count"
                run_scenarios "dagger" "kapt" "$include_clean_builds"
                for scenario_dir in "$RESULTS_DIR"/dagger_kapt_*"$TIMESTAMP"*; do
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
            "noop") mode_prefix="noop" ;;
            "dagger-ksp") mode_prefix="dagger_ksp" ;;
            "dagger-kapt") mode_prefix="dagger_kapt" ;;
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

        # Get metro scores for this test type to use as baseline for "vs Metro" column
        local metro_score1=$(extract_median_for_ref "$ref1_label" "metro" "$test_type")
        local metro_score2=""
        if mode_was_run_for_ref "$ref2_label" "metro"; then
            metro_score2=$(extract_median_for_ref "$ref2_label" "metro" "$test_type")
        fi

        cat >> "$summary_file" << EOF
## $test_name

| Framework | $ref1_label | vs Metro | $ref2_label | vs Metro | Difference |
|-----------|-------------|----------|-------------|----------|------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local mode_prefix
            case "$mode" in
                "metro") mode_prefix="metro" ;;
                "noop") mode_prefix="noop" ;;
                "dagger-ksp") mode_prefix="dagger_ksp" ;;
                "dagger-kapt") mode_prefix="dagger_kapt" ;;
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
            local vs_metro1="—"
            local vs_metro2="—"
            local diff="-"

            if [ -n "$score1" ]; then
                local secs1=$(echo "scale=1; $score1 / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs1" ]; then
                    display1="${secs1}s"
                fi
                # Calculate vs Metro for ref1
                if [ "$mode" = "metro" ]; then
                    vs_metro1="baseline"
                elif [ -n "$metro_score1" ] && [ "$metro_score1" != "0" ]; then
                    local pct1=$(echo "scale=1; (($score1 - $metro_score1) / $metro_score1) * 100" | bc 2>/dev/null | sed 's/\.0$//' || echo "")
                    local mult1=$(echo "scale=1; $score1 / $metro_score1" | bc 2>/dev/null || echo "")
                    if [ -n "$pct1" ] && [ -n "$mult1" ]; then
                        vs_metro1="+${pct1}% (${mult1}x)"
                    fi
                fi
            fi

            if [ "$mode_ran_on_ref2" = true ]; then
                if [ -n "$score2" ]; then
                    local secs2=$(echo "scale=1; $score2 / 1000" | bc 2>/dev/null || echo "")
                    if [ -n "$secs2" ]; then
                        display2="${secs2}s"
                    fi
                    # Calculate vs Metro for ref2
                    if [ "$mode" = "metro" ]; then
                        vs_metro2="baseline"
                    elif [ -n "$metro_score2" ] && [ "$metro_score2" != "0" ]; then
                        local pct2=$(echo "scale=1; (($score2 - $metro_score2) / $metro_score2) * 100" | bc 2>/dev/null | sed 's/\.0$//' || echo "")
                        local mult2=$(echo "scale=1; $score2 / $metro_score2" | bc 2>/dev/null || echo "")
                        if [ -n "$pct2" ] && [ -n "$mult2" ]; then
                            vs_metro2="+${pct2}% (${mult2}x)"
                        fi
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

            echo "| $mode | $display1 | $vs_metro1 | $display2 | $vs_metro2 | $diff |" >> "$summary_file"
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

    # Generate HTML report
    generate_html_report "$ref1_label" "$ref2_label" "$modes"
}

# Generate HTML report for benchmarks
generate_html_report() {
    local ref1_label="$1"
    local ref2_label="${2:-}"
    local modes="$3"

    local html_file="$RESULTS_DIR/${TIMESTAMP}/benchmark-report.html"

    print_header "Generating HTML Report"

    # Build JSON data
    local json_data
    json_data=$(build_benchmark_json "$ref1_label" "$ref2_label" "$modes")

    # Generate HTML
    cat > "$html_file" << 'HTMLHEAD'
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Metro Benchmark Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root { --metro-color: #4CAF50; --noop-color: #607D8B; --dagger-ksp-color: #2196F3; --dagger-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; }
        * { box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 0; background: #f5f5f5; color: #333; }
        .header { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: white; padding: 2rem; text-align: center; }
        .header h1 { margin: 0 0 0.5rem 0; font-weight: 300; font-size: 2rem; }
        .header .subtitle { opacity: 0.8; font-size: 0.9rem; }
        .container { max-width: 1400px; margin: 0 auto; padding: 2rem; }
        .refs-info { display: flex; gap: 2rem; margin-bottom: 2rem; flex-wrap: wrap; }
        .ref-card { background: white; border-radius: 8px; padding: 1rem 1.5rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); flex: 1; min-width: 250px; }
        .ref-card.baseline { border-left: 4px solid var(--metro-color); }
        .ref-card.comparison { border-left: 4px solid var(--dagger-ksp-color); }
        .ref-card h3 { margin: 0 0 0.5rem 0; font-size: 0.85rem; text-transform: uppercase; color: #666; }
        .ref-card .ref-name { font-size: 1.2rem; font-weight: 600; font-family: monospace; }
        .ref-card .commit { font-size: 0.85rem; color: #888; margin-top: 0.25rem; }
        .benchmark-section { background: white; border-radius: 8px; padding: 1.5rem; margin-bottom: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .benchmark-section h2 { margin: 0 0 0.25rem 0; font-size: 1.3rem; font-weight: 500; }
        .benchmark-section .chart-hint { font-size: 0.8rem; color: #888; margin-bottom: 1rem; padding-bottom: 0.5rem; border-bottom: 2px solid #eee; }
        .chart-container { position: relative; height: 300px; margin-bottom: 1.5rem; }
        table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
        th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #eee; }
        th { background: #f8f9fa; font-weight: 600; color: #555; font-size: 0.8rem; text-transform: uppercase; }
        td.numeric { text-align: right; font-family: 'SF Mono', Monaco, monospace; }
        td.framework { font-weight: 500; }
        .baseline-select { cursor: pointer; width: 30px; }
        .baseline-radio { display: inline-block; width: 16px; height: 16px; border: 2px solid #ccc; border-radius: 50%; }
        .baseline-radio.selected { border-color: var(--metro-color); background: var(--metro-color); }
        .baseline-row { background: #f0fdf4; }
        .vs-baseline { color: #888; font-size: 0.85em; }
        .vs-baseline.baseline { color: var(--metro-color); font-weight: 500; }
        .vs-baseline.slower { color: #e53935; }
        .vs-baseline.faster { color: #43a047; }
        .diff { font-weight: 500; }
        .diff.positive { color: #e53935; }
        .diff.negative { color: #43a047; }
        .diff.neutral { color: #888; }
        .legend { display: flex; gap: 1.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
        .legend-item { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; }
        .legend-color { width: 16px; height: 16px; border-radius: 3px; }
        .no-data { color: #999; font-style: italic; }
        .summary-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
        .stat-card { background: white; border-radius: 8px; padding: 1.5rem; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .stat-card .value { font-size: 2rem; font-weight: 600; color: var(--metro-color); }
        .stat-card .label { font-size: 0.85rem; color: #666; margin-top: 0.25rem; }
        .metadata-section { background: white; border-radius: 8px; padding: 1.5rem; margin-top: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .metadata-section h2 { margin: 0 0 1rem 0; font-size: 1.1rem; font-weight: 500; color: #666; border-bottom: 2px solid #eee; padding-bottom: 0.5rem; }
        .metadata-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; }
        .metadata-group h3 { margin: 0 0 0.75rem 0; font-size: 0.9rem; font-weight: 600; color: #555; text-transform: uppercase; }
        .metadata-group dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 0.25rem 1rem; font-size: 0.85rem; }
        .metadata-group dt { color: #888; }
        .metadata-group dd { margin: 0; font-family: 'SF Mono', Monaco, monospace; color: #333; word-break: break-all; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Metro Build Benchmark Results</h1>
        <div class="subtitle" id="date"></div>
    </div>
    <div class="container">
        <div class="refs-info" id="refs-info"></div>
        <div id="benchmarks"></div>
        <div class="metadata-section" id="metadata"></div>
    </div>
<script>
const benchmarkData =
HTMLHEAD

    echo "$json_data" >> "$html_file"

    cat >> "$html_file" << 'HTMLTAIL'
;
const colors = { 'metro': '#4CAF50', 'noop': '#607D8B', 'dagger_ksp': '#2196F3', 'dagger_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0' };
const displayNames = { 'metro': 'Metro', 'noop': 'NOOP (Baseline)', 'dagger_ksp': 'Dagger (KSP)', 'dagger_kapt': 'Dagger (KAPT)', 'kotlin_inject_anvil': 'kotlin-inject' };

// State for selectable baseline
let selectedBaseline = 'metro';

function formatTime(ms) {
    if (ms === null || ms === undefined) return '—';
    return (ms / 1000).toFixed(1) + 's';
}

// Calculate percentage difference vs baseline: (value - baseline) / baseline * 100
// e.g., 30s vs 24s baseline = (30-24)/24*100 = +25%
function calculateVsBaseline(value, baselineValue) {
    if (!value || !baselineValue) return { text: '—', class: '' };
    if (value === baselineValue) return { text: 'baseline', class: 'baseline' };
    const pct = ((value - baselineValue) / baselineValue * 100).toFixed(0);
    const mult = (value / baselineValue).toFixed(1);
    if (pct < 0) {
        return { text: `${pct}% (${mult}x)`, class: 'faster' };
    }
    return { text: `+${pct}% (${mult}x)`, class: 'slower' };
}

function calculateDiff(newVal, oldVal) {
    if (!newVal || !oldVal) return { text: '—', class: 'neutral' };
    const pct = ((newVal - oldVal) / oldVal * 100).toFixed(2);
    if (Math.abs(pct) < 0.01) return { text: '+0.00%', class: 'neutral' };
    const prefix = pct > 0 ? '+' : '';
    return { text: `${prefix}${pct}%`, class: pct > 0 ? 'positive' : 'negative' };
}

function renderRefsInfo() {
    const container = document.getElementById('refs-info');
    let html = '';
    if (benchmarkData.refs.ref1) {
        html += `<div class="ref-card baseline"><h3>Baseline (ref1)</h3><div class="ref-name">${benchmarkData.refs.ref1.label}</div><div class="commit">${benchmarkData.refs.ref1.commit}</div></div>`;
    }
    if (benchmarkData.refs.ref2) {
        html += `<div class="ref-card comparison"><h3>Comparison (ref2)</h3><div class="ref-name">${benchmarkData.refs.ref2.label}</div><div class="commit">${benchmarkData.refs.ref2.commit}</div></div>`;
    }
    container.innerHTML = html;
}

function renderSummaryStats() {
    const container = document.getElementById('summary-stats');
    let totalSpeedup = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0 };
    let counts = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0 };
    benchmarkData.benchmarks.forEach(benchmark => {
        const metroResult = benchmark.results.find(r => r.key === 'metro');
        if (!metroResult || !metroResult.ref1) return;
        benchmark.results.forEach(result => {
            if (result.key !== 'metro' && result.ref1) {
                totalSpeedup[result.key] += result.ref1 / metroResult.ref1;
                counts[result.key]++;
            }
        });
    });
    let html = '';
    const names = { 'dagger_ksp': 'Dagger (KSP)', 'dagger_kapt': 'Dagger (KAPT)', 'kotlin_inject_anvil': 'kotlin-inject' };
    Object.keys(totalSpeedup).forEach(key => {
        if (counts[key] > 0) {
            const avgSpeedup = (totalSpeedup[key] / counts[key]).toFixed(1);
            html += `<div class="stat-card"><div class="value">${avgSpeedup}x</div><div class="label">faster than ${names[key]}</div></div>`;
        }
    });
    container.innerHTML = html;
}

function getBaselineLabel() {
    const result = benchmarkData.benchmarks[0]?.results.find(r => r.key === selectedBaseline);
    return result?.framework || 'Baseline';
}

function renderBenchmarks() {
    const container = document.getElementById('benchmarks');
    let html = '';
    benchmarkData.benchmarks.forEach((benchmark, idx) => {
        html += `<div class="benchmark-section"><h2>${benchmark.name}</h2>
            <div class="chart-hint">Lower is better</div>
            <div class="legend">${benchmark.results.map(r => `<div class="legend-item"><div class="legend-color" style="background: ${colors[r.key]}"></div><span>${r.framework}</span></div>`).join('')}</div>
            <div class="chart-container"><canvas id="chart-${idx}"></canvas></div>
            <table><thead><tr><th></th><th>Framework</th>
                ${benchmarkData.refs.ref1 ? `<th>${benchmarkData.refs.ref1.label}</th><th>vs <span class="baseline-header">${getBaselineLabel()}</span></th>` : ''}
                ${benchmarkData.refs.ref2 ? `<th>${benchmarkData.refs.ref2.label}</th><th>vs <span class="baseline-header">${getBaselineLabel()}</span></th>` : ''}
                ${benchmarkData.refs.ref1 && benchmarkData.refs.ref2 ? '<th>Difference</th>' : ''}
            </tr></thead><tbody id="table-${idx}"></tbody></table></div>`;
    });
    container.innerHTML = html;
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderChart(benchmark, idx); renderTable(benchmark, idx); });
}

const charts = [];
function renderChart(benchmark, idx) {
    const ctx = document.getElementById(`chart-${idx}`).getContext('2d');
    const labels = [], ref1Data = [], ref2Data = [], backgroundColors = [];
    benchmark.results.forEach(result => {
        labels.push(result.framework);
        ref1Data.push(result.ref1 ? result.ref1 / 1000 : 0);
        ref2Data.push(result.ref2 ? result.ref2 / 1000 : 0);
        backgroundColors.push(colors[result.key]);
    });
    const datasets = [];
    if (benchmarkData.refs.ref1) datasets.push({ label: benchmarkData.refs.ref1.label, data: ref1Data, backgroundColor: backgroundColors.map(c => c + 'CC'), borderColor: backgroundColors, borderWidth: 2 });
    if (benchmarkData.refs.ref2) datasets.push({ label: benchmarkData.refs.ref2.label, data: ref2Data, backgroundColor: backgroundColors.map(c => c + '66'), borderColor: backgroundColors, borderWidth: 2, borderDash: [5, 5] });
    charts[idx] = new Chart(ctx, { type: 'bar', data: { labels, datasets }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: datasets.length > 1 }, tooltip: { callbacks: { label: ctx => ctx.dataset.label + ': ' + ctx.raw.toFixed(1) + 's' } } }, scales: { y: { beginAtZero: true, title: { display: true, text: 'Time (seconds)' } } } } });
}

function renderTable(benchmark, idx) {
    const tbody = document.getElementById(`table-${idx}`);
    const baselineRef1 = benchmark.results.find(r => r.key === selectedBaseline)?.ref1;
    const baselineRef2 = benchmark.results.find(r => r.key === selectedBaseline)?.ref2;
    let html = '';
    benchmark.results.forEach(result => {
        const isBaseline = result.key === selectedBaseline;
        const vsBaseline1 = calculateVsBaseline(result.ref1, baselineRef1);
        const vsBaseline2 = calculateVsBaseline(result.ref2, baselineRef2);
        const diff = calculateDiff(result.ref2, result.ref1);
        html += `<tr class="${isBaseline ? 'baseline-row' : ''}" data-key="${result.key}">
            <td class="baseline-select" onclick="setBaseline('${result.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>
            <td class="framework" style="color: ${colors[result.key]}">${result.framework}</td>
            ${benchmarkData.refs.ref1 ? `<td class="numeric">${result.ref1 ? formatTime(result.ref1) : '<span class="no-data">N/A</span>'}</td><td class="numeric vs-baseline ${vsBaseline1.class}">${vsBaseline1.text}</td>` : ''}
            ${benchmarkData.refs.ref2 ? `<td class="numeric">${result.ref2 ? formatTime(result.ref2) : '<span class="no-data">(not run)</span>'}</td><td class="numeric vs-baseline ${vsBaseline2.class}">${vsBaseline2.text}</td>` : ''}
            ${benchmarkData.refs.ref1 && benchmarkData.refs.ref2 ? `<td class="numeric diff ${diff.class}">${diff.text}</td>` : ''}</tr>`;
    });
    tbody.innerHTML = html;
}

function setBaseline(key) {
    selectedBaseline = key;
    // Update all tables
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderTable(benchmark, idx); });
    // Update header labels
    document.querySelectorAll('.baseline-header').forEach(el => { el.textContent = getBaselineLabel(); });
}

function renderMetadata() {
    const container = document.getElementById('metadata');
    if (!benchmarkData.metadata) { container.style.display = 'none'; return; }
    const m = benchmarkData.metadata;
    container.innerHTML = `
        <h2>Build Environment</h2>
        <div class="metadata-grid">
            <div class="metadata-group">
                <h3>Library Versions</h3>
                <dl>
                    <dt>Kotlin</dt><dd>${m.versions?.kotlin || '—'}</dd>
                    <dt>Dagger</dt><dd>${m.versions?.dagger || '—'}</dd>
                    <dt>KSP</dt><dd>${m.versions?.ksp || '—'}</dd>
                    <dt>kotlin-inject</dt><dd>${m.versions?.kotlinInject || '—'}</dd>
                    <dt>Anvil</dt><dd>${m.versions?.anvil || '—'}</dd>
                    <dt>kotlin-inject-anvil</dt><dd>${m.versions?.kotlinInjectAnvil || '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>Build Tools</h3>
                <dl>
                    <dt>Gradle</dt><dd>${m.build?.gradle || '—'}</dd>
                    <dt>Gradle Profiler</dt><dd>${m.build?.gradleProfiler || '—'}</dd>
                    <dt>JDK</dt><dd>${m.build?.jdk || '—'}</dd>
                    <dt>JVM Target</dt><dd>${m.build?.jvmTarget || '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>System</h3>
                <dl>
                    <dt>OS</dt><dd>${m.system?.os || '—'}</dd>
                    <dt>CPU</dt><dd>${m.system?.cpu || '—'}</dd>
                    <dt>RAM</dt><dd>${m.system?.ram || '—'}</dd>
                    <dt>Daemon JVM Args</dt><dd>${m.system?.daemonJvmArgs || '—'}</dd>
                </dl>
            </div>
        </div>`;
}

document.getElementById('date').textContent = new Date(benchmarkData.date).toLocaleString();
renderRefsInfo(); renderBenchmarks(); renderMetadata();
</script>
</body>
</html>
HTMLTAIL

    print_success "HTML report saved to $html_file"
}

# Build JSON data for HTML report
build_benchmark_json() {
    local ref1_label="$1"
    local ref2_label="${2:-}"
    local modes="$3"

    local test_types=("abi_change" "non_abi_change" "plain_abi_change" "plain_non_abi_change" "raw_compilation")
    local test_names=("ABI Change" "Non-ABI Change" "Plain Kotlin ABI" "Plain Kotlin Non-ABI" "Graph Processing")

    IFS=',' read -ra MODE_ARRAY <<< "$modes"

    # Get repo root and read metadata
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local versions_file="$repo_root/gradle/libs.versions.toml"

    # Helper to extract version from libs.versions.toml
    get_toml_version() {
        local key="$1"
        grep "^${key} = " "$versions_file" 2>/dev/null | sed 's/.*= *"\([^"]*\)".*/\1/' | head -1
    }

    echo "{"
    echo '  "title": "Build Benchmark Comparison",'
    echo '  "date": "'$(date -Iseconds)'",'
    echo '  "moduleCount": '"$DEFAULT_MODULE_COUNT"','

    # Refs info
    echo '  "refs": {'
    local ref1_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    echo '    "ref1": { "label": "'"$ref1_label"'", "commit": "'"$ref1_commit"'" }'
    if [ -n "$ref2_label" ]; then
        local ref2_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")
        echo '    ,"ref2": { "label": "'"$ref2_label"'", "commit": "'"$ref2_commit"'" }'
    fi
    echo '  },'

    # Build metadata
    local kotlin_version=$(get_toml_version "kotlin")
    local dagger_version=$(get_toml_version "dagger")
    local ksp_version=$(get_toml_version "ksp")
    local kotlin_inject_version=$(get_toml_version "kotlinInject")
    local anvil_version=$(get_toml_version "anvil")
    local kotlin_inject_anvil_version=$(get_toml_version "kotlinInject-anvil")
    local jvm_target=$(get_toml_version "jvmTarget")

    local gradle_version=$("$repo_root/gradlew" --version 2>/dev/null | grep "^Gradle " | awk '{print $2}' || echo "unknown")

    local profiler_version="unknown"
    local profiler_source_dir="$repo_root/tmp/gradle-profiler-source"
    if [ -d "$profiler_source_dir/.git" ]; then
        local profiler_sha=$(cd "$profiler_source_dir" && git rev-parse --short HEAD 2>/dev/null || echo "")
        profiler_version="source ($profiler_sha)"
    elif command -v gradle-profiler &> /dev/null; then
        profiler_version=$(gradle-profiler --version 2>/dev/null | head -1 || echo "unknown")
    fi

    local java_version=$(java -version 2>&1 | head -1 | sed 's/.*"\([^"]*\)".*/\1/' || echo "unknown")

    local os_info=$(uname -s 2>/dev/null || echo "unknown")
    local cpu_info=""
    local ram_info=""
    if [ "$os_info" = "Darwin" ]; then
        cpu_info=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo "unknown")
        ram_info=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0f GB", $1/1024/1024/1024}' || echo "unknown")
    elif [ "$os_info" = "Linux" ]; then
        cpu_info=$(grep "model name" /proc/cpuinfo 2>/dev/null | head -1 | cut -d: -f2 | xargs || echo "unknown")
        ram_info=$(free -h 2>/dev/null | awk '/^Mem:/ {print $2}' || echo "unknown")
    fi

    local daemon_jvm_args=""
    if [ -f "$repo_root/gradle.properties" ]; then
        daemon_jvm_args=$(grep "org.gradle.jvmargs" "$repo_root/gradle.properties" 2>/dev/null | cut -d= -f2- | sed 's/"/\\"/g' || echo "")
    fi

    echo '  "metadata": {'
    echo '    "versions": {'
    echo '      "kotlin": "'"$kotlin_version"'",'
    echo '      "dagger": "'"$dagger_version"'",'
    echo '      "ksp": "'"$ksp_version"'",'
    echo '      "kotlinInject": "'"$kotlin_inject_version"'",'
    echo '      "anvil": "'"$anvil_version"'",'
    echo '      "kotlinInjectAnvil": "'"$kotlin_inject_anvil_version"'"'
    echo '    },'
    echo '    "build": {'
    echo '      "gradle": "'"$gradle_version"'",'
    echo '      "gradleProfiler": "'"$profiler_version"'",'
    echo '      "jdk": "'"$java_version"'",'
    echo '      "jvmTarget": "'"$jvm_target"'"'
    echo '    },'
    echo '    "system": {'
    echo '      "os": "'"$os_info"'",'
    echo '      "cpu": "'"$cpu_info"'",'
    echo '      "ram": "'"$ram_info"'",'
    echo '      "daemonJvmArgs": "'"$daemon_jvm_args"'"'
    echo '    }'
    echo '  },'

    # Benchmarks data
    echo '  "benchmarks": ['

    local first_test=true
    for i in "${!test_types[@]}"; do
        local test_type="${test_types[$i]}"
        local test_name="${test_names[$i]}"

        if [ "$first_test" = false ]; then echo ","; fi
        first_test=false

        echo '    {'
        echo '      "name": "'"$test_name"'",'
        echo '      "key": "'"$test_type"'",'
        echo '      "results": ['

        local first_mode=true
        for mode in "${MODE_ARRAY[@]}"; do
            local mode_prefix
            local mode_name
            case "$mode" in
                "metro") mode_prefix="metro"; mode_name="Metro" ;;
                "noop") mode_prefix="noop"; mode_name="NOOP (Baseline)" ;;
                "dagger-ksp") mode_prefix="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                "dagger-kapt") mode_prefix="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                "kotlin-inject-anvil") mode_prefix="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
                *) continue ;;
            esac

            if [ "$first_mode" = false ]; then echo ","; fi
            first_mode=false

            local score1=$(extract_median_for_ref "$ref1_label" "$mode_prefix" "$test_type")
            local score2=""
            if [ -n "$ref2_label" ]; then
                score2=$(extract_median_for_ref "$ref2_label" "$mode_prefix" "$test_type")
            fi

            echo '        {'
            echo '          "framework": "'"$mode_name"'",'
            echo '          "key": "'"$mode_prefix"'",'
            if [ -n "$score1" ]; then
                echo '          "ref1": '"$score1"','
            else
                echo '          "ref1": null,'
            fi
            if [ -n "$score2" ]; then
                echo '          "ref2": '"$score2"
            else
                echo '          "ref2": null'
            fi
            echo -n '        }'
        done

        echo ''
        echo '      ]'
        echo -n '    }'
    done

    echo ''
    echo '  ]'
    echo "}"
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

        # Get metro score for this test type to use as baseline
        local metro_score=$(extract_median_for_ref "$ref_label" "metro" "$test_type")

        cat >> "$summary_file" << EOF
## $test_name

| Framework | Time | vs Metro |
|-----------|------|----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local mode_prefix
            case "$mode" in
                "metro") mode_prefix="metro" ;;
                "noop") mode_prefix="noop" ;;
                "dagger-ksp") mode_prefix="dagger_ksp" ;;
                "dagger-kapt") mode_prefix="dagger_kapt" ;;
                "kotlin-inject-anvil") mode_prefix="kotlin_inject_anvil" ;;
                *) continue ;;
            esac

            local score=$(extract_median_for_ref "$ref_label" "$mode_prefix" "$test_type")

            local display="N/A"
            local vs_metro="—"

            if [ -n "$score" ]; then
                local secs=$(echo "scale=1; $score / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs" ]; then
                    display="${secs}s"
                fi
                # Calculate vs Metro
                if [ "$mode" = "metro" ]; then
                    vs_metro="baseline"
                elif [ -n "$metro_score" ] && [ "$metro_score" != "0" ]; then
                    local pct=$(echo "scale=1; (($score - $metro_score) / $metro_score) * 100" | bc 2>/dev/null | sed 's/\.0$//' || echo "")
                    local mult=$(echo "scale=1; $score / $metro_score" | bc 2>/dev/null || echo "")
                    if [ -n "$pct" ] && [ -n "$mult" ]; then
                        vs_metro="+${pct}% (${mult}x)"
                    fi
                fi
            fi

            echo "| $mode | $display | $vs_metro |" >> "$summary_file"
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

    # Generate HTML report
    generate_html_report "$ref_label" "" "$modes"
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
        noop)
            run_mode_benchmark "noop" "" "$count" "$build_only" "$include_clean_builds"
            ;;
        dagger-ksp)
            run_mode_benchmark "dagger" "ksp" "$count" "$build_only" "$include_clean_builds"
            ;;
        dagger-kapt)
            run_mode_benchmark "dagger" "kapt" "$count" "$build_only" "$include_clean_builds"
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