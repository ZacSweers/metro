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
    echo "  jvm-r8    Run JVM startup benchmarks with R8-minified classes (Metro only)"
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
    echo "  --benchmark <type>  Benchmark type: jvm, jvm-r8, android, or all (default: jvm)"
    echo ""
    echo "Compare Options:"
    echo "  --ref1 <ref>        First git ref (baseline) - branch name or commit hash"
    echo "  --ref2 <ref>        Second git ref to compare against baseline"
    echo "  --benchmark <type>  Benchmark type for compare: jvm, jvm-r8, android, or all (default: jvm)"
    echo "  --rerun-non-metro   Re-run non-metro modes on ref2 (default: only run metro on ref2)"
    echo "                      When disabled (default), ref2 uses ref1's non-metro results for comparison"
    echo ""
    echo "Examples:"
    echo "  $0 jvm                              # Run JVM benchmarks for all modes"
    echo "  $0 jvm-r8                           # Run JVM benchmarks with R8-minified Metro"
    echo "  $0 jvm --modes metro,anvil-ksp      # Run JVM benchmarks for specific modes"
    echo "  $0 all --count 250                  # Run all benchmarks with 250 modules"
    echo "  $0 android --include-macrobenchmark # Run Android benchmarks including macrobenchmarks"
    echo "  $0 summary --timestamp 20251205_125203 --modes metro,anvil-ksp"
    echo ""
    echo "  # Run benchmarks on a single git ref:"
    echo "  $0 single --ref main"
    echo "  $0 single --ref feature-branch --modes metro,anvil-ksp --benchmark jvm"
    echo "  $0 single --ref main --benchmark jvm-r8  # Run R8-minified Metro benchmark"
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

