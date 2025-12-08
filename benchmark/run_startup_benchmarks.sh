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

# Git refs
SINGLE_REF=""
COMPARE_REF1=""
COMPARE_REF2=""
ORIGINAL_GIT_REF=""
ORIGINAL_GIT_IS_BRANCH=false
# Whether to re-run non-metro modes in ref2 (default: false to save time)
RERUN_NON_METRO=false
# Whether to include macrobenchmarks (disabled by default as startup time is low-signal for DI perf)
INCLUDE_MACROBENCHMARK=false

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

# Save current git state (branch or commit)
save_git_state() {
    # Check if we're on a branch or in detached HEAD state
    local current_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "")
    if [ -n "$current_branch" ]; then
        ORIGINAL_GIT_REF="$current_branch"
        ORIGINAL_GIT_IS_BRANCH=true
        print_info "Saved current branch: $ORIGINAL_GIT_REF"
    else
        # Detached HEAD - save the commit hash
        ORIGINAL_GIT_REF=$(git rev-parse HEAD)
        ORIGINAL_GIT_IS_BRANCH=false
        print_info "Saved current commit: ${ORIGINAL_GIT_REF:0:12}"
    fi
}

# Restore to original git state
restore_git_state() {
    if [ -z "$ORIGINAL_GIT_REF" ]; then
        print_error "No git state saved to restore"
        return 1
    fi

    print_step "Restoring to original git state..."
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
    print_step "Checking out: $ref"
    git checkout "$ref" 2>/dev/null || {
        print_error "Failed to checkout: $ref"
        return 1
    }
    local short_ref=$(git rev-parse --short HEAD)
    print_success "Checked out: $ref ($short_ref)"
}

# Get a short display name for a git ref
get_ref_display_name() {
    local ref="$1"
    # Try to resolve to a short commit hash
    local short_hash=$(git rev-parse --short "$ref" 2>/dev/null || echo "$ref")
    # If it's a branch name, use that; otherwise use the short hash
    if git show-ref --verify --quiet "refs/heads/$ref" 2>/dev/null; then
        echo "$ref"
    elif git show-ref --verify --quiet "refs/remotes/origin/$ref" 2>/dev/null; then
        echo "$ref"
    else
        echo "$short_hash"
    fi
}

# Get a filesystem-safe name for a git ref
get_ref_safe_name() {
    local ref="$1"
    # Replace slashes and other special chars with underscores
    echo "$ref" | sed 's/[^a-zA-Z0-9._-]/_/g'
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
    echo "  single    Run benchmarks on a single git ref"
    echo "  compare   Compare benchmarks across two git refs (branches or commits)"
    echo "  summary   Regenerate summary from existing results (use with --timestamp)"
    echo "  help      Show this help message"
    echo ""
    echo "Options:"
    echo "  --modes <list>          Comma-separated list of modes to benchmark"
    echo "                          Available: metro, anvil-ksp, anvil-kapt, kotlin-inject-anvil"
    echo "                          Default: metro,anvil-ksp,kotlin-inject-anvil"
    echo "  --count <n>             Number of modules to generate (default: 500)"
    echo "  --timestamp <ts>        Use specific timestamp for results directory"
    echo "  --include-macrobenchmark  Include Android macrobenchmarks (startup time)"
    echo "                          Disabled by default as startup time is low-signal for DI perf"
    echo ""
    echo "Single Options:"
    echo "  --ref <ref>         Git ref to benchmark - branch name or commit hash"
    echo "  --benchmark <type>  Benchmark type: jvm, android, or all (default: jvm)"
    echo ""
    echo "Compare Options:"
    echo "  --ref1 <ref>        First git ref (baseline) - branch name or commit hash"
    echo "  --ref2 <ref>        Second git ref to compare against baseline"
    echo "  --benchmark <type>  Benchmark type for compare: jvm, android, or all (default: jvm)"
    echo "  --rerun-non-metro   Re-run non-metro modes on ref2 (default: only run metro on ref2)"
    echo "                      When disabled (default), ref2 uses ref1's non-metro results for comparison"
    echo ""
    echo "Examples:"
    echo "  $0 jvm                              # Run JVM benchmarks for all modes"
    echo "  $0 jvm --modes metro,anvil-ksp      # Run JVM benchmarks for specific modes"
    echo "  $0 all --count 250                  # Run all benchmarks with 250 modules"
    echo "  $0 android --include-macrobenchmark # Run Android benchmarks including macrobenchmarks"
    echo "  $0 summary --timestamp 20251205_125203 --modes metro,anvil-ksp"
    echo ""
    echo "  # Run benchmarks on a single git ref:"
    echo "  $0 single --ref main"
    echo "  $0 single --ref feature-branch --modes metro,anvil-ksp --benchmark jvm"
    echo ""
    echo "  # Compare benchmarks across git refs:"
    echo "  $0 compare --ref1 main --ref2 feature-branch"
    echo "  $0 compare --ref1 abc123 --ref2 def456 --benchmark all"
    echo "  $0 compare --ref1 v1.0.0 --ref2 HEAD --modes metro"
    echo "  $0 compare --ref1 main --ref2 feature --rerun-non-metro  # Re-run all modes on both refs"
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
        anvil-ksp|anvil-kapt|kotlin-inject-anvil)
            # Disable incremental processing and build cache to avoid flaky KSP/KAPT builds
            echo "--no-build-cache -Pksp.incremental=false -Pkotlin.incremental=false"
            ;;
        *)
            echo ""
            ;;
    esac
}

