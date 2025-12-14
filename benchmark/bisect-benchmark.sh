#!/usr/bin/env bash
# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0
#
# Git bisect script for finding benchmark regressions.
#
# This script is designed to be used with `git bisect run` to automatically
# find the commit that introduced a performance regression.
#
# It compares each tested commit against the "good" ref by:
# 1. On first run: publishing the good ref to mavenLocal and caching its benchmark result
# 2. On each bisect iteration: publishing the current commit and comparing against cached baseline
#
# Supported benchmark types:
#   build          - Incremental build time (ABI change)
#   startup-jvm    - JVM cold start time (JMH)
#   startup-jvm-r8 - JVM cold start with R8 minification
#
# Usage:
#   git bisect start <bad-commit> <good-commit>
#   git bisect run benchmark/bisect-benchmark.sh --good-ref <good-commit> [options]
#
# Options:
#   --good-ref <ref>              Git ref to compare against (required, should match bisect good ref)
#   --type <type>                 Benchmark type: build, startup-jvm, startup-jvm-r8 (default: build)
#   --threshold <percent>         Regression threshold percentage (default: 10)
#   --module-count <count>        Number of modules to generate (default: 100 for build, 500 for startup)
#   --warmup-count <count>        Number of warmup iterations (default: 3, build only)
#   --iteration-count <count>     Number of measured iterations (default: 5, build only)
#   --verbose                     Enable verbose output
#
# Exit codes (per git bisect convention):
#   0   - Good commit (no regression detected)
#   1   - Bad commit (regression detected)
#   125 - Skip commit (build failed, tells git bisect to skip this commit)
#
# Examples:
#   # Build time bisect
#   git bisect start HEAD v0.8.2
#   git bisect run benchmark/bisect-benchmark.sh --good-ref v0.8.2 --type build --threshold 15
#
#   # JVM startup time bisect
#   git bisect start HEAD v0.8.2
#   git bisect run benchmark/bisect-benchmark.sh --good-ref v0.8.2 --type startup-jvm --threshold 20

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Determine repo root - if we're in benchmark/tmp/bisect, go up three levels; otherwise go up one
if [[ "$SCRIPT_DIR" == */benchmark/tmp/bisect ]]; then
    REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
else
    REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

# Source common utilities from the same directory as this script
# (they get copied together by metrow bisect)
source "$SCRIPT_DIR/benchmark-utils.sh"

# Benchmark directory (always in repo)
BENCHMARK_DIR="$REPO_ROOT/benchmark"

# Configuration defaults
GOOD_REF=""
BENCHMARK_TYPE="build"
BUILD_SCENARIO="abi"  # abi, non-abi, raw, plain-abi, plain-non-abi, clean
THRESHOLD_PERCENT=10
MODULE_COUNT=""  # Will be set based on benchmark type
WARMUP_COUNT=3
ITERATION_COUNT=5
INTERACTIVE=false
VERBOSE=false

# Working directories
BISECT_DIR="$BENCHMARK_DIR/bisect-results"

# Print functions
print_bisect_status() {
    echo -e "${BLUE}[BISECT]${NC} $1"
}

print_bisect_success() {
    echo -e "${GREEN}[BISECT]${NC} $1"
}

print_bisect_warning() {
    echo -e "${YELLOW}[BISECT]${NC} $1"
}