# Run JMH R8 benchmark only (no clean/generate) - Metro only
run_jvm_r8_benchmark_only() {
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"
    mkdir -p "$output_dir"

    print_step "Running JMH R8 benchmark for metro (minified)..."

    # Run JMH with R8-minified classes and capture output
    if ./gradlew --quiet :startup-jvm-minified:jmh 2>&1 | tee "$output_dir/jmh-output.txt"; then
        # Copy JMH results
        if [ -d "startup-jvm-minified/build/results/jmh" ]; then
            cp -r startup-jvm-minified/build/results/jmh/* "$output_dir/" 2>/dev/null || true
        fi
        print_success "JMH R8 benchmark complete for metro"
    else
        print_error "JMH R8 benchmark failed for metro"
        return 1
    fi
}

# Run JMH R8 benchmark for metro (with clean/generate)
run_jvm_r8_benchmark() {
    setup_for_mode "metro"
    run_jvm_r8_benchmark_only
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

    # Add JVM R8 results if they exist
    local jvm_r8_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"
    if [ -d "$jvm_r8_dir" ]; then
        cat >> "$summary_file" << EOF

## JVM Benchmarks - R8 Minified (JMH)

Graph creation and initialization time with R8 optimization (Metro only, lower is better):

| Framework | Time (ms) | vs Metro (non-R8) |
|-----------|-----------|-------------------|
EOF

        local r8_score=""
        if [ -f "$jvm_r8_dir/results.json" ]; then
            r8_score=$(extract_jmh_score "$jvm_r8_dir/results.json")
        fi
        if [ -z "$r8_score" ] && [ -f "$jvm_r8_dir/jmh-output.txt" ]; then
            r8_score=$(grep 'graphCreationAndInitialization' "$jvm_r8_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
        fi

        local r8_comparison="-"
        if [ -n "$r8_score" ] && [ -n "$metro_jvm_score" ] && [ "$metro_jvm_score" != "0" ]; then
            local pct=$(printf "%.1f" "$(echo "scale=4; (($r8_score - $metro_jvm_score) / $metro_jvm_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
            if [ -n "$pct" ]; then
                if [[ "$pct" != -* ]]; then
                    r8_comparison="+${pct}%"
                else
                    r8_comparison="${pct}%"
                fi
            fi
        fi

        local r8_display_score="${r8_score:-N/A}"
        if [ -n "$r8_score" ]; then
            r8_display_score=$(printf "%.2f" "$r8_score")
        fi

        echo "| Metro (R8) | $r8_display_score | $r8_comparison |" >> "$summary_file"
    fi

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

    # Generate HTML report for non-ref benchmarks
    generate_non_ref_html_report "all"
}

# Generate HTML report for non-ref benchmarks (using jvm_<mode> directory structure)
generate_non_ref_html_report() {
    local benchmark_type="$1"
    local html_file="$RESULTS_DIR/${TIMESTAMP}/startup-benchmark-report.html"

    print_header "Generating HTML Report"

    local json_data
    json_data=$(build_non_ref_benchmark_json "$benchmark_type")

    cat > "$html_file" << 'HTMLHEAD'
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Metro Startup Benchmark Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root { --metro-color: #4CAF50; --anvil-ksp-color: #2196F3; --anvil-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; }
        * { box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 0; background: #f5f5f5; color: #333; }
        .header { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: white; padding: 2rem; text-align: center; }
        .header h1 { margin: 0 0 0.5rem 0; font-weight: 300; font-size: 2rem; }
        .header .subtitle { opacity: 0.8; font-size: 0.9rem; }
        .container { max-width: 1400px; margin: 0 auto; padding: 2rem; }
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
        .legend { display: flex; gap: 1.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
        .legend-item { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; }
        .legend-color { width: 16px; height: 16px; border-radius: 3px; }
        .no-data { color: #999; font-style: italic; }
        .metadata-section { background: white; border-radius: 8px; padding: 1.5rem; margin-top: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .metadata-section h2 { margin: 0 0 1rem 0; font-size: 1.1rem; font-weight: 500; color: #666; border-bottom: 2px solid #eee; padding-bottom: 0.5rem; }
        .metadata-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1.5rem; }
        .metadata-group h3 { margin: 0 0 0.75rem 0; font-size: 0.9rem; font-weight: 600; color: #555; text-transform: uppercase; }
        .metadata-group dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 0.25rem 1rem; font-size: 0.85rem; }
        .metadata-group dt { color: #888; }
        .metadata-group dd { margin: 0; font-family: 'SF Mono', Monaco, monospace; color: #333; word-break: break-all; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Metro Startup Benchmark Results</h1>
        <div class="subtitle" id="date"></div>
    </div>
    <div class="container">
        <div id="benchmarks"></div>
        <div class="metadata-section" id="metadata"></div>
    </div>
<script>
const benchmarkData =
HTMLHEAD

    echo "$json_data" >> "$html_file"

    cat >> "$html_file" << 'HTMLTAIL'
;
const colors = { 'metro': '#4CAF50', 'anvil_ksp': '#2196F3', 'anvil_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0' };
let selectedBaseline = 'metro';

function formatTime(ms, unit) {
    if (ms === null || ms === undefined) return '—';
    if (unit === 'ms') {
        if (ms < 1) return ms.toFixed(3) + ' ms';
        if (ms < 100) return ms.toFixed(2) + ' ms';
        return ms.toFixed(0) + ' ms';
    }
    return ms.toFixed(2);
}

function calculateVsBaseline(value, baselineValue) {
    if (!value || !baselineValue) return { text: '—', class: '' };
    if (value === baselineValue) return { text: 'baseline', class: 'baseline' };
    const pct = ((value - baselineValue) / baselineValue * 100).toFixed(0);
    const mult = (value / baselineValue).toFixed(1);
    if (pct < 0) return { text: `${pct}% (${mult}x)`, class: 'faster' };
    return { text: `+${pct}% (${mult}x)`, class: 'slower' };
}

function renderSummaryStats() {
    const container = document.getElementById('summary-stats');
    let totalSpeedup = { anvil_ksp: 0, anvil_kapt: 0, kotlin_inject_anvil: 0 };
    let counts = { anvil_ksp: 0, anvil_kapt: 0, kotlin_inject_anvil: 0 };
    benchmarkData.benchmarks.forEach(benchmark => {
        const metroResult = benchmark.results.find(r => r.key === 'metro');
        if (!metroResult || !metroResult.value) return;
        benchmark.results.forEach(result => {
            if (result.key !== 'metro' && result.value) {
                totalSpeedup[result.key] += result.value / metroResult.value;
                counts[result.key]++;
            }
        });
    });
    let html = '';
    const names = { 'anvil_ksp': 'Anvil KSP', 'anvil_kapt': 'Anvil KAPT', 'kotlin_inject_anvil': 'kotlin-inject' };
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
            <table><thead><tr><th></th><th>Framework</th><th>Time</th><th>vs <span class="baseline-header">${getBaselineLabel()}</span></th></tr></thead><tbody id="table-${idx}"></tbody></table></div>`;
    });
    container.innerHTML = html;
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderChart(benchmark, idx); renderTable(benchmark, idx); });
}

const charts = [];
function renderChart(benchmark, idx) {
    const ctx = document.getElementById(`chart-${idx}`).getContext('2d');
    const labels = [], data = [], backgroundColors = [];
    benchmark.results.forEach(result => {
        labels.push(result.framework);
        data.push(result.value || 0);
        backgroundColors.push(colors[result.key]);
    });
    charts[idx] = new Chart(ctx, { type: 'bar', data: { labels, datasets: [{ label: 'Time', data, backgroundColor: backgroundColors.map(c => c + 'CC'), borderColor: backgroundColors, borderWidth: 2 }] }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false }, tooltip: { callbacks: { label: ctx => ctx.raw.toFixed(2) + ' ' + (benchmark.unit || 'ms') } } }, scales: { y: { beginAtZero: true, title: { display: true, text: 'Time (' + (benchmark.unit || 'ms') + ')' } } } } });
}

function renderTable(benchmark, idx) {
    const tbody = document.getElementById(`table-${idx}`);
    const baselineValue = benchmark.results.find(r => r.key === selectedBaseline)?.value;
    let html = '';
    benchmark.results.forEach(result => {
        const isBaseline = result.key === selectedBaseline;
        const vsBaseline = calculateVsBaseline(result.value, baselineValue);
        html += `<tr class="${isBaseline ? 'baseline-row' : ''}" data-key="${result.key}">
            <td class="baseline-select" onclick="setBaseline('${result.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>
            <td class="framework" style="color: ${colors[result.key]}">${result.framework}</td>
            <td class="numeric">${result.value ? formatTime(result.value, benchmark.unit) : '<span class="no-data">N/A</span>'}</td>
            <td class="numeric vs-baseline ${vsBaseline.class}">${vsBaseline.text}</td></tr>`;
    });
    tbody.innerHTML = html;
}

function setBaseline(key) {
    selectedBaseline = key;
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderTable(benchmark, idx); });
    document.querySelectorAll('.baseline-header').forEach(el => { el.textContent = getBaselineLabel(); });
}

function renderMetadata() {
    const container = document.getElementById('metadata');
    if (!benchmarkData.metadata) { container.style.display = 'none'; return; }
    const m = benchmarkData.metadata;
    const hasAndroid = m.android?.device || m.android?.version;
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
                    <dt>JDK</dt><dd>${m.build?.jdk || '—'}</dd>
                    <dt>JVM Target</dt><dd>${m.build?.jvmTarget || '—'}</dd>
                    <dt>JMH Plugin</dt><dd>${m.build?.jmhPlugin || '—'}</dd>
                    <dt>AndroidX Benchmark</dt><dd>${m.build?.androidxBenchmark || '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>System</h3>
                <dl>
                    <dt>OS</dt><dd>${m.system?.os || '—'}</dd>
                    <dt>CPU</dt><dd>${m.system?.cpu || '—'}</dd>
                    <dt>RAM</dt><dd>${m.system?.ram || '—'}</dd>
                </dl>
            </div>
            ${hasAndroid ? `<div class="metadata-group">
                <h3>Android Device</h3>
                <dl>
                    <dt>Device</dt><dd>${m.android?.device || '—'}</dd>
                    <dt>Android Version</dt><dd>${m.android?.version || '—'}</dd>
                </dl>
            </div>` : ''}
        </div>`;
}

document.getElementById('date').textContent = new Date(benchmarkData.date).toLocaleString();
renderBenchmarks(); renderMetadata();
</script>
</body>
</html>
HTMLTAIL

    print_success "HTML report saved to $html_file"
}

# Build JSON data for non-ref startup benchmarks
build_non_ref_benchmark_json() {
    local benchmark_type="$1"
    IFS=',' read -ra MODE_ARRAY <<< "$MODES"

    # Get repo root and read metadata
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local versions_file="$repo_root/gradle/libs.versions.toml"

    # Helper to extract version from libs.versions.toml
    get_toml_version() {
        local key="$1"
        grep "^${key} = " "$versions_file" 2>/dev/null | sed 's/.*= *"\([^"]*\)".*/\1/' | head -1
    }

    # Get plugin version (format: id = "...", version = "X.Y.Z")
    get_plugin_version() {
        local plugin_id="$1"
        grep "$plugin_id" "$versions_file" 2>/dev/null | sed 's/.*version *= *"\([^"]*\)".*/\1/' | head -1
    }

    echo "{"
    echo '  "title": "Startup Benchmark Results",'
    echo '  "date": "'$(date -Iseconds)'",'
    echo '  "moduleCount": '"$MODULE_COUNT"','

    # Build metadata
    local kotlin_version=$(get_toml_version "kotlin")
    local dagger_version=$(get_toml_version "dagger")
    local ksp_version=$(get_toml_version "ksp")
    local kotlin_inject_version=$(get_toml_version "kotlinInject")
    local anvil_version=$(get_toml_version "anvil")
    local kotlin_inject_anvil_version=$(get_toml_version "kotlinInject-anvil")
    local jvm_target=$(get_toml_version "jvmTarget")

    # JMH and benchmark versions
    local jmh_version=$(get_plugin_version "me.champeau.jmh")
    local benchmark_version=$(get_plugin_version "androidx.benchmark")

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

    # Android device info (if adb is available)
    local android_device=""
    local android_version=""
    if command -v adb &> /dev/null; then
        android_device=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "")
        android_version=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "")
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
    echo '      "jdk": "'"$java_version"'",'
    echo '      "jvmTarget": "'"$jvm_target"'",'
    echo '      "jmhPlugin": "'"$jmh_version"'",'
    echo '      "androidxBenchmark": "'"$benchmark_version"'"'
    echo '    },'
    echo '    "system": {'
    echo '      "os": "'"$os_info"'",'
    echo '      "cpu": "'"$cpu_info"'",'
    echo '      "ram": "'"$ram_info"'"'
    echo '    },'
    echo '    "android": {'
    echo '      "device": "'"$android_device"'",'
    echo '      "version": "'"$android_version"'"'
    echo '    }'
    echo '  },'

    echo '  "benchmarks": ['

    local first_test=true

    # JVM section
    if [ "$benchmark_type" = "jvm" ] || [ "$benchmark_type" = "all" ]; then
        if [ "$first_test" = false ]; then echo ","; fi
        first_test=false

        echo '    {'
        echo '      "name": "JVM Startup (JMH)",'
        echo '      "key": "jvm",'
        echo '      "unit": "ms",'
        echo '      "results": ['

        local first_mode=true
        for mode in "${MODE_ARRAY[@]}"; do
            local mode_key mode_name
            case "$mode" in
                "metro") mode_key="metro"; mode_name="Metro" ;;
                "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                *) continue ;;
            esac

            if [ "$first_mode" = false ]; then echo ","; fi
            first_mode=false

            local score=""
            local jvm_dir="$RESULTS_DIR/${TIMESTAMP}/jvm_${mode}"
            if [ -f "$jvm_dir/results.json" ]; then
                score=$(extract_jmh_score "$jvm_dir/results.json")
            fi
            if [ -z "$score" ] && [ -f "$jvm_dir/results.txt" ]; then
                score=$(grep 'graphCreationAndInitialization' "$jvm_dir/results.txt" 2>/dev/null | awk '{print $4}' || echo "")
            fi
            if [ -z "$score" ] && [ -f "$jvm_dir/jmh-output.txt" ]; then
                score=$(grep 'graphCreationAndInitialization' "$jvm_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
            fi

            echo '        {'
            echo '          "framework": "'"$mode_name"'",'
            echo '          "key": "'"$mode_key"'",'
            if [ -n "$score" ]; then
                echo '          "value": '"$score"
            else
                echo '          "value": null'
            fi
            echo -n '        }'
        done

        echo ''
        echo '      ]'
        echo -n '    }'
    fi

    # Android macrobenchmark section
    if [ "$benchmark_type" = "android" ] || [ "$benchmark_type" = "all" ]; then
        local has_macro_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            local android_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
            local macro_score=$(extract_android_macro_score "$android_dir")
            if [ -n "$macro_score" ]; then
                has_macro_results=true
                break
            fi
        done

        if [ "$INCLUDE_MACROBENCHMARK" = true ] || [ "$has_macro_results" = true ]; then
            if [ "$first_test" = false ]; then echo ","; fi
            first_test=false

            echo '    {'
            echo '      "name": "Android Startup (Macrobenchmark)",'
            echo '      "key": "android_macro",'
            echo '      "unit": "ms",'
            echo '      "results": ['

            local first_mode=true
            for mode in "${MODE_ARRAY[@]}"; do
                local mode_key mode_name
                case "$mode" in
                    "metro") mode_key="metro"; mode_name="Metro" ;;
                    "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                    "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                    "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                    *) continue ;;
                esac

                if [ "$first_mode" = false ]; then echo ","; fi
                first_mode=false

                local android_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
                local score=$(extract_android_macro_score "$android_dir")

                echo '        {'
                echo '          "framework": "'"$mode_name"'",'
                echo '          "key": "'"$mode_key"'",'
                if [ -n "$score" ]; then
                    echo '          "value": '"$score"
                else
                    echo '          "value": null'
                fi
                echo -n '        }'
            done

            echo ''
            echo '      ]'
            echo -n '    }'
        fi

        # Android microbenchmark section
        if [ "$first_test" = false ]; then echo ","; fi
        first_test=false

        echo '    {'
        echo '      "name": "Android Component Init (Microbenchmark)",'
        echo '      "key": "android_micro",'
        echo '      "unit": "ms",'
        echo '      "results": ['

        local first_mode=true
        for mode in "${MODE_ARRAY[@]}"; do
            local mode_key mode_name
            case "$mode" in
                "metro") mode_key="metro"; mode_name="Metro" ;;
                "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                *) continue ;;
            esac

            if [ "$first_mode" = false ]; then echo ","; fi
            first_mode=false

            local android_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
            local score=$(extract_android_micro_score "$android_dir")

            echo '        {'
            echo '          "framework": "'"$mode_name"'",'
            echo '          "key": "'"$mode_key"'",'
            if [ -n "$score" ]; then
                echo '          "value": '"$score"
            else
                echo '          "value": null'
            fi
            echo -n '        }'
        done

        echo ''
        echo '      ]'
        echo -n '    }'
    fi

    echo ''
    echo '  ]'
    echo "}"
}

