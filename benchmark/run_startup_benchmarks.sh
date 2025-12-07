#!/bin/bash
# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

# Script to run Metro startup benchmarks across multiple DI frameworks and generate comparison
# Usage: ./run_startup_benchmarks.sh [jvm|android|all] [--modes metro,anvil-ksp,kotlin-inject-anvil]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Output directory
RESULTS_DIR="startup-benchmark-results"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
MODULE_COUNT=500

# Default modes to benchmark
MODES="metro,anvil-ksp,kotlin-inject-anvil"

print_header() {
    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  $1${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
}

print_step() {
    echo -e "${YELLOW}→ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Clean build artifacts more thoroughly (including KSP caches)
clean_build_artifacts() {
    print_step "Cleaning build artifacts..."
    # Stop Gradle daemon to ensure no stale state
    ./gradlew --stop > /dev/null 2>&1 || true
    # Remove all build directories to avoid stale JAR/class file issues
    find . -type d -name "build" -not -path "./.gradle/*" -exec rm -rf {} + 2>/dev/null || true
    # Remove KSP caches
    find . -type d -name "kspCaches" -exec rm -rf {} + 2>/dev/null || true
    # Remove .gradle caches in project
    rm -rf .gradle/caches 2>/dev/null || true
}

show_usage() {
    echo "Usage: $0 [command] [options]"
    echo ""
    echo "Commands:"
    echo "  jvm       Run JVM startup benchmarks using JMH"
    echo "  android   Run Android benchmarks (requires device)"
    echo "  all       Run all benchmarks (default)"
    echo "  summary   Regenerate summary from existing results (use with --timestamp)"
    echo "  help      Show this help message"
    echo ""
    echo "Options:"
    echo "  --modes <list>      Comma-separated list of modes to benchmark"
    echo "                      Available: metro, anvil-ksp, anvil-kapt, kotlin-inject-anvil"
    echo "                      Default: metro,anvil-ksp,kotlin-inject-anvil"
    echo "  --count <n>         Number of modules to generate (default: 500)"
    echo "  --timestamp <ts>    Use specific timestamp for results directory"
    echo ""
    echo "Examples:"
    echo "  $0 jvm                              # Run JVM benchmarks for all modes"
    echo "  $0 jvm --modes metro,anvil-ksp      # Run JVM benchmarks for specific modes"
    echo "  $0 all --count 250                  # Run all benchmarks with 250 modules"
    echo "  $0 summary --timestamp 20251205_125203 --modes metro,anvil-ksp"
    echo ""
    echo "Results will be saved to: $RESULTS_DIR/"
}

# Parse mode string to generator arguments
get_generator_args() {
    local mode="$1"
    case "$mode" in
        metro)
            echo "--mode metro"
            ;;
        anvil-ksp)
            echo "--mode anvil --processor ksp"
            ;;
        anvil-kapt)
            echo "--mode anvil --processor kapt"
            ;;
        kotlin-inject-anvil)
            echo "--mode kotlin_inject_anvil"
            ;;
        *)
            print_error "Unknown mode: $mode"
            exit 1
            ;;
    esac
}

# Get extra Gradle arguments for a mode (e.g., disable incremental for flaky KSP)
get_gradle_args() {
    local mode="$1"
    case "$mode" in
        anvil-ksp|kotlin-inject-anvil)
            # Disable incremental processing and build cache to avoid flaky KSP builds
            echo "--no-build-cache -Pksp.incremental=false -Pkotlin.incremental=false"
            ;;
        *)
            echo ""
            ;;
    esac
}