print_bisect_error() {
    echo -e "${RED}[BISECT]${NC} $1"
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --good-ref)
                GOOD_REF="$2"
                shift 2
                ;;
            --type)
                BENCHMARK_TYPE="$2"
                shift 2
                ;;
            --scenario)
                BUILD_SCENARIO="$2"
                shift 2
                ;;
            --threshold)
                THRESHOLD_PERCENT="$2"
                shift 2
                ;;
            --module-count)
                MODULE_COUNT="$2"
                shift 2
                ;;
            --warmup-count)
                WARMUP_COUNT="$2"
                shift 2
                ;;
            --iteration-count)
                ITERATION_COUNT="$2"
                shift 2
                ;;
            --interactive|-i)
                INTERACTIVE=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --help|-h)
                show_usage
                exit 0
                ;;
            *)
                print_bisect_error "Unknown option: $1"
                show_usage
                exit 125
                ;;
        esac
    done

    if [[ -z "$GOOD_REF" ]]; then
        print_bisect_error "--good-ref is required"
        show_usage
        exit 125
    fi

    # Validate benchmark type
    case "$BENCHMARK_TYPE" in
        build|startup-jvm|startup-jvm-r8)
            ;;
        *)
            print_bisect_error "Invalid benchmark type: $BENCHMARK_TYPE"
            print_bisect_error "Valid types: build, startup-jvm, startup-jvm-r8"
            exit 125
            ;;
    esac

    # Validate build scenario (only applies to build type)
    if [[ "$BENCHMARK_TYPE" == "build" ]]; then
        case "$BUILD_SCENARIO" in
            abi|non-abi|raw|plain-abi|plain-non-abi|clean)
                ;;
            *)
                print_bisect_error "Invalid build scenario: $BUILD_SCENARIO"
                print_bisect_error "Valid scenarios: abi, non-abi, raw, plain-abi, plain-non-abi, clean"
                exit 125
                ;;
        esac
    fi

    # Set default module count based on benchmark type
    if [[ -z "$MODULE_COUNT" ]]; then
        case "$BENCHMARK_TYPE" in
            build)
                MODULE_COUNT=500
                ;;
            startup-jvm|startup-jvm-r8)
                MODULE_COUNT=500
                ;;
        esac
    fi
}

show_usage() {
    cat << 'EOF'
Git Bisect Benchmark Script

Usage:
  git bisect start <bad-commit> <good-commit>
  git bisect run benchmark/bisect-benchmark.sh --good-ref <good-commit> [options]

Required:
  --good-ref <ref>              Git ref to compare against (should match bisect good ref)

Options:
  --type <type>                 Benchmark type (default: build)
                                  build          - Incremental build time
                                  startup-jvm    - JVM cold start time (JMH)
                                  startup-jvm-r8 - JVM cold start with R8 minification
  --scenario <scenario>         Build scenario (default: abi, only for --type build)
                                  abi          - ABI change to CommonInterfaces.kt
                                  non-abi      - Non-ABI change to AuthFeature10.kt
                                  raw          - Raw compilation (--rerun-tasks)
                                  plain-abi    - Plain Kotlin ABI change
                                  plain-non-abi - Plain Kotlin non-ABI change
                                  clean        - Clean build
  --threshold <percent>         Regression threshold percentage (default: 10)
  --module-count <count>        Number of modules (default: 500)
  --warmup-count <count>        Warmup iterations (default: 3, build type only)
  --iteration-count <count>     Measured iterations (default: 5, build type only)
  --interactive, -i             Interactive mode: manually judge each commit
  --verbose                     Enable verbose output

Exit Codes:
  0   - Good commit (no regression)
  1   - Bad commit (regression detected)
  125 - Skip commit (build failed)

Examples:
  # Build time bisect (ABI change, default)
  git bisect start HEAD v0.8.2
  git bisect run benchmark/bisect-benchmark.sh --good-ref v0.8.2 --type build

  # Build time bisect with non-ABI change scenario
  git bisect start HEAD v0.8.2
  git bisect run benchmark/bisect-benchmark.sh --good-ref v0.8.2 --type build --scenario non-abi

  # JVM startup time bisect
  git bisect start HEAD v0.8.2
  git bisect run benchmark/bisect-benchmark.sh --good-ref v0.8.2 --type startup-jvm --threshold 20
EOF
}

# Get commit info
get_commit_sha() {
    local ref="$1"
    git rev-parse "$ref"
}