run_jvm_benchmarks() {
    print_header "Running JVM Startup Benchmarks"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        print_info "Benchmarking: $mode"
        run_jvm_benchmark "$mode" || true
    done
}

run_jvm_r8_benchmarks() {
    print_header "Running JVM R8 Startup Benchmarks (Metro Only)"
    run_jvm_r8_benchmark || true
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
            jvm-r8)
                # Only run R8 benchmarks for metro mode
                if [ "$mode" = "metro" ]; then
                    run_jvm_r8_benchmark_only || true
                    if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro" ]; then
                        mkdir -p "$ref_dir/jvm-r8_metro"
                        cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"/* "$ref_dir/jvm-r8_metro/" 2>/dev/null || true
                        rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"
                    fi
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
                # Run R8 benchmark for metro mode
                if [ "$mode" = "metro" ]; then
                    run_jvm_r8_benchmark_only || true
                    if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro" ]; then
                        mkdir -p "$ref_dir/jvm-r8_metro"
                        cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"/* "$ref_dir/jvm-r8_metro/" 2>/dev/null || true
                        rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"
                    fi
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
        # Get metro scores for "vs Metro" column
        local metro_jvm_score1=$(extract_jmh_score_for_ref "$ref1_label" "metro")
        local metro_jvm_score2=""
        if mode_was_run_for_ref "$ref2_label" "metro" "jvm"; then
            metro_jvm_score2=$(extract_jmh_score_for_ref "$ref2_label" "metro")
        fi

        cat >> "$summary_file" << EOF