# Setup project for a specific mode (clean and generate)
setup_for_mode() {
    local mode="$1"
    clean_build_artifacts

    print_step "Generating project for $mode..."
    local gen_args=$(get_generator_args "$mode")
    kotlin generate-projects.main.kts $gen_args --count "$MODULE_COUNT" > /dev/null
}

# Run JMH benchmark only (no clean/generate)
run_jvm_benchmark_only() {
    local mode="$1"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
    mkdir -p "$output_dir"

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

# Run JMH benchmark for a specific mode (with clean/generate)
run_jvm_benchmark() {
    local mode="$1"
    setup_for_mode "$mode"
    run_jvm_benchmark_only "$mode"
}

# Run Android benchmark only (no clean/generate)
run_android_benchmark_only() {
    local mode="$1"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
    mkdir -p "$output_dir"

    local gradle_args=$(get_gradle_args "$mode")

    # Build tasks - only include macrobenchmark if enabled
    local build_tasks=":startup-android:app:assembleRelease :startup-android:microbenchmark:assembleBenchmark"
    if [ "$INCLUDE_MACROBENCHMARK" = true ]; then
        build_tasks="$build_tasks :startup-android:benchmark:assembleBenchmark"
    fi

    print_step "Building Android app for $mode..."
    if ! ./gradlew --quiet $gradle_args $build_tasks 2>&1; then
        print_error "Android build failed for $mode"
        return 1
    fi

    # Run macrobenchmark only if enabled
    if [ "$INCLUDE_MACROBENCHMARK" = true ]; then
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

# Run Android benchmark for a specific mode (with clean/generate)
run_android_benchmark() {
    local mode="$1"
    setup_for_mode "$mode"
    run_android_benchmark_only "$mode"
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

    # Only include macrobenchmark section if enabled or if results exist
    if [ "$INCLUDE_MACROBENCHMARK" = true ] || [ -f "$RESULTS_DIR/${TIMESTAMP}/android_metro/macro-benchmark-output.txt" ]; then
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
    fi

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

# Run all benchmarks grouped by mode (build once per mode)
run_all_benchmarks() {
    print_header "Running All Startup Benchmarks (Grouped by Mode)"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        print_header "Benchmarking: $mode"

        # Setup once for this mode
        setup_for_mode "$mode"

        # Run JVM benchmarks
        print_info "Running JVM benchmarks for $mode..."
        run_jvm_benchmark_only "$mode" || true

        # Run Android benchmarks (reuses the same generated project)
        print_info "Running Android benchmarks for $mode..."
        run_android_benchmark_only "$mode" || true
    done
}

# Run benchmarks for a specific git ref
# Arguments: ref, benchmark_type (jvm|android|all), ref_label, is_second_ref
run_benchmarks_for_ref() {
    local ref="$1"
    local benchmark_type="$2"
    local ref_label="$3"
    local is_second_ref="${4:-false}"

    print_header "Running benchmarks for: $ref_label"

    # Checkout the ref
    checkout_ref "$ref" || return 1

    # Create ref-specific results directory
    local ref_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}"
    mkdir -p "$ref_dir"

    # Save the commit hash for reference
    git rev-parse HEAD > "$ref_dir/commit.txt"
    git log -1 --format='%h %s' > "$ref_dir/commit-info.txt"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        # Skip non-metro modes on second ref unless RERUN_NON_METRO is true
        if [ "$is_second_ref" = true ] && [ "$mode" != "metro" ] && [ "$RERUN_NON_METRO" != true ]; then
            print_info "Skipping $mode for $ref_label (using ref1 results for comparison)"
            continue
        fi

        print_header "Benchmarking $mode for $ref_label"

        # Setup for this mode
        setup_for_mode "$mode"

        case "$benchmark_type" in
            jvm)
                run_jvm_benchmark_only "$mode" || true
                # Move results to ref-specific directory
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}" ]; then
                    mkdir -p "$ref_dir/jvm_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"/* "$ref_dir/jvm_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
                fi
                ;;
            android)
                run_android_benchmark_only "$mode" || true
                # Move results to ref-specific directory
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/android_${mode}" ]; then
                    mkdir -p "$ref_dir/android_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/android_${mode}"/* "$ref_dir/android_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
                fi
                ;;
            all)
                run_jvm_benchmark_only "$mode" || true
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}" ]; then
                    mkdir -p "$ref_dir/jvm_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"/* "$ref_dir/jvm_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
                fi
                run_android_benchmark_only "$mode" || true
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/android_${mode}" ]; then
                    mkdir -p "$ref_dir/android_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/android_${mode}"/* "$ref_dir/android_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
                fi
                ;;
        esac
    done

    print_success "Completed benchmarks for $ref_label"
}

# Extract JMH score for a ref
extract_jmh_score_for_ref() {
    local ref_label="$1"
    local mode="$2"
    local jvm_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/jvm_${mode}"
    local score=""

    # Try to get score from JSON first, then text output
    if [ -f "$jvm_dir/results.json" ]; then
        score=$(extract_jmh_score "$jvm_dir/results.json")
    fi

    # Fallback: parse from results.txt or jmh-output.txt
    if [ -z "$score" ] && [ -f "$jvm_dir/results.txt" ]; then
        score=$(grep 'graphCreationAndInitialization' "$jvm_dir/results.txt" 2>/dev/null | awk '{print $4}' || echo "")
    fi
    if [ -z "$score" ] && [ -f "$jvm_dir/jmh-output.txt" ]; then
        score=$(grep 'graphCreationAndInitialization' "$jvm_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
    fi

    echo "$score"
}

# Extract Android macro score for a ref
extract_android_macro_score_for_ref() {
    local ref_label="$1"
    local mode="$2"
    local android_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/android_${mode}"
    extract_android_macro_score "$android_dir"
}

# Extract Android micro score for a ref
extract_android_micro_score_for_ref() {
    local ref_label="$1"
    local mode="$2"
    local android_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/android_${mode}"
    extract_android_micro_score "$android_dir"
}

# Check if a mode was run for a given ref (by checking if results exist)
mode_was_run_for_ref() {
    local ref_label="$1"
    local mode="$2"
    local benchmark_type="$3"
    local ref_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}"

    # Check based on benchmark type
    case "$benchmark_type" in
        jvm)
            [ -d "$ref_dir/jvm_${mode}" ]
            ;;
        android)
            [ -d "$ref_dir/android_${mode}" ]
            ;;
        all)
            [ -d "$ref_dir/jvm_${mode}" ] || [ -d "$ref_dir/android_${mode}" ]
            ;;
    esac
}

# Generate comparison summary between two refs
# When non-metro modes are not run on ref2, we compare ref2's metro against ref1's non-metro results
generate_comparison_summary() {
    local ref1_label="$1"
    local ref2_label="$2"
    local benchmark_type="$3"

    local summary_file="$RESULTS_DIR/${TIMESTAMP}/comparison-summary.md"
    local ref1_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    local ref2_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Comparison Summary"

    # Determine which modes were actually run on ref2
    local ref2_modes=""
    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        if mode_was_run_for_ref "$ref2_label" "$mode" "$benchmark_type"; then
            if [ -n "$ref2_modes" ]; then
                ref2_modes="${ref2_modes},"
            fi
            ref2_modes="${ref2_modes}${mode}"
        fi
    done

    # Get ref2 metro scores for comparison with ref1 non-metro modes
    local ref2_metro_jvm_score=$(extract_jmh_score_for_ref "$ref2_label" "metro")
    local ref2_metro_macro_score=$(extract_android_macro_score_for_ref "$ref2_label" "metro")
    local ref2_metro_micro_score=$(extract_android_micro_score_for_ref "$ref2_label" "metro")

    cat > "$summary_file" << EOF
# Benchmark Comparison: $ref1_label vs $ref2_label

**Date:** $(date)
**Module Count:** $MODULE_COUNT
**Modes benchmarked on ref1:** $MODES
**Modes benchmarked on ref2:** ${ref2_modes:-metro}

## Git Refs

| Ref | Commit |
|-----|--------|
| $ref1_label (baseline) | $ref1_commit |
| $ref2_label | $ref2_commit |

EOF

    if [ "$benchmark_type" = "jvm" ] || [ "$benchmark_type" = "all" ]; then
        cat >> "$summary_file" << EOF
## JVM Benchmarks (JMH)

Graph creation and initialization time (lower is better):

| Framework | $ref1_label (baseline) | $ref2_label | Difference |
|-----------|------------------------|-------------|------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score1=$(extract_jmh_score_for_ref "$ref1_label" "$mode")

            # Check if this mode was run on ref2
            local mode_ran_on_ref2=false
            if mode_was_run_for_ref "$ref2_label" "$mode" "jvm"; then
                mode_ran_on_ref2=true
            fi

            local score2=""
            local display2="N/A"
            local diff="-"

            if [ "$mode_ran_on_ref2" = true ]; then
                # Mode was run on ref2, use its result
                score2=$(extract_jmh_score_for_ref "$ref2_label" "$mode")
                if [ -n "$score2" ]; then
                    display2=$(printf "%.3f ms" "$score2")
                fi
            elif [ "$mode" != "metro" ] && [ -n "$ref2_metro_jvm_score" ]; then
                # Mode was NOT run on ref2 and it's not metro
                # Compare ref2's metro against ref1's this mode
                score2="$ref2_metro_jvm_score"
                display2="-"
            fi

            local display1="${score1:-N/A}"
            if [ -n "$score1" ]; then
                display1=$(printf "%.3f ms" "$score1")
            fi

            if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                local pct=$(printf "%.1f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    if [[ "$pct" == -* ]]; then
                        diff="${pct}% (faster)"
                    elif [[ "$pct" == "0.0" ]]; then
                        diff="no change"
                    else
                        diff="+${pct}% (slower)"
                    fi
                fi
            fi

            echo "| $mode | $display1 | $display2 | $diff |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    fi

    if [ "$benchmark_type" = "android" ] || [ "$benchmark_type" = "all" ]; then
        # Only include macrobenchmark section if enabled or if results exist
        local has_macro_results=false
        if [ -n "$ref2_metro_macro_score" ] || [ -d "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_metro" ]; then
            # Check if macro results actually exist
            local macro_json=$(find "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_metro" -name "*benchmarkData.json" -not -path "*/microbenchmark/*" -type f 2>/dev/null | head -1)
            if [ -n "$macro_json" ]; then
                has_macro_results=true
            fi
        fi

        if [ "$INCLUDE_MACROBENCHMARK" = true ] || [ "$has_macro_results" = true ]; then
            cat >> "$summary_file" << EOF
## Android Benchmarks (Macrobenchmark)

Cold startup time including graph initialization (lower is better):

| Framework | $ref1_label (baseline) | $ref2_label | Difference |
|-----------|------------------------|-------------|------------|
EOF

            for mode in "${MODE_ARRAY[@]}"; do
                local score1=$(extract_android_macro_score_for_ref "$ref1_label" "$mode")

                # Check if this mode was run on ref2
                local mode_ran_on_ref2=false
                if mode_was_run_for_ref "$ref2_label" "$mode" "android"; then
                    mode_ran_on_ref2=true
                fi

                local score2=""
                local display2="N/A"
                local diff="-"

                if [ "$mode_ran_on_ref2" = true ]; then
                    # Mode was run on ref2, use its result
                    score2=$(extract_android_macro_score_for_ref "$ref2_label" "$mode")
                    if [ -n "$score2" ]; then
                        display2=$(printf "%.0f ms" "$score2")
                    fi
                elif [ "$mode" != "metro" ] && [ -n "$ref2_metro_macro_score" ]; then
                    # Mode was NOT run on ref2 and it's not metro
                    # Compare ref2's metro against ref1's this mode
                    score2="$ref2_metro_macro_score"
                    display2="-"
                fi

                local display1="${score1:-N/A}"
                if [ -n "$score1" ]; then
                    display1=$(printf "%.0f ms" "$score1")
                fi

                if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                    local pct=$(printf "%.1f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct" ]; then
                        if [[ "$pct" == -* ]]; then
                            diff="${pct}% (faster)"
                        elif [[ "$pct" == "0.0" ]]; then
                            diff="no change"
                        else
                            diff="+${pct}% (slower)"
                        fi
                    fi
                fi

                echo "| $mode | $display1 | $display2 | $diff |" >> "$summary_file"
            done

            echo "" >> "$summary_file"
        fi

        cat >> "$summary_file" << EOF
## Android Benchmarks (Microbenchmark)

Graph creation and initialization time on Android (lower is better):

| Framework | $ref1_label (baseline) | $ref2_label | Difference |
|-----------|------------------------|-------------|------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score1=$(extract_android_micro_score_for_ref "$ref1_label" "$mode")

            # Check if this mode was run on ref2
            local mode_ran_on_ref2=false
            if mode_was_run_for_ref "$ref2_label" "$mode" "android"; then
                mode_ran_on_ref2=true
            fi

            local score2=""
            local display2="N/A"
            local diff="-"

            if [ "$mode_ran_on_ref2" = true ]; then
                # Mode was run on ref2, use its result
                score2=$(extract_android_micro_score_for_ref "$ref2_label" "$mode")
                if [ -n "$score2" ]; then
                    display2=$(printf "%.3f ms" "$score2")
                fi
            elif [ "$mode" != "metro" ] && [ -n "$ref2_metro_micro_score" ]; then
                # Mode was NOT run on ref2 and it's not metro
                # Compare ref2's metro against ref1's this mode
                score2="$ref2_metro_micro_score"
                display2="-"
            fi

            local display1="${score1:-N/A}"
            if [ -n "$score1" ]; then
                display1=$(printf "%.3f ms" "$score1")
            fi

            if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                local pct=$(printf "%.1f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    if [[ "$pct" == -* ]]; then
                        diff="${pct}% (faster)"
                    elif [[ "$pct" == "0.0" ]]; then
                        diff="no change"
                    else
                        diff="+${pct}% (slower)"
                    fi
                fi
            fi

            echo "| $mode | $display1 | $display2 | $diff |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    fi

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
    local benchmark_type="$2"

    local summary_file="$RESULTS_DIR/${TIMESTAMP}/single-summary.md"
    local ref_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Single Ref Summary"

    cat > "$summary_file" << EOF
# Startup Benchmark Results: $ref_label

**Date:** $(date)
**Module Count:** $MODULE_COUNT
**Modes:** $MODES
**Commit:** $ref_commit

EOF

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"

    if [ "$benchmark_type" = "jvm" ] || [ "$benchmark_type" = "all" ]; then
        cat >> "$summary_file" << EOF
## JVM Benchmarks (JMH)

Graph creation and initialization time (lower is better):

| Framework | Time (ms) |
|-----------|-----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score=$(extract_jmh_score_for_ref "$ref_label" "$mode")
            local display="${score:-N/A}"
            if [ -n "$score" ]; then
                display=$(printf "%.3f" "$score")
            fi
            echo "| $mode | $display |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    fi

    if [ "$benchmark_type" = "android" ] || [ "$benchmark_type" = "all" ]; then
        # Check if macro results exist
        local has_macro_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            local macro_score=$(extract_android_macro_score_for_ref "$ref_label" "$mode")
            if [ -n "$macro_score" ]; then
                has_macro_results=true
                break
            fi
        done

        if [ "$INCLUDE_MACROBENCHMARK" = true ] || [ "$has_macro_results" = true ]; then
            cat >> "$summary_file" << EOF
## Android Benchmarks (Macrobenchmark)

Cold startup time including graph initialization (lower is better):

| Framework | Time (ms) |
|-----------|-----------|
EOF

            for mode in "${MODE_ARRAY[@]}"; do
                local score=$(extract_android_macro_score_for_ref "$ref_label" "$mode")
                local display="${score:-N/A}"
                if [ -n "$score" ]; then
                    display=$(printf "%.0f" "$score")
                fi
                echo "| $mode | $display |" >> "$summary_file"
            done

            echo "" >> "$summary_file"
        fi

        cat >> "$summary_file" << EOF
## Android Benchmarks (Microbenchmark)

Graph creation and initialization time on Android (lower is better):

| Framework | Time (ms) |
|-----------|-----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score=$(extract_android_micro_score_for_ref "$ref_label" "$mode")
            local display="${score:-N/A}"
            if [ -n "$score" ]; then
                display=$(printf "%.3f" "$score")
            fi
            echo "| $mode | $display |" >> "$summary_file"
        done

        echo "" >> "$summary_file"
    fi

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
    local benchmark_type="${COMPARE_BENCHMARK_TYPE:-jvm}"

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
    print_info "Ref: $SINGLE_REF"
    print_info "Benchmark type: $benchmark_type"
    print_info "Modes: $MODES"
    echo ""

    # Save current git state
    save_git_state

    # Create safe label for directory name
    local ref_label=$(get_ref_safe_name "$SINGLE_REF")

    # Set up trap to restore git state on exit
    trap 'restore_git_state' EXIT

    # Run benchmarks for the ref (all modes, not second ref)
    run_benchmarks_for_ref "$SINGLE_REF" "$benchmark_type" "$ref_label" false || {
        print_error "Failed to run benchmarks for $SINGLE_REF"
        exit 1
    }

    # Generate summary
    generate_single_summary "$ref_label" "$benchmark_type"

    print_header "Benchmarks Complete"
    echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
    echo ""
}

# Run compare command
run_compare() {
    local benchmark_type="${COMPARE_BENCHMARK_TYPE:-jvm}"

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
    print_info "Baseline (ref1): $COMPARE_REF1"
    print_info "Compare (ref2):  $COMPARE_REF2"
    print_info "Benchmark type:  $benchmark_type"
    print_info "Modes:           $MODES"
    if [ "$RERUN_NON_METRO" = true ]; then
        print_info "Re-run non-metro on ref2: yes"
    else
        print_info "Re-run non-metro on ref2: no (using ref1 results)"
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

    # Set up trap to restore git state on exit
    trap 'restore_git_state' EXIT

    # Run benchmarks for ref1 (baseline) - run all modes
    run_benchmarks_for_ref "$COMPARE_REF1" "$benchmark_type" "$ref1_label" false || {
        print_error "Failed to run benchmarks for $COMPARE_REF1"
        exit 1
    }

    # Run benchmarks for ref2 - only metro by default (is_second_ref=true)
    run_benchmarks_for_ref "$COMPARE_REF2" "$benchmark_type" "$ref2_label" true || {
        print_error "Failed to run benchmarks for $COMPARE_REF2"
        exit 1
    }

    # Generate comparison summary
    generate_comparison_summary "$ref1_label" "$ref2_label" "$benchmark_type"

    # Restore will happen via trap
}

# Default benchmark type for compare
COMPARE_BENCHMARK_TYPE="jvm"

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
            --benchmark)
                COMPARE_BENCHMARK_TYPE="$2"
                shift 2
                ;;
            --rerun-non-metro)
                RERUN_NON_METRO=true
                shift
                ;;
            --include-macrobenchmark)
                INCLUDE_MACROBENCHMARK=true
                shift
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
            run_all_benchmarks
            generate_summary
            ;;
        summary)
            # Just regenerate the summary from existing results
            generate_summary
            ;;
        single)
            run_single
            ;;
        compare)
            run_compare
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

    if [ "$command" != "compare" ] && [ "$command" != "single" ]; then
        print_header "Benchmarks Complete"
        echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
        echo ""
    fi
}

main "$@"