get_commit_short() {
    local ref="$1"
    git rev-parse --short "$ref"
}

# Get cache file paths based on benchmark type and scenario
get_baseline_cache_file() {
    if [[ "$BENCHMARK_TYPE" == "build" ]]; then
        echo "$BISECT_DIR/baseline-${BENCHMARK_TYPE}-${BUILD_SCENARIO}-cache.txt"
    else
        echo "$BISECT_DIR/baseline-${BENCHMARK_TYPE}-cache.txt"
    fi
}

get_baseline_version_file() {
    if [[ "$BENCHMARK_TYPE" == "build" ]]; then
        echo "$BISECT_DIR/baseline-${BENCHMARK_TYPE}-${BUILD_SCENARIO}-version.txt"
    else
        echo "$BISECT_DIR/baseline-${BENCHMARK_TYPE}-version.txt"
    fi
}

# Publish a commit's Metro to mavenLocal
publish_to_maven_local() {
    local ref="$1"
    local commit_sha
    commit_sha=$(get_commit_short "$ref")
    local version="1.0.0-bisect-${commit_sha}"

    print_bisect_status "Publishing Metro at $ref to mavenLocal as version: $version"

    cd "$REPO_ROOT"

    # Stash current state
    local current_sha
    current_sha=$(git rev-parse HEAD)

    # Checkout the ref to build
    git checkout "$ref" --quiet 2>/dev/null || {
        print_bisect_error "Failed to checkout $ref"
        return 1
    }

    # Clean and publish
    local publish_result=0
    if [ "$VERBOSE" = true ]; then
        ./metrow publish --local --version "$version" || publish_result=$?
    else
        ./metrow publish --local --version "$version" > /dev/null 2>&1 || publish_result=$?
    fi

    # Return to original commit
    git checkout "$current_sha" --quiet 2>/dev/null

    if [ $publish_result -ne 0 ]; then
        print_bisect_error "Failed to publish Metro to mavenLocal"
        return 1
    fi

    print_bisect_success "Published Metro version: $version"
    echo "$version"
}

# Publish current HEAD to mavenLocal (used during bisect iterations)
publish_current_to_maven_local() {
    local commit_sha
    commit_sha=$(get_commit_short HEAD)
    local version="1.0.0-bisect-${commit_sha}"

    print_bisect_status "Publishing current commit to mavenLocal as version: $version"

    cd "$REPO_ROOT"

    # Clean and publish
    if [ "$VERBOSE" = true ]; then
        ./metrow publish --local --version "$version"
    else
        ./metrow publish --local --version "$version" > /dev/null 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "Failed to publish Metro to mavenLocal"
        return 1
    fi

    print_bisect_success "Published Metro version: $version"
    echo "$version"
}

# =============================================================================
# BUILD BENCHMARK
# =============================================================================

# Get the file to change and change type based on scenario
get_scenario_config() {
    local scenario="$1"
    # Returns: change_file|change_type|gradle_args
    # change_type: abi, non-abi, none
    case "$scenario" in
        abi)
            echo "core/foundation/src/main/kotlin/dev/zacsweers/metro/benchmark/core/foundation/CommonInterfaces.kt|abi|"
            ;;
        non-abi)
            echo "features/auth-feature-10/src/main/kotlin/dev/zacsweers/metro/benchmark/features/authfeature10/AuthFeature10.kt|non-abi|"
            ;;
        raw)
            echo "|none|--rerun-tasks"
            ;;
        plain-abi)
            echo "core/foundation/src/main/kotlin/dev/zacsweers/metro/benchmark/core/foundation/PlainKotlinFile.kt|abi|"
            ;;
        plain-non-abi)
            echo "core/foundation/src/main/kotlin/dev/zacsweers/metro/benchmark/core/foundation/PlainKotlinFile.kt|non-abi|"
            ;;
        clean)
            echo "|clean|"
            ;;
    esac
}