## JVM Benchmarks (JMH)

Graph creation and initialization time (lower is better):

| Framework | $ref1_label | vs Metro | $ref2_label | vs Metro | Difference |
|-----------|-------------|----------|-------------|----------|------------|
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
            local vs_metro1="—"
            local vs_metro2="—"
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
                # Calculate vs Metro for ref1
                if [ "$mode" = "metro" ]; then
                    vs_metro1="baseline"
                elif [ -n "$metro_jvm_score1" ] && [ "$metro_jvm_score1" != "0" ]; then
                    local pct1=$(printf "%.0f" "$(echo "scale=4; ($score1 / $metro_jvm_score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult1=$(printf "%.1f" "$(echo "scale=4; $score1 / $metro_jvm_score1" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct1" ] && [ -n "$mult1" ]; then
                        vs_metro1="+${pct1}% (${mult1}x)"
                    fi
                fi
            fi

            # Calculate vs Metro for ref2
            if [ -n "$score2" ]; then
                if [ "$mode" = "metro" ]; then
                    vs_metro2="baseline"
                elif [ -n "$metro_jvm_score2" ] && [ "$metro_jvm_score2" != "0" ]; then
                    local pct2=$(printf "%.0f" "$(echo "scale=4; ($score2 / $metro_jvm_score2) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult2=$(printf "%.1f" "$(echo "scale=4; $score2 / $metro_jvm_score2" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct2" ] && [ -n "$mult2" ]; then
                        vs_metro2="+${pct2}% (${mult2}x)"
                    fi
                fi
            fi

            if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                local pct=$(printf "%.2f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    if [[ "$pct" == -* ]]; then
                        diff="${pct}%"
                    elif [[ "$pct" == "0.00" ]]; then
                        diff="+0.00% (no change)"
                    else
                        diff="+${pct}%"
                    fi
                fi
            fi

            echo "| $mode | $display1 | $vs_metro1 | $display2 | $vs_metro2 | $diff |" >> "$summary_file"
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
            # Get metro scores for "vs Metro" column
            local metro_macro_score1=$(extract_android_macro_score_for_ref "$ref1_label" "metro")
            local metro_macro_score2=""
            if mode_was_run_for_ref "$ref2_label" "metro" "android"; then
                metro_macro_score2=$(extract_android_macro_score_for_ref "$ref2_label" "metro")
            fi

            cat >> "$summary_file" << EOF
## Android Benchmarks (Macrobenchmark)

Cold startup time including graph initialization (lower is better):

| Framework | $ref1_label | vs Metro | $ref2_label | vs Metro | Difference |
|-----------|-------------|----------|-------------|----------|------------|
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
                local vs_metro1="—"
                local vs_metro2="—"
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
                    # Calculate vs Metro for ref1
                    if [ "$mode" = "metro" ]; then
                        vs_metro1="baseline"
                    elif [ -n "$metro_macro_score1" ] && [ "$metro_macro_score1" != "0" ]; then
                        local pct1=$(printf "%.0f" "$(echo "scale=4; ($score1 / $metro_macro_score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        local mult1=$(printf "%.1f" "$(echo "scale=4; $score1 / $metro_macro_score1" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        if [ -n "$pct1" ] && [ -n "$mult1" ]; then
                            vs_metro1="+${pct1}% (${mult1}x)"
                        fi
                    fi
                fi

                # Calculate vs Metro for ref2
                if [ -n "$score2" ]; then
                    if [ "$mode" = "metro" ]; then
                        vs_metro2="baseline"
                    elif [ -n "$metro_macro_score2" ] && [ "$metro_macro_score2" != "0" ]; then
                        local pct2=$(printf "%.0f" "$(echo "scale=4; ($score2 / $metro_macro_score2) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        local mult2=$(printf "%.1f" "$(echo "scale=4; $score2 / $metro_macro_score2" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        if [ -n "$pct2" ] && [ -n "$mult2" ]; then
                            vs_metro2="+${pct2}% (${mult2}x)"
                        fi
                    fi
                fi

                if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                    local pct=$(printf "%.2f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct" ]; then
                        if [[ "$pct" == -* ]]; then
                            diff="${pct}%"
                        elif [[ "$pct" == "0.00" ]]; then
                            diff="+0.00% (no change)"
                        else
                            diff="+${pct}%"
                        fi
                    fi
                fi

                echo "| $mode | $display1 | $vs_metro1 | $display2 | $vs_metro2 | $diff |" >> "$summary_file"
            done

            echo "" >> "$summary_file"
        fi

        # Get metro scores for "vs Metro" column in microbenchmark
        local metro_micro_score1=$(extract_android_micro_score_for_ref "$ref1_label" "metro")
        local metro_micro_score2=""
        if mode_was_run_for_ref "$ref2_label" "metro" "android"; then
            metro_micro_score2=$(extract_android_micro_score_for_ref "$ref2_label" "metro")
        fi

        cat >> "$summary_file" << EOF
## Android Benchmarks (Microbenchmark)

Graph creation and initialization time on Android (lower is better):

| Framework | $ref1_label | vs Metro | $ref2_label | vs Metro | Difference |
|-----------|-------------|----------|-------------|----------|------------|
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
            local vs_metro1="—"
            local vs_metro2="—"
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
                # Calculate vs Metro for ref1
                if [ "$mode" = "metro" ]; then
                    vs_metro1="baseline"
                elif [ -n "$metro_micro_score1" ] && [ "$metro_micro_score1" != "0" ]; then
                    local pct1=$(printf "%.0f" "$(echo "scale=4; ($score1 / $metro_micro_score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult1=$(printf "%.1f" "$(echo "scale=4; $score1 / $metro_micro_score1" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct1" ] && [ -n "$mult1" ]; then
                        vs_metro1="+${pct1}% (${mult1}x)"
                    fi
                fi
            fi

            # Calculate vs Metro for ref2
            if [ -n "$score2" ]; then
                if [ "$mode" = "metro" ]; then
                    vs_metro2="baseline"
                elif [ -n "$metro_micro_score2" ] && [ "$metro_micro_score2" != "0" ]; then
                    local pct2=$(printf "%.0f" "$(echo "scale=4; ($score2 / $metro_micro_score2) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult2=$(printf "%.1f" "$(echo "scale=4; $score2 / $metro_micro_score2" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct2" ] && [ -n "$mult2" ]; then
                        vs_metro2="+${pct2}% (${mult2}x)"
                    fi
                fi
            fi

            if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                local pct=$(printf "%.2f" "$(echo "scale=4; (($score2 - $score1) / $score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    if [[ "$pct" == -* ]]; then
                        diff="${pct}%"
                    elif [[ "$pct" == "0.00" ]]; then
                        diff="+0.00% (no change)"
                    else
                        diff="+${pct}%"
                    fi
                fi
            fi

            echo "| $mode | $display1 | $vs_metro1 | $display2 | $vs_metro2 | $diff |" >> "$summary_file"
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

    # Generate HTML report
    generate_html_report "$ref1_label" "$ref2_label" "$MODES" "$benchmark_type"
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
        # Get metro score for "vs Metro" column
        local metro_jvm_score=$(extract_jmh_score_for_ref "$ref_label" "metro")

        cat >> "$summary_file" << EOF
## JVM Benchmarks (JMH)

Graph creation and initialization time (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score=$(extract_jmh_score_for_ref "$ref_label" "$mode")
            local display="${score:-N/A}"
            local vs_metro="—"

            if [ -n "$score" ]; then
                display=$(printf "%.3f" "$score")
                if [ "$mode" = "metro" ]; then
                    vs_metro="baseline"
                elif [ -n "$metro_jvm_score" ] && [ "$metro_jvm_score" != "0" ]; then
                    local pct=$(printf "%.0f" "$(echo "scale=4; ($score / $metro_jvm_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult=$(printf "%.1f" "$(echo "scale=4; $score / $metro_jvm_score" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct" ] && [ -n "$mult" ]; then
                        vs_metro="+${pct}% (${mult}x)"
                    fi
                fi
            fi
            echo "| $mode | $display | $vs_metro |" >> "$summary_file"
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
            # Get metro score for "vs Metro" column
            local metro_macro_score=$(extract_android_macro_score_for_ref "$ref_label" "metro")

            cat >> "$summary_file" << EOF
## Android Benchmarks (Macrobenchmark)

Cold startup time including graph initialization (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

            for mode in "${MODE_ARRAY[@]}"; do
                local score=$(extract_android_macro_score_for_ref "$ref_label" "$mode")
                local display="${score:-N/A}"
                local vs_metro="—"

                if [ -n "$score" ]; then
                    display=$(printf "%.0f" "$score")
                    if [ "$mode" = "metro" ]; then
                        vs_metro="baseline"
                    elif [ -n "$metro_macro_score" ] && [ "$metro_macro_score" != "0" ]; then
                        local pct=$(printf "%.0f" "$(echo "scale=4; ($score / $metro_macro_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        local mult=$(printf "%.1f" "$(echo "scale=4; $score / $metro_macro_score" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        if [ -n "$pct" ] && [ -n "$mult" ]; then
                            vs_metro="+${pct}% (${mult}x)"
                        fi
                    fi
                fi
                echo "| $mode | $display | $vs_metro |" >> "$summary_file"
            done

            echo "" >> "$summary_file"
        fi

        # Get metro score for "vs Metro" column
        local metro_micro_score=$(extract_android_micro_score_for_ref "$ref_label" "metro")

        cat >> "$summary_file" << EOF
## Android Benchmarks (Microbenchmark)

Graph creation and initialization time on Android (lower is better):

| Framework | Time (ms) | vs Metro |
|-----------|-----------|----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local score=$(extract_android_micro_score_for_ref "$ref_label" "$mode")
            local display="${score:-N/A}"
            local vs_metro="—"

            if [ -n "$score" ]; then
                display=$(printf "%.3f" "$score")
                if [ "$mode" = "metro" ]; then
                    vs_metro="baseline"
                elif [ -n "$metro_micro_score" ] && [ "$metro_micro_score" != "0" ]; then
                    local pct=$(printf "%.0f" "$(echo "scale=4; ($score / $metro_micro_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult=$(printf "%.1f" "$(echo "scale=4; $score / $metro_micro_score" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct" ] && [ -n "$mult" ]; then
                        vs_metro="+${pct}% (${mult}x)"
                    fi
                fi
            fi
            echo "| $mode | $display | $vs_metro |" >> "$summary_file"
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

    # Generate HTML report
    generate_html_report "$ref_label" "" "$MODES" "$benchmark_type"
}

# Build JSON data for startup HTML report
build_startup_benchmark_json() {
    local ref1_label="$1"
    local ref2_label="${2:-}"
    local modes="$3"
    local benchmark_type="$4"

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

    # Get plugin version (format: id = "...", version = "X.Y.Z")
    get_plugin_version() {
        local plugin_id="$1"
        grep "$plugin_id" "$versions_file" 2>/dev/null | sed 's/.*version *= *"\([^"]*\)".*/\1/' | head -1
    }

    echo "{"
    echo '  "title": "Startup Benchmark Comparison",'
    echo '  "date": "'$(date -Iseconds)'",'
    echo '  "moduleCount": '"$MODULE_COUNT"','

    # Build metadata
    local kotlin_version=$(get_toml_version "kotlin")
    local dagger_version=$(get_toml_version "dagger")
    local ksp_version=$(get_toml_version "ksp")
    local kotlin_inject_version=$(get_toml_version "kotlinInject")
    local anvil_version=$(get_toml_version "anvil")
    local kotlin_inject_anvil_version=$(get_toml_version "kotlinInject-anvil")
    local jvm_target=$(get_toml_version "jvmTarget")

    # JMH and benchmark versions
    local jmh_version=$(get_plugin_version "me.champeau.jmh")
    local benchmark_version=$(get_plugin_version "androidx.benchmark")

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

    # Android device info (if adb is available)
    local android_device=""
    local android_version=""
    if command -v adb &> /dev/null; then
        android_device=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "")
        android_version=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "")
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
    echo '      "jdk": "'"$java_version"'",'
    echo '      "jvmTarget": "'"$jvm_target"'",'
    echo '      "jmhPlugin": "'"$jmh_version"'",'
    echo '      "androidxBenchmark": "'"$benchmark_version"'"'
    echo '    },'
    echo '    "system": {'
    echo '      "os": "'"$os_info"'",'
    echo '      "cpu": "'"$cpu_info"'",'
    echo '      "ram": "'"$ram_info"'"'
    echo '    },'
    echo '    "android": {'
    echo '      "device": "'"$android_device"'",'
    echo '      "version": "'"$android_version"'"'
    echo '    }'
    echo '  },'

    # Refs info
    echo '  "refs": {'
    local ref1_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    echo '    "ref1": { "label": "'"$ref1_label"'", "commit": "'"$ref1_commit"'" }'
    if [ -n "$ref2_label" ]; then
        local ref2_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")
        echo '    ,"ref2": { "label": "'"$ref2_label"'", "commit": "'"$ref2_commit"'" }'
    fi
    echo '  },'

    # Benchmarks data - different sections based on benchmark type
    echo '  "benchmarks": ['

    local first_test=true

    # JVM section
    if [ "$benchmark_type" = "jvm" ] || [ "$benchmark_type" = "all" ]; then
        if [ "$first_test" = false ]; then echo ","; fi
        first_test=false

        echo '    {'
        echo '      "name": "JVM Startup (JMH)",'
        echo '      "key": "jvm",'
        echo '      "unit": "ms",'
        echo '      "results": ['

        local first_mode=true
        for mode in "${MODE_ARRAY[@]}"; do
            local mode_key
            local mode_name
            case "$mode" in
                "metro") mode_key="metro"; mode_name="Metro" ;;
                "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                *) continue ;;
            esac

            if [ "$first_mode" = false ]; then echo ","; fi
            first_mode=false

            local score1=$(extract_jmh_score_for_ref "$ref1_label" "$mode")
            local score2=""
            if [ -n "$ref2_label" ]; then
                score2=$(extract_jmh_score_for_ref "$ref2_label" "$mode")
            fi

            echo '        {'
            echo '          "framework": "'"$mode_name"'",'
            echo '          "key": "'"$mode_key"'",'
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
    fi

    # Android macrobenchmark section (only if enabled or results exist)
    if [ "$benchmark_type" = "android" ] || [ "$benchmark_type" = "all" ]; then
        local has_macro_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            local macro_score=$(extract_android_macro_score_for_ref "$ref1_label" "$mode")
            if [ -n "$macro_score" ]; then
                has_macro_results=true
                break
            fi
        done

        if [ "$INCLUDE_MACROBENCHMARK" = true ] || [ "$has_macro_results" = true ]; then
            if [ "$first_test" = false ]; then echo ","; fi
            first_test=false

            echo '    {'
            echo '      "name": "Android Startup (Macrobenchmark)",'
            echo '      "key": "android_macro",'
            echo '      "unit": "ms",'
            echo '      "results": ['

            local first_mode=true
            for mode in "${MODE_ARRAY[@]}"; do
                local mode_key
                local mode_name
                case "$mode" in
                    "metro") mode_key="metro"; mode_name="Metro" ;;
                    "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                    "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                    "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                    *) continue ;;
                esac

                if [ "$first_mode" = false ]; then echo ","; fi
                first_mode=false

                local score1=$(extract_android_macro_score_for_ref "$ref1_label" "$mode")
                local score2=""
                if [ -n "$ref2_label" ]; then
                    score2=$(extract_android_macro_score_for_ref "$ref2_label" "$mode")
                fi

                echo '        {'
                echo '          "framework": "'"$mode_name"'",'
                echo '          "key": "'"$mode_key"'",'
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
        fi

        # Android microbenchmark section
        if [ "$first_test" = false ]; then echo ","; fi
        first_test=false

        echo '    {'
        echo '      "name": "Android Component Init (Microbenchmark)",'
        echo '      "key": "android_micro",'
        echo '      "unit": "ms",'
        echo '      "results": ['

        local first_mode=true
        for mode in "${MODE_ARRAY[@]}"; do
            local mode_key
            local mode_name
            case "$mode" in
                "metro") mode_key="metro"; mode_name="Metro" ;;
                "anvil-ksp") mode_key="anvil_ksp"; mode_name="Anvil (KSP)" ;;
                "anvil-kapt") mode_key="anvil_kapt"; mode_name="Anvil (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                *) continue ;;
            esac

            if [ "$first_mode" = false ]; then echo ","; fi
            first_mode=false

            local score1=$(extract_android_micro_score_for_ref "$ref1_label" "$mode")
            local score2=""
            if [ -n "$ref2_label" ]; then
                score2=$(extract_android_micro_score_for_ref "$ref2_label" "$mode")
            fi

            echo '        {'
            echo '          "framework": "'"$mode_name"'",'
            echo '          "key": "'"$mode_key"'",'
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
    fi

    echo ''
    echo '  ]'
    echo "}"
}

# Generate HTML report for startup benchmarks
generate_html_report() {
    local ref1_label="$1"
    local ref2_label="${2:-}"
    local modes="$3"
    local benchmark_type="$4"

    local html_file="$RESULTS_DIR/${TIMESTAMP}/startup-benchmark-report.html"

    print_header "Generating HTML Report"

    # Build JSON data
    local json_data
    json_data=$(build_startup_benchmark_json "$ref1_label" "$ref2_label" "$modes" "$benchmark_type")

    # Generate HTML
    cat > "$html_file" << 'HTMLHEAD'
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Metro Startup Benchmark Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root { --metro-color: #4CAF50; --anvil-ksp-color: #2196F3; --anvil-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; }
        * { box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 0; background: #f5f5f5; color: #333; }
        .header { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: white; padding: 2rem; text-align: center; }
        .header h1 { margin: 0 0 0.5rem 0; font-weight: 300; font-size: 2rem; }
        .header .subtitle { opacity: 0.8; font-size: 0.9rem; }
        .container { max-width: 1400px; margin: 0 auto; padding: 2rem; }
        .refs-info { display: flex; gap: 2rem; margin-bottom: 2rem; flex-wrap: wrap; }
        .ref-card { background: white; border-radius: 8px; padding: 1rem 1.5rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); flex: 1; min-width: 250px; }
        .ref-card.baseline { border-left: 4px solid var(--metro-color); }
        .ref-card.comparison { border-left: 4px solid var(--anvil-ksp-color); }
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
        .metadata-section { background: white; border-radius: 8px; padding: 1.5rem; margin-top: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .metadata-section h2 { margin: 0 0 1rem 0; font-size: 1.1rem; font-weight: 500; border-bottom: 2px solid #eee; padding-bottom: 0.5rem; color: #666; }
        .metadata-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; }
        .metadata-group h3 { margin: 0 0 0.75rem 0; font-size: 0.85rem; text-transform: uppercase; color: #888; font-weight: 600; }
        .metadata-group dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 0.25rem 1rem; }
        .metadata-group dt { color: #666; font-size: 0.85rem; }
        .metadata-group dd { margin: 0; font-family: 'SF Mono', Monaco, monospace; font-size: 0.85rem; color: #333; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Metro Startup Benchmark Results</h1>
        <div class="subtitle" id="date"></div>
    </div>
    <div class="container">
        <div class="refs-info" id="refs-info"></div>
        <div id="benchmarks"></div>
        <div id="metadata"></div>
    </div>
<script>
const benchmarkData =
HTMLHEAD

    echo "$json_data" >> "$html_file"

    cat >> "$html_file" << 'HTMLTAIL'
;
const colors = { 'metro': '#4CAF50', 'anvil_ksp': '#2196F3', 'anvil_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0' };

// State for selectable baseline
let selectedBaseline = 'metro';

function formatTime(ms, unit) {
    if (ms === null || ms === undefined) return '—';
    if (unit === 'ms') {
        if (ms < 1) return ms.toFixed(3) + ' ms';
        if (ms < 100) return ms.toFixed(2) + ' ms';
        return ms.toFixed(0) + ' ms';
    }
    return ms.toFixed(2);
}

// Calculate percentage difference vs baseline: (value - baseline) / baseline * 100
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
    // Calculate average speedup vs other frameworks across all benchmarks
    let totalSpeedup = { anvil_ksp: 0, anvil_kapt: 0, kotlin_inject_anvil: 0 };
    let counts = { anvil_ksp: 0, anvil_kapt: 0, kotlin_inject_anvil: 0 };
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
    const names = { 'anvil_ksp': 'Anvil KSP', 'anvil_kapt': 'Anvil KAPT', 'kotlin_inject_anvil': 'kotlin-inject' };
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
        ref1Data.push(result.ref1 || 0);
        ref2Data.push(result.ref2 || 0);
        backgroundColors.push(colors[result.key]);
    });
    const datasets = [];
    if (benchmarkData.refs.ref1) datasets.push({ label: benchmarkData.refs.ref1.label, data: ref1Data, backgroundColor: backgroundColors.map(c => c + 'CC'), borderColor: backgroundColors, borderWidth: 2 });
    if (benchmarkData.refs.ref2) datasets.push({ label: benchmarkData.refs.ref2.label, data: ref2Data, backgroundColor: backgroundColors.map(c => c + '66'), borderColor: backgroundColors, borderWidth: 2, borderDash: [5, 5] });
    charts[idx] = new Chart(ctx, { type: 'bar', data: { labels, datasets }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: datasets.length > 1 }, tooltip: { callbacks: { label: ctx => ctx.dataset.label + ': ' + ctx.raw.toFixed(2) + ' ' + (benchmark.unit || 'ms') } } }, scales: { y: { beginAtZero: true, title: { display: true, text: 'Time (' + (benchmark.unit || 'ms') + ')' } } } } });
}

function renderTable(benchmark, idx) {
    const tbody = document.getElementById(`table-${idx}`);
    const baselineRef1 = benchmark.results.find(r => r.key === selectedBaseline)?.ref1;
    const baselineRef2 = benchmark.results.find(r => r.key === selectedBaseline)?.ref2;
    // Get metro's ref2 value for comparing non-metro frameworks that weren't re-run
    const metroRef2 = benchmark.results.find(r => r.key === 'metro')?.ref2;
    let html = '';
    benchmark.results.forEach(result => {
        const isBaseline = result.key === selectedBaseline;
        const vsBaseline1 = calculateVsBaseline(result.ref1, baselineRef1);
        const vsBaseline2 = calculateVsBaseline(result.ref2, baselineRef2);
        // For diff: if this framework has ref2, compare ref2 vs ref1
        // If not, compare ref1 against metro's ref2 (how much faster/slower than new metro)
        const diff = result.ref2 ? calculateDiff(result.ref2, result.ref1) : calculateDiff(result.ref1, metroRef2);
        html += `<tr class="${isBaseline ? 'baseline-row' : ''}" data-key="${result.key}">
            <td class="baseline-select" onclick="setBaseline('${result.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>
            <td class="framework" style="color: ${colors[result.key]}">${result.framework}</td>
            ${benchmarkData.refs.ref1 ? `<td class="numeric">${result.ref1 ? formatTime(result.ref1, benchmark.unit) : '<span class="no-data">N/A</span>'}</td><td class="numeric vs-baseline ${vsBaseline1.class}">${vsBaseline1.text}</td>` : ''}
            ${benchmarkData.refs.ref2 ? `<td class="numeric">${result.ref2 ? formatTime(result.ref2, benchmark.unit) : '<span class="no-data">(not run)</span>'}</td><td class="numeric vs-baseline ${vsBaseline2.class}">${vsBaseline2.text}</td>` : ''}
            ${benchmarkData.refs.ref1 && benchmarkData.refs.ref2 ? `<td class="numeric diff ${diff.class}">${diff.text}</td>` : ''}</tr>`;
    });
    tbody.innerHTML = html;
}

function setBaseline(key) {
    selectedBaseline = key;
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderTable(benchmark, idx); });
    document.querySelectorAll('.baseline-header').forEach(el => { el.textContent = getBaselineLabel(); });
}

function renderMetadata() {
    const m = benchmarkData.metadata;
    if (!m) return;
    const container = document.getElementById('metadata');
    const hasAndroid = m.android?.device || m.android?.version;
    container.innerHTML = `
        <div class="metadata-section">
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
                    <h3>Build Configuration</h3>
                    <dl>
                        <dt>JDK</dt><dd>${m.build?.jdk || '—'}</dd>
                        <dt>JVM Target</dt><dd>${m.build?.jvmTarget || '—'}</dd>
                        <dt>JMH Plugin</dt><dd>${m.build?.jmhPlugin || '—'}</dd>
                        <dt>AndroidX Benchmark</dt><dd>${m.build?.androidxBenchmark || '—'}</dd>
                    </dl>
                </div>
                <div class="metadata-group">
                    <h3>System Info</h3>
                    <dl>
                        <dt>OS</dt><dd>${m.system?.os || '—'}</dd>
                        <dt>CPU</dt><dd>${m.system?.cpu || '—'}</dd>
                        <dt>RAM</dt><dd>${m.system?.ram || '—'}</dd>
                    </dl>
                </div>
                ${hasAndroid ? `
                <div class="metadata-group">
                    <h3>Android Device</h3>
                    <dl>
                        <dt>Device</dt><dd>${m.android?.device || '—'}</dd>
                        <dt>Android Version</dt><dd>${m.android?.version || '—'}</dd>
                    </dl>
                </div>
                ` : ''}
            </div>
        </div>
    `;
}

document.getElementById('date').textContent = new Date(benchmarkData.date).toLocaleString();
renderRefsInfo(); renderBenchmarks(); renderMetadata();
</script>
</body>
</html>
HTMLTAIL

    print_success "HTML report saved to $html_file"
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
        jvm-r8)
            run_jvm_r8_benchmarks
            generate_summary
            ;;
        android)
            run_android_benchmarks
            generate_summary
            ;;
        all)
            run_all_benchmarks
            # Also run R8 benchmarks for metro
            run_jvm_r8_benchmarks
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