# Run JMH benchmark for a specific mode
run_jvm_benchmark() {
    local mode="$1"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
    mkdir -p "$output_dir"

    clean_build_artifacts

    print_step "Generating project for $mode..."
    local gen_args=$(get_generator_args "$mode")
    kotlin generate-projects.main.kts $gen_args --count "$MODULE_COUNT" > /dev/null

    print_step "Running JMH benchmark for $mode..."

    local gradle_args=$(get_gradle_args "$mode")

    # Run JMH and capture output
    if ./gradlew --quiet $gradle_args :startup-jvm:jmh 2>&1 | tee "$output_dir/jmh-output.txt"; then
        # Copy JMH results
        if [ -d "startup-jvm/build/results/jmh" ]; then
            cp -r startup-jvm/build/results/jmh/* "$output_dir/" 2>/dev/null || true
        fi
        print_success "JMH benchmark complete for $mode"
    else
        print_error "JMH benchmark failed for $mode"
        return 1
    fi
}

# Run Android benchmark for a specific mode
run_android_benchmark() {
    local mode="$1"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
    mkdir -p "$output_dir"

    clean_build_artifacts

    print_step "Generating project for $mode..."
    local gen_args=$(get_generator_args "$mode")
    kotlin generate-projects.main.kts $gen_args --count "$MODULE_COUNT" > /dev/null

    local gradle_args=$(get_gradle_args "$mode")

    print_step "Building Android app for $mode..."
    if ! ./gradlew --quiet $gradle_args :startup-android:app:assembleRelease :startup-android:benchmark:assembleBenchmark :startup-android:microbenchmark:assembleBenchmark 2>&1; then
        print_error "Android build failed for $mode"
        return 1
    fi

    print_step "Running Android macrobenchmark for $mode (requires connected device)..."
    if ./gradlew --quiet :startup-android:benchmark:connectedBenchmarkAndroidTest 2>&1 | tee "$output_dir/macro-benchmark-output.txt"; then
        # Copy macrobenchmark results
        local macro_output="startup-android/benchmark/build/outputs/connected_android_test_additional_output"
        if [ -d "$macro_output" ]; then
            cp -r "$macro_output"/* "$output_dir/" 2>/dev/null || true
        fi
        print_success "Android macrobenchmark complete for $mode"
    else
        print_error "Android macrobenchmark failed for $mode (is a device connected?)"
        return 1
    fi

    print_step "Running Android microbenchmark for $mode..."
    if ./gradlew --quiet :startup-android:microbenchmark:connectedBenchmarkAndroidTest 2>&1 | tee "$output_dir/micro-benchmark-output.txt"; then
        # Copy microbenchmark results
        local micro_output="startup-android/microbenchmark/build/outputs/connected_android_test_additional_output"
        if [ -d "$micro_output" ]; then
            mkdir -p "$output_dir/microbenchmark"
            cp -r "$micro_output"/* "$output_dir/microbenchmark/" 2>/dev/null || true
        fi
        print_success "Android microbenchmark complete for $mode"
    else
        print_error "Android microbenchmark failed for $mode"
        return 1
    fi
}

# Extract JMH score from results
extract_jmh_score() {
    local results_file="$1"
    if [ -f "$results_file" ]; then
        # Extract score from JSON results (average time in ms)
        if command -v jq &> /dev/null; then
            jq -r '.[0].primaryMetric.score // empty' "$results_file" 2>/dev/null || echo ""
        else
            # Fallback: grep from text output
            grep -oP 'graphCreationAndInitialization\s+avgt\s+\d+\s+\K[\d.]+' "$results_file" 2>/dev/null || echo ""
        fi
    fi
}

# Extract Android macrobenchmark score from results
extract_android_macro_score() {
    local results_dir="$1"
    # Find the benchmark JSON file (look for benchmarkData.json specifically)
    local json_file=$(find "$results_dir" -name "*benchmarkData.json" -type f 2>/dev/null | head -1)
    if [ -n "$json_file" ] && [ -f "$json_file" ]; then
        if command -v jq &> /dev/null; then
            # Extract median startup time from the "startup" benchmark (macrobenchmark)
            jq -r '.benchmarks[] | select(.name == "startup") | .metrics.timeToInitialDisplayMs.median // empty' "$json_file" 2>/dev/null || echo ""
        fi
    fi
}

# Extract Android microbenchmark score from results (in nanoseconds, convert to ms)
extract_android_micro_score() {
    local results_dir="$1"
    # Find the microbenchmark JSON file
    local json_file=$(find "$results_dir/microbenchmark" -name "*benchmarkData.json" -type f 2>/dev/null | head -1)
    if [ -n "$json_file" ] && [ -f "$json_file" ]; then
        if command -v jq &> /dev/null; then
            # Extract median time in nanoseconds, convert to milliseconds
            local ns=$(jq -r '.benchmarks[] | select(.name | contains("graphCreationAndInitialization")) | .metrics.timeNs.median // empty' "$json_file" 2>/dev/null || echo "")
            if [ -n "$ns" ]; then
                # Convert ns to ms
                echo "scale=3; $ns / 1000000" | bc 2>/dev/null || echo ""
            fi
        fi
    fi
}

# Generate comparison summary
generate_summary() {
    local summary_file="$RESULTS_DIR/${TIMESTAMP}/summary.md"

    print_header "Generating Comparison Summary"

    cat > "$summary_file" << EOF
# Startup Benchmark Results

**Date:** $(date)
**Module Count:** $MODULE_COUNT
**Modes:** $MODES

## JVM Benchmarks (JMH)

Graph creation and initialization time (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

    # Collect JVM results
    local metro_jvm_score=""

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        local jvm_dir="$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
        local score=""

        # Try to get score from JSON first, then text output
        if [ -f "$jvm_dir/results.json" ]; then
            score=$(extract_jmh_score "$jvm_dir/results.json")
        fi

        # Fallback: parse from results.txt or jmh-output.txt
        if [ -z "$score" ] && [ -f "$jvm_dir/results.txt" ]; then
            # Format: StartupBenchmark.graphCreationAndInitialization  avgt   10  0.329 ± 0.090  ms/op
            score=$(grep 'graphCreationAndInitialization' "$jvm_dir/results.txt" 2>/dev/null | awk '{print $4}' || echo "")
        fi
        if [ -z "$score" ] && [ -f "$jvm_dir/jmh-output.txt" ]; then
            # Parse from the summary line at the end
            score=$(grep 'graphCreationAndInitialization' "$jvm_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
        fi

        if [ "$mode" = "metro" ]; then
            metro_jvm_score="$score"
        fi

        # Calculate comparison
        local comparison="-"
        if [ -n "$score" ] && [ -n "$metro_jvm_score" ] && [ "$metro_jvm_score" != "0" ]; then
            if [ "$mode" = "metro" ]; then
                comparison="baseline"
            else
                local pct=$(printf "%.1f" "$(echo "scale=4; (($score - $metro_jvm_score) / $metro_jvm_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    # Add + sign for positive percentages (slower than baseline)
                    if [[ "$pct" != -* ]]; then
                        comparison="+${pct}%"
                    else
                        comparison="${pct}%"
                    fi
                fi
            fi
        fi

        local display_score="${score:-N/A}"
        if [ -n "$score" ]; then
            display_score=$(printf "%.2f" "$score")
        fi

        echo "| $mode | $display_score | $comparison |" >> "$summary_file"
    done

    cat >> "$summary_file" << EOF

## Android Benchmarks (Macrobenchmark)

Cold startup time including graph initialization (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

    # Collect Android macrobenchmark results
    local metro_android_score=""

    for mode in "${MODE_ARRAY[@]}"; do
        local android_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
        local score=$(extract_android_macro_score "$android_dir")

        if [ "$mode" = "metro" ]; then
            metro_android_score="$score"
        fi

        # Calculate comparison
        local comparison="-"
        if [ -n "$score" ] && [ -n "$metro_android_score" ] && [ "$metro_android_score" != "0" ]; then
            if [ "$mode" = "metro" ]; then
                comparison="baseline"
            else
                local pct=$(printf "%.1f" "$(echo "scale=4; (($score - $metro_android_score) / $metro_android_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    # Add + sign for positive percentages (slower than baseline)
                    if [[ "$pct" != -* ]]; then
                        comparison="+${pct}%"
                    else
                        comparison="${pct}%"
                    fi
                fi
            fi
        fi

        local display_score="${score:-N/A}"
        if [ -n "$score" ]; then
            display_score=$(printf "%.0f" "$score")
        fi

        echo "| $mode | $display_score | $comparison |" >> "$summary_file"
    done

    cat >> "$summary_file" << EOF

## Android Benchmarks (Microbenchmark)

Graph creation and initialization time on Android (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

    # Collect Android microbenchmark results
    local metro_android_micro_score=""

    for mode in "${MODE_ARRAY[@]}"; do
        local android_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
        local score=$(extract_android_micro_score "$android_dir")

        if [ "$mode" = "metro" ]; then
            metro_android_micro_score="$score"
        fi

        # Calculate comparison
        local comparison="-"
        if [ -n "$score" ] && [ -n "$metro_android_micro_score" ] && [ "$metro_android_micro_score" != "0" ]; then
            if [ "$mode" = "metro" ]; then
                comparison="baseline"
            else
                local pct=$(printf "%.1f" "$(echo "scale=4; (($score - $metro_android_micro_score) / $metro_android_micro_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    # Add + sign for positive percentages (slower than baseline)
                    if [[ "$pct" != -* ]]; then
                        comparison="+${pct}%"
                    else
                        comparison="${pct}%"
                    fi
                fi
            fi
        fi

        local display_score="${score:-N/A}"
        if [ -n "$score" ]; then
            display_score=$(printf "%.3f" "$score")
        fi

        echo "| $mode | $display_score | $comparison |" >> "$summary_file"
    done

    cat >> "$summary_file" << EOF

## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`jvm_<mode>/\` - JMH benchmark results
- \`android_<mode>/\` - Android benchmark results
EOF

    print_success "Summary saved to $summary_file"
    echo ""
    cat "$summary_file"
}

run_jvm_benchmarks() {
    print_header "Running JVM Startup Benchmarks"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        print_info "Benchmarking: $mode"
        run_jvm_benchmark "$mode" || true
    done
}

run_android_benchmarks() {
    print_header "Running Android Startup Benchmarks"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        print_info "Benchmarking: $mode"
        run_android_benchmark "$mode" || true
    done
}

main() {
    local command="${1:-all}"
    shift || true

    # Parse options
    while [[ $# -gt 0 ]]; do
        case $1 in
            --modes)
                MODES="$2"
                shift 2
                ;;
            --count)
                MODULE_COUNT="$2"
                shift 2
                ;;
            --timestamp)
                TIMESTAMP="$2"
                shift 2
                ;;
            *)
                print_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done

    mkdir -p "$RESULTS_DIR/${TIMESTAMP}"

    case "$command" in
        jvm)
            run_jvm_benchmarks
            generate_summary
            ;;
        android)
            run_android_benchmarks
            generate_summary
            ;;
        all)
            run_jvm_benchmarks
            run_android_benchmarks
            generate_summary
            ;;
        summary)
            # Just regenerate the summary from existing results
            generate_summary
            ;;
        help|--help|-h)
            show_usage
            exit 0
            ;;
        *)
            print_error "Unknown command: $command"
            show_usage
            exit 1
            ;;
    esac

    print_header "Benchmarks Complete"
    echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
    echo ""
}

main "$@"