# Apply a change to a file based on change type
apply_change() {
    local file="$1"
    local change_type="$2"
    local iteration="$3"

    if [[ -z "$file" ]] || [[ "$change_type" == "none" ]] || [[ "$change_type" == "clean" ]]; then
        return 0
    fi

    if [[ ! -f "$file" ]]; then
        print_bisect_warning "Change file not found: $file"
        return 1
    fi

    if [[ "$change_type" == "abi" ]]; then
        # ABI change: add a new public constant
        echo "const val BISECT_ITERATION_$iteration = $iteration // $(date +%s)" >> "$file"
    else
        # Non-ABI change: add a comment or private val
        echo "// Bisect non-abi iteration $iteration - $(date +%s)" >> "$file"
    fi
}

run_build_benchmark() {
    local metro_version="$1"
    local label="$2"

    cd "$BENCHMARK_DIR"

    print_bisect_status "Running BUILD benchmark for $label (version: $metro_version)"
    print_bisect_status "Scenario: $BUILD_SCENARIO"
    print_bisect_status "Generating projects with $MODULE_COUNT modules..."

    # Generate projects
    if [ "$VERBOSE" = true ]; then
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT"
    else
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT" > /dev/null 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "Failed to generate projects"
        return 1
    fi

    # Get scenario configuration
    local config
    config=$(get_scenario_config "$BUILD_SCENARIO")
    local change_file=$(echo "$config" | cut -d'|' -f1)
    local change_type=$(echo "$config" | cut -d'|' -f2)
    local gradle_extra_args=$(echo "$config" | cut -d'|' -f3)

    # Prepend generated/ to change_file path if it exists
    if [[ -n "$change_file" ]]; then
        change_file="generated/$change_file"
    fi

    # Set METRO_VERSION env var to use the specified version
    export METRO_VERSION="$metro_version"

    print_bisect_status "Running warmup builds ($WARMUP_COUNT iterations)..."

    # Warmup builds
    for i in $(seq 1 $WARMUP_COUNT); do
        if [ "$VERBOSE" = true ]; then
            print_bisect_status "Warmup iteration $i/$WARMUP_COUNT"
        fi
        if [[ "$change_type" == "clean" ]]; then
            ./gradlew clean > /dev/null 2>&1
        fi
        apply_change "$change_file" "$change_type" "warmup_$i"
        ./gradlew :app:component:compileKotlin $gradle_extra_args > /dev/null 2>&1
    done

    print_bisect_status "Running measured builds ($ITERATION_COUNT iterations)..."

    # Measured builds - collect times
    local total_time=0
    local times=()

    for i in $(seq 1 $ITERATION_COUNT); do
        # Clean for clean build scenario
        if [[ "$change_type" == "clean" ]]; then
            ./gradlew clean > /dev/null 2>&1
        fi

        # Apply change for incremental scenarios
        apply_change "$change_file" "$change_type" "$i"

        local start_time=$(date +%s%3N)
        ./gradlew :app:component:compileKotlin $gradle_extra_args > /dev/null 2>&1
        local end_time=$(date +%s%3N)

        local elapsed=$((end_time - start_time))
        times+=("$elapsed")
        total_time=$((total_time + elapsed))

        if [ "$VERBOSE" = true ]; then
            print_bisect_status "Iteration $i: ${elapsed}ms"
        fi
    done

    # Calculate median
    IFS=$'\n' sorted=($(sort -n <<<"${times[*]}")); unset IFS
    local mid=$((ITERATION_COUNT / 2))
    local median_time
    if [ $((ITERATION_COUNT % 2)) -eq 0 ]; then
        median_time=$(( (sorted[mid-1] + sorted[mid]) / 2 ))
    else
        median_time=${sorted[mid]}
    fi

    unset METRO_VERSION

    print_bisect_success "$label build ($BUILD_SCENARIO) results: median=${median_time}ms"
    echo "$median_time"
}

# =============================================================================
# JVM STARTUP BENCHMARK
# =============================================================================

run_startup_jvm_benchmark() {
    local metro_version="$1"
    local label="$2"

    cd "$BENCHMARK_DIR"

    print_bisect_status "Running JVM STARTUP benchmark for $label (version: $metro_version)"
    print_bisect_status "Generating projects with $MODULE_COUNT modules..."

    # Generate projects
    if [ "$VERBOSE" = true ]; then
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT"
    else
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT" > /dev/null 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "Failed to generate projects"
        return 1
    fi

    # Set METRO_VERSION env var
    export METRO_VERSION="$metro_version"

    print_bisect_status "Running JMH startup benchmark..."

    # Build and run JMH benchmark
    local jmh_output
    jmh_output=$(mktemp)

    if [ "$VERBOSE" = true ]; then
        ./gradlew :startup-jvm:jmh 2>&1 | tee "$jmh_output"
    else
        ./gradlew :startup-jvm:jmh > "$jmh_output" 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "JMH benchmark failed"
        rm -f "$jmh_output"
        unset METRO_VERSION
        return 1
    fi

    # Parse JMH results - extract the average time in ms
    # JMH output format: "Benchmark                          Mode  Cnt    Score   Error  Units"
    local score
    score=$(grep -E "^.*StartupBenchmark.*avgt" "$jmh_output" | awk '{print $4}' | head -1)

    rm -f "$jmh_output"
    unset METRO_VERSION

    if [ -z "$score" ]; then
        print_bisect_error "Failed to parse JMH results"
        return 1
    fi

    # Convert to integer milliseconds (JMH reports in ms)
    local result_ms
    result_ms=$(echo "$score" | awk '{printf "%.0f", $1}')

    print_bisect_success "$label JVM startup results: ${result_ms}ms"
    echo "$result_ms"
}

# =============================================================================
# JVM STARTUP R8 BENCHMARK
# =============================================================================

run_startup_jvm_r8_benchmark() {
    local metro_version="$1"
    local label="$2"

    cd "$BENCHMARK_DIR"

    print_bisect_status "Running JVM STARTUP (R8) benchmark for $label (version: $metro_version)"
    print_bisect_status "Generating projects with $MODULE_COUNT modules..."

    # Generate projects
    if [ "$VERBOSE" = true ]; then
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT"
    else
        kotlin generate-projects.main.kts --mode METRO --count "$MODULE_COUNT" > /dev/null 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "Failed to generate projects"
        return 1
    fi

    # Set METRO_VERSION env var
    export METRO_VERSION="$metro_version"

    print_bisect_status "Building R8-minified JAR and running JMH benchmark..."

    # Build minified JAR and run JMH benchmark
    local jmh_output
    jmh_output=$(mktemp)

    if [ "$VERBOSE" = true ]; then
        ./gradlew :startup-jvm-minified:jmh 2>&1 | tee "$jmh_output"
    else
        ./gradlew :startup-jvm-minified:jmh > "$jmh_output" 2>&1
    fi

    if [ $? -ne 0 ]; then
        print_bisect_error "JMH R8 benchmark failed"
        rm -f "$jmh_output"
        unset METRO_VERSION
        return 1
    fi

    # Parse JMH results
    local score
    score=$(grep -E "^.*StartupBenchmark.*avgt" "$jmh_output" | awk '{print $4}' | head -1)

    rm -f "$jmh_output"
    unset METRO_VERSION

    if [ -z "$score" ]; then
        print_bisect_error "Failed to parse JMH results"
        return 1
    fi

    local result_ms
    result_ms=$(echo "$score" | awk '{printf "%.0f", $1}')

    print_bisect_success "$label JVM startup (R8) results: ${result_ms}ms"
    echo "$result_ms"
}

# =============================================================================
# BENCHMARK DISPATCHER
# =============================================================================

run_benchmark() {
    local metro_version="$1"
    local label="$2"

    case "$BENCHMARK_TYPE" in
        build)
            run_build_benchmark "$metro_version" "$label"
            ;;
        startup-jvm)
            run_startup_jvm_benchmark "$metro_version" "$label"
            ;;
        startup-jvm-r8)
            run_startup_jvm_r8_benchmark "$metro_version" "$label"
            ;;
    esac
}

# Get or compute baseline benchmark from the good ref
get_baseline_time() {
    local cache_file
    cache_file=$(get_baseline_cache_file)
    local version_file
    version_file=$(get_baseline_version_file)

    # Check for cached baseline that matches our good ref
    if [ -f "$cache_file" ] && [ -f "$version_file" ]; then
        local cached_ref
        cached_ref=$(cat "$version_file")
        local expected_ref
        expected_ref=$(get_commit_sha "$GOOD_REF")

        if [ "$cached_ref" = "$expected_ref" ]; then
            local cached_time
            cached_time=$(cat "$cache_file")
            print_bisect_status "Using cached baseline time for $GOOD_REF: ${cached_time}ms"
            echo "$cached_time"
            return 0
        fi
    fi

    print_bisect_status "Computing baseline from good ref: $GOOD_REF"

    # Publish the good ref to mavenLocal
    local baseline_version
    baseline_version=$(publish_to_maven_local "$GOOD_REF")

    if [ $? -ne 0 ] || [ -z "$baseline_version" ]; then
        print_bisect_error "Failed to publish baseline"
        return 1
    fi

    # Run baseline benchmark
    local baseline_time
    baseline_time=$(run_benchmark "$baseline_version" "baseline ($GOOD_REF)")

    if [ $? -ne 0 ]; then
        print_bisect_error "Failed to run baseline benchmark"
        return 1
    fi

    # Cache the results
    echo "$baseline_time" > "$cache_file"
    get_commit_sha "$GOOD_REF" > "$version_file"

    echo "$baseline_time"
}

# Compare times and determine if there's a regression
check_regression() {
    local baseline_time="$1"
    local test_time="$2"

    # Calculate percentage difference
    local diff=$((test_time - baseline_time))
    local percent_diff=$((diff * 100 / baseline_time))

    print_bisect_status "Baseline: ${baseline_time}ms"
    print_bisect_status "Test:     ${test_time}ms"
    print_bisect_status "Diff:     ${diff}ms (${percent_diff}%)"
    print_bisect_status "Threshold: ${THRESHOLD_PERCENT}%"

    if [ "$percent_diff" -gt "$THRESHOLD_PERCENT" ]; then
        print_bisect_warning "REGRESSION DETECTED: ${percent_diff}% > ${THRESHOLD_PERCENT}%"
        return 1  # Bad commit
    else
        print_bisect_success "No regression: ${percent_diff}% <= ${THRESHOLD_PERCENT}%"
        return 0  # Good commit
    fi
}

# Interactive prompt for manual judgment
prompt_interactive() {
    local baseline_time="$1"
    local test_time="$2"
    local commit_short="$3"

    # Calculate percentage difference
    local diff=$((test_time - baseline_time))
    local percent_diff=$((diff * 100 / baseline_time))

    echo ""
    print_bisect_status "============================================"
    print_bisect_status "INTERACTIVE MODE - Manual Judgment Required"
    print_bisect_status "============================================"
    echo ""
    print_bisect_status "Commit: $commit_short"
    print_bisect_status "Baseline: ${baseline_time}ms"
    print_bisect_status "Test:     ${test_time}ms"
    print_bisect_status "Diff:     ${diff}ms (${percent_diff}%)"
    echo ""
    print_bisect_status "Is this commit:"
    echo "  [g] Good (no regression)"
    echo "  [b] Bad (has regression)"
    echo "  [s] Skip (can't determine)"
    echo ""

    while true; do
        read -p "Enter judgment [g/b/s]: " -n 1 -r judgment
        echo ""
        case "$judgment" in
            g|G)
                print_bisect_success "Marked as GOOD"
                return 0
                ;;
            b|B)
                print_bisect_warning "Marked as BAD"
                return 1
                ;;
            s|S)
                print_bisect_status "Marked as SKIP"
                return 125
                ;;
            *)
                print_bisect_error "Invalid input. Please enter g, b, or s."
                ;;
        esac
    done
}

# Main bisect logic
main() {
    parse_args "$@"

    print_bisect_status "============================================"
    print_bisect_status "Git Bisect Benchmark Runner"
    print_bisect_status "============================================"

    local commit_sha
    commit_sha=$(git rev-parse HEAD)
    local commit_short
    commit_short=$(git rev-parse --short HEAD)

    print_bisect_status "Testing commit: $commit_short ($commit_sha)"
    print_bisect_status "Comparing against: $GOOD_REF"
    print_bisect_status "Benchmark type: $BENCHMARK_TYPE"
    if [[ "$BENCHMARK_TYPE" == "build" ]]; then
        print_bisect_status "Build scenario: $BUILD_SCENARIO"
    fi
    print_bisect_status "Regression threshold: ${THRESHOLD_PERCENT}%"
    print_bisect_status "Module count: $MODULE_COUNT"

    # Create bisect results directory
    mkdir -p "$BISECT_DIR"

    # Get baseline time (cached or computed from good ref)
    local baseline_time
    baseline_time=$(get_baseline_time)

    if [ $? -ne 0 ] || [ -z "$baseline_time" ]; then
        print_bisect_error "Failed to get baseline time"
        exit 125
    fi

    # Try to publish current commit to mavenLocal
    local test_version
    test_version=$(publish_current_to_maven_local)

    if [ $? -ne 0 ] || [ -z "$test_version" ]; then
        print_bisect_warning "Build failed for commit $commit_short - skipping"
        exit 125  # Tell git bisect to skip this commit
    fi

    # Run benchmark for test commit
    local test_time
    test_time=$(run_benchmark "$test_version" "test ($commit_short)")

    if [ $? -ne 0 ] || [ -z "$test_time" ]; then
        print_bisect_warning "Benchmark failed for commit $commit_short - skipping"
        exit 125
    fi

    # Save results for this commit
    local result_suffix="${BENCHMARK_TYPE}"
    if [[ "$BENCHMARK_TYPE" == "build" ]]; then
        result_suffix="${BENCHMARK_TYPE}-${BUILD_SCENARIO}"
    fi
    local result_file="$BISECT_DIR/${commit_short}-${result_suffix}-result.txt"
    cat > "$result_file" << EOF
commit: $commit_sha
commit_short: $commit_short
test_version: $test_version
good_ref: $GOOD_REF
benchmark_type: $BENCHMARK_TYPE
build_scenario: $BUILD_SCENARIO
baseline_time_ms: $baseline_time
test_time_ms: $test_time
threshold_percent: $THRESHOLD_PERCENT
module_count: $MODULE_COUNT
timestamp: $(date -Iseconds)
EOF

    # Check for regression (interactive or automatic)
    if [ "$INTERACTIVE" = true ]; then
        prompt_interactive "$baseline_time" "$test_time" "$commit_short"
        local result=$?
        if [ $result -eq 0 ]; then
            print_bisect_success "Commit $commit_short is GOOD"
            exit 0
        elif [ $result -eq 1 ]; then
            print_bisect_warning "Commit $commit_short is BAD"
            exit 1
        else
            print_bisect_status "Commit $commit_short is SKIPPED"
            exit 125
        fi
    else
        if check_regression "$baseline_time" "$test_time"; then
            print_bisect_success "Commit $commit_short is GOOD"
            exit 0
        else
            print_bisect_warning "Commit $commit_short is BAD"
            exit 1
        fi
    fi
}

main "$@"
