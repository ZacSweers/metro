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
START_TIME=$(date +%s)

# Default modes to benchmark
MODES="metro,dagger-ksp,dagger-kapt,kotlin-inject-anvil"

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
# Whether to only collect binary metrics without running benchmarks (for testing)
BINARY_METRICS_ONLY=false

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

# Format duration in human-readable format
format_duration() {
    local seconds=$1
    local minutes=$((seconds / 60))
    local remaining_seconds=$((seconds % 60))
    if [ $minutes -gt 0 ]; then
        echo "${minutes}m ${remaining_seconds}s"
    else
        echo "${seconds}s"
    fi
}

# Print final results with duration
print_final_results() {
    local results_dir="$1"
    local end_time=$(date +%s)
    local duration=$((end_time - START_TIME))
    local formatted_duration=$(format_duration $duration)
    local full_path="$(cd "$(dirname "$results_dir")" && pwd)/$(basename "$results_dir")"

    print_header "Benchmarks Complete"
    echo "Results saved to: $full_path"
    echo "Total duration: $formatted_duration"
    echo ""
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
    echo "  jvm-r8    Run JVM startup benchmarks with R8-minified classes"
    echo "  android   Run Android benchmarks (requires device)"
    echo "  all       Run all benchmarks (default)"
    echo "  single    Run benchmarks on a single git ref"
    echo "  compare   Compare benchmarks across two git refs (branches or commits)"
    echo "  summary   Regenerate summary from existing results (use with --timestamp)"
    echo "  help      Show this help message"
    echo ""
    echo "Options:"
    echo "  --modes <list>          Comma-separated list of modes to benchmark"
    echo "                          Available: metro, dagger-ksp, dagger-kapt, kotlin-inject-anvil"
    echo "                          Default: metro,dagger-ksp,dagger-kapt,kotlin-inject-anvil"
    echo "  --count <n>             Number of modules to generate (default: 500)"
    echo "  --timestamp <ts>        Use specific timestamp for results directory"
    echo "  --include-macrobenchmark  Include Android macrobenchmarks (startup time)"
    echo "                          Disabled by default as startup time is low-signal for DI perf"
    echo "  --binary-metrics-only   Only collect binary metrics (skip JMH/benchmark runs)"
    echo "                          Useful for testing binary metrics collection quickly"
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
    echo "  $0 jvm-r8                           # Run JVM benchmarks with R8-minified classes for all modes"
    echo "  $0 jvm --modes metro,dagger-ksp     # Run JVM benchmarks for specific modes"
    echo "  $0 all --count 250                  # Run all benchmarks with 250 modules"
    echo "  $0 android --include-macrobenchmark # Run Android benchmarks including macrobenchmarks"
    echo "  $0 summary --timestamp 20251205_125203 --modes metro,dagger-ksp"
    echo ""
    echo "  # Run benchmarks on a single git ref:"
    echo "  $0 single --ref main"
    echo "  $0 single --ref feature-branch --modes metro,dagger-ksp --benchmark jvm"
    echo "  $0 single --ref main --benchmark jvm-r8  # Run R8-minified benchmark for all modes"
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
        dagger-ksp)
            echo "--mode dagger --processor ksp"
            ;;
        dagger-kapt)
            echo "--mode dagger --processor kapt"
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
        dagger-ksp|dagger-kapt|kotlin-inject-anvil)
            # Disable incremental processing and build cache to avoid flaky KSP/KAPT builds
            echo "--no-build-cache -Pksp.incremental=false -Pkotlin.incremental=false"
            ;;
        *)
            echo ""
            ;;
    esac
}

# Extract class metrics from compiled AppComponent classes using javap
# Outputs JSON: { fields: N, methods: N, shards: N, total_size_bytes: N, classes: [...] }
# Handles both Metro (kotlin/main) and Dagger (java/main with DaggerMergedAppComponent) classes
extract_class_metrics() {
    local base_class_dir="$1"
    local output_file="$2"
    local package_path="dev/zacsweers/metro/benchmark/app/component"

    # Check both kotlin/main and java/main directories - aggregate from both
    local kotlin_path="$base_class_dir/$package_path"
    local java_path="${base_class_dir/kotlin\/main/java/main}/$package_path"

    local dirs_to_scan=()
    if [ -d "$kotlin_path" ]; then
        dirs_to_scan+=("$kotlin_path")
    fi
    if [ -d "$java_path" ]; then
        dirs_to_scan+=("$java_path")
    fi

    if [ ${#dirs_to_scan[@]} -eq 0 ]; then
        print_error "Class directory not found: $kotlin_path or $java_path"
        echo '{"fields":0,"methods":0,"shards":0,"total_size_bytes":0,"classes":[]}' > "$output_file"
        return 1
    fi

    # Aggregate ALL .class files from all found directories
    local class_files=()
    local shard_count=0
    local total_size=0
    local total_fields=0
    local total_methods=0
    local class_names=()

    # Process all .class files from all directories
    for dir in "${dirs_to_scan[@]}"; do
        while IFS= read -r -d '' class_file; do
            class_files+=("$class_file")
            local filename=$(basename "$class_file")
            class_names+=("$filename")

            # Get file size
            local file_size=$(stat -f%z "$class_file" 2>/dev/null || stat -c%s "$class_file" 2>/dev/null || echo "0")
            total_size=$((total_size + file_size))

            # Count shard classes (both Metro and Dagger generate these)
            if [[ "$filename" == *"Shard"* ]]; then
                shard_count=$((shard_count + 1))
            fi

            # Use javap to extract fields and methods count
            # Look for line like: "interfaces: 5, fields: 11, methods: 171, attributes: 5"
            local fields=$(javap -verbose "$class_file" 2>/dev/null | grep -o 'fields: [0-9]*' | grep -o '[0-9]*' | head -1 || echo "0")
            local methods=$(javap -verbose "$class_file" 2>/dev/null | grep -o 'methods: [0-9]*' | grep -o '[0-9]*' | head -1 || echo "0")

            if [ -n "$fields" ]; then
                total_fields=$((total_fields + fields))
            fi
            if [ -n "$methods" ]; then
                total_methods=$((total_methods + methods))
            fi
        done < <(find "$dir" -maxdepth 1 -name "*.class" -print0 2>/dev/null)
    done

    # Build JSON array of class names
    local classes_json="["
    local first=true
    for name in "${class_names[@]}"; do
        if [ "$first" = true ]; then
            first=false
        else
            classes_json+=","
        fi
        classes_json+="\"$name\""
    done
    classes_json+="]"

    # Output JSON
    cat > "$output_file" << EOF
{
  "fields": $total_fields,
  "methods": $total_methods,
  "shards": $shard_count,
  "total_size_bytes": $total_size,
  "classes": $classes_json
}
EOF

    local dirs_str=$(IFS=", "; echo "${dirs_to_scan[*]}")
    print_success "Class metrics extracted: fields=$total_fields, methods=$total_methods, shards=$shard_count, size=${total_size}B, classes=${#class_names[@]} (from $dirs_str)"
}

# Extract JAR metrics using diffuse
# Diffs JAR against itself to get accurate class/method/field counts
# Optional third parameter: diffuse output file to save full output
extract_jar_metrics() {
    local jar_file="$1"
    local output_file="$2"
    local diffuse_output_file="${3:-}"

    if [ ! -f "$jar_file" ]; then
        print_error "JAR file not found: $jar_file"
        echo '{"size_bytes":0,"classes":0,"methods":0,"fields":0}' > "$output_file"
        return 1
    fi

    # Get file size
    local file_size=$(stat -f%z "$jar_file" 2>/dev/null || stat -c%s "$jar_file" 2>/dev/null || echo "0")

    # Use diffuse to get accurate metrics by diffing JAR against itself
    local diffuse_output
    diffuse_output=$(diffuse diff --jar "$jar_file" "$jar_file" 2>&1 || echo "")

    # Save full diffuse output if requested
    if [ -n "$diffuse_output_file" ]; then
        echo "$diffuse_output" > "$diffuse_output_file"
        print_step "Saved diffuse output to: $diffuse_output_file"
    fi

    # Parse diffuse output for CLASSES section
    # Format: " classes │ 3977 │ 3977 │ 0 (+0 -0)"
    local classes=$(echo "$diffuse_output" | grep -E "^\s*classes\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")
    local methods=$(echo "$diffuse_output" | grep -E "^\s*methods\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")
    local fields=$(echo "$diffuse_output" | grep -E "^\s*fields\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")

    # Default to 0 if empty
    classes=${classes:-0}
    methods=${methods:-0}
    fields=${fields:-0}

    # Output JSON
    cat > "$output_file" << EOF
{
  "size_bytes": $file_size,
  "classes": $classes,
  "methods": $methods,
  "fields": $fields
}
EOF

    local size_kb=$(echo "scale=1; $file_size / 1024" | bc)
    print_success "JAR metrics (diffuse): size=${size_kb}KB, classes=$classes, methods=$methods, fields=$fields"
}

# Extract APK metrics using diffuse
# Diffs APK against itself to get accurate DEX stats
# Optional third parameter: diffuse output file to save full output
extract_apk_metrics() {
    local apk_file="$1"
    local output_file="$2"
    local diffuse_output_file="${3:-}"

    if [ ! -f "$apk_file" ]; then
        print_error "APK file not found: $apk_file"
        echo '{"size_bytes":0,"dex_size_bytes":0,"dex_classes":0,"dex_methods":0,"dex_fields":0}' > "$output_file"
        return 1
    fi

    # Get file size
    local file_size=$(stat -f%z "$apk_file" 2>/dev/null || stat -c%s "$apk_file" 2>/dev/null || echo "0")

    # Use diffuse to get accurate metrics by diffing APK against itself
    local diffuse_output
    diffuse_output=$(diffuse diff --apk "$apk_file" "$apk_file" 2>&1 || echo "")

    # Save full diffuse output if requested
    if [ -n "$diffuse_output_file" ]; then
        echo "$diffuse_output" > "$diffuse_output_file"
        print_step "Saved diffuse output to: $diffuse_output_file"
    fi

    # Parse APK section for DEX size (compressed)
    # Format: "      dex │ 391.4 KiB │ 391.4 KiB │  0 B │ ..."
    local dex_size_str=$(echo "$diffuse_output" | grep -E "^\s*dex\s*│" | head -1 | awk -F'│' '{print $2}' | tr -d ' ')
    local dex_size_bytes=0
    if [[ "$dex_size_str" =~ ([0-9.]+)[[:space:]]*(KiB|MiB|B) ]]; then
        local num="${BASH_REMATCH[1]}"
        local unit="${BASH_REMATCH[2]}"
        case "$unit" in
            "B") dex_size_bytes=$(printf "%.0f" "$num") ;;
            "KiB") dex_size_bytes=$(printf "%.0f" "$(echo "$num * 1024" | bc)") ;;
            "MiB") dex_size_bytes=$(printf "%.0f" "$(echo "$num * 1024 * 1024" | bc)") ;;
        esac
    fi

    # Parse DEX section for class/method/field counts
    # Format: " classes │  457 │  457 │ 0 (+0 -0)"
    local dex_classes=$(echo "$diffuse_output" | grep -E "^\s*classes\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")
    local dex_methods=$(echo "$diffuse_output" | grep -E "^\s*methods\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")
    local dex_fields=$(echo "$diffuse_output" | grep -E "^\s*fields\s*│" | awk -F'│' '{print $2}' | tr -d ' ' || echo "0")

    # Default to 0 if empty
    dex_classes=${dex_classes:-0}
    dex_methods=${dex_methods:-0}
    dex_fields=${dex_fields:-0}

    # Output JSON
    cat > "$output_file" << EOF
{
  "size_bytes": $file_size,
  "dex_size_bytes": $dex_size_bytes,
  "dex_classes": $dex_classes,
  "dex_methods": $dex_methods,
  "dex_fields": $dex_fields
}
EOF

    local size_kb=$(echo "scale=1; $file_size / 1024" | bc)
    local dex_kb=$(echo "scale=1; $dex_size_bytes / 1024" | bc)
    print_success "APK metrics (diffuse): size=${size_kb}KB, dex=${dex_kb}KB, classes=$dex_classes, methods=$dex_methods, fields=$dex_fields"
}

# Run diffuse comparison between two APKs or JARs
# Saves full output and extracts summary tables
# Args: file1, file2, output_dir, file_type (apk/jar), comparison_name
run_diffuse_diff() {
    local file1="$1"
    local file2="$2"
    local output_dir="$3"
    local file_type="${4:-apk}"  # apk or jar
    local comparison_name="${5:-comparison}"  # e.g., "metro_ref1_vs_ref2" or "metro_vs_dagger"

    mkdir -p "$output_dir"

    # Source the diffuse installation script to get the binary path
    source "$SCRIPT_DIR/install-diffuse.sh"
    local diffuse_bin=$(get_diffuse_bin)

    if [ ! -x "$diffuse_bin" ]; then
        print_error "diffuse not installed. Run ./install-diffuse.sh first"
        return 1
    fi

    if [ ! -f "$file1" ] || [ ! -f "$file2" ]; then
        print_error "One or both files not found: $file1, $file2"
        return 1
    fi

    local type_flag=""
    if [ "$file_type" = "jar" ]; then
        type_flag="--jar"
    fi

    print_step "Running diffuse diff: $comparison_name..."

    # Run diffuse and capture full output with unique filename
    local full_output="$output_dir/diffuse-${comparison_name}.txt"
    if "$diffuse_bin" diff $type_flag "$file1" "$file2" > "$full_output" 2>&1; then
        print_success "diffuse diff complete: $full_output"
        return 0
    else
        print_error "diffuse diff failed for $comparison_name"
        cat "$full_output"
        return 1
    fi
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

    local gradle_args=$(get_gradle_args "$mode")

    if [ "$BINARY_METRICS_ONLY" = true ]; then
        # Binary metrics only mode - just build classes, skip JMH
        print_step "Building classes for $mode (binary metrics only)..."
        if ./gradlew --quiet $gradle_args :app:component:classes 2>&1 | tee "$output_dir/build-output.txt"; then
            # Extract class metrics from compiled AppComponent classes
            print_step "Extracting class metrics for $mode..."
            extract_class_metrics "app/component/build/classes/kotlin/main" "$output_dir/class-metrics.json"
            print_success "Binary metrics extraction complete for $mode"
        else
            print_error "Build failed for $mode"
            return 1
        fi
    else
        # Full benchmark mode - run JMH
        print_step "Running JMH benchmark for $mode..."

        # Run JMH and capture output
        if ./gradlew --quiet $gradle_args :startup-jvm:jmh 2>&1 | tee "$output_dir/jmh-output.txt"; then
            # Copy JMH results
            if [ -d "startup-jvm/build/results/jmh" ]; then
                cp -r startup-jvm/build/results/jmh/* "$output_dir/" 2>/dev/null || true
            fi

            # Extract class metrics from compiled AppComponent classes
            print_step "Extracting class metrics for $mode..."
            extract_class_metrics "app/component/build/classes/kotlin/main" "$output_dir/class-metrics.json"

            print_success "JMH benchmark complete for $mode"
        else
            print_error "JMH benchmark failed for $mode"
            return 1
        fi
    fi
}

# Run JMH benchmark for a specific mode (with clean/generate)
run_jvm_benchmark() {
    local mode="$1"
    setup_for_mode "$mode"
    run_jvm_benchmark_only "$mode"
}

# Run JMH R8 benchmark only (no clean/generate)
run_jvm_r8_benchmark_only() {
    local mode="${1:-metro}"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"
    mkdir -p "$output_dir"

    local jar_file="startup-jvm/minified-jar/build/libs/minified-jar.jar"

    if [ "$BINARY_METRICS_ONLY" = true ]; then
        # Binary metrics only mode - just build minified jar, skip JMH
        print_step "Building minified JAR for $mode (binary metrics only)..."
        if ./gradlew --quiet :startup-jvm:minified-jar:r8 2>&1 | tee "$output_dir/build-output.txt"; then
            # Extract JAR metrics from minified jar
            print_step "Extracting R8 JAR metrics for $mode..."
            mkdir -p "$output_dir/diffuse"
            extract_jar_metrics "$jar_file" "$output_dir/jar-metrics.json" "$output_dir/diffuse/diffuse-jar-${mode}.txt"
            # Copy JAR file to results directory for later diffuse comparison
            cp "$jar_file" "$output_dir/minified-jar.jar" 2>/dev/null || true
            print_success "Binary metrics extraction complete for $mode"
        else
            print_error "Build failed for $mode"
            return 1
        fi
    else
        # Full benchmark mode - run JMH
        print_step "Running JMH R8 benchmark for $mode (minified)..."

        # Run JMH with R8-minified classes and capture output
        if ./gradlew --quiet :startup-jvm-minified:jmh 2>&1 | tee "$output_dir/jmh-output.txt"; then
            # Copy JMH results
            if [ -d "startup-jvm-minified/build/results/jmh" ]; then
                cp -r startup-jvm-minified/build/results/jmh/* "$output_dir/" 2>/dev/null || true
            fi

            # Extract JAR metrics from minified jar
            print_step "Extracting R8 JAR metrics for $mode..."
            mkdir -p "$output_dir/diffuse"
            extract_jar_metrics "$jar_file" "$output_dir/jar-metrics.json" "$output_dir/diffuse/diffuse-jar-${mode}.txt"
            # Copy JAR file to results directory for later diffuse comparison
            cp "$jar_file" "$output_dir/minified-jar.jar" 2>/dev/null || true

            print_success "JMH R8 benchmark complete for $mode"
        else
            print_error "JMH R8 benchmark failed for $mode"
            return 1
        fi
    fi
}

# Run JMH R8 benchmark for a specific mode (with clean/generate)
run_jvm_r8_benchmark() {
    local mode="${1:-metro}"
    setup_for_mode "$mode"
    run_jvm_r8_benchmark_only "$mode"
}

# Run Android benchmark only (no clean/generate)
run_android_benchmark_only() {
    local mode="$1"
    local output_dir="$RESULTS_DIR/${TIMESTAMP}/android_${mode}"
    mkdir -p "$output_dir"

    local gradle_args=$(get_gradle_args "$mode")

    if [ "$BINARY_METRICS_ONLY" = true ]; then
        # Binary metrics only mode - just build APK, skip benchmarks
        print_step "Building Android APK for $mode (binary metrics only)..."
        if ! ./gradlew --quiet $gradle_args :startup-android:app:assembleRelease 2>&1 | tee "$output_dir/build-output.txt"; then
            print_error "Android build failed for $mode"
            return 1
        fi

        # Extract APK metrics after build
        local apk_file="startup-android/app/build/outputs/apk/release/app-release.apk"
        if [ -f "$apk_file" ]; then
            print_step "Extracting APK metrics for $mode..."
            mkdir -p "$output_dir/diffuse"
            extract_apk_metrics "$apk_file" "$output_dir/apk-metrics.json" "$output_dir/diffuse/diffuse-apk-${mode}.txt"
            # Copy APK file to results directory for later diffuse comparison
            cp "$apk_file" "$output_dir/app-release.apk" 2>/dev/null || true
            print_success "Binary metrics extraction complete for $mode"
        else
            print_error "APK not found at expected path: $apk_file"
            return 1
        fi
    else
        # Full benchmark mode
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

        # Extract APK metrics after build
        local apk_file="startup-android/app/build/outputs/apk/release/app-release.apk"
        if [ -f "$apk_file" ]; then
            print_step "Extracting APK metrics for $mode..."
            mkdir -p "$output_dir/diffuse"
            extract_apk_metrics "$apk_file" "$output_dir/apk-metrics.json" "$output_dir/diffuse/diffuse-apk-${mode}.txt"
            # Copy APK file to results directory for later diffuse comparison
            cp "$apk_file" "$output_dir/app-release.apk" 2>/dev/null || true
        else
            print_error "APK not found at expected path: $apk_file"
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

    # Add JVM R8 results if any exist
    local has_r8_results=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}" ]; then
            has_r8_results=true
            break
        fi
    done

    if [ "$has_r8_results" = true ]; then
        # Get metro R8 score for "vs Metro R8" column
        local metro_jvm_r8_score=""
        local r8_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_metro"
        if [ -f "$r8_dir/results.json" ]; then
            metro_jvm_r8_score=$(extract_jmh_score "$r8_dir/results.json")
        fi
        if [ -z "$metro_jvm_r8_score" ] && [ -f "$r8_dir/jmh-output.txt" ]; then
            metro_jvm_r8_score=$(grep 'graphCreationAndInitialization' "$r8_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
        fi

        cat >> "$summary_file" << EOF

## JVM Benchmarks - R8 Minified (JMH)

Graph creation and initialization time with R8 optimization (lower is better):

| Framework | Time (ms) | vs Metro R8 |
|-----------|-----------|-------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local jvm_r8_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"
            if [ ! -d "$jvm_r8_dir" ]; then
                continue
            fi

            local r8_score=""
            if [ -f "$jvm_r8_dir/results.json" ]; then
                r8_score=$(extract_jmh_score "$jvm_r8_dir/results.json")
            fi
            if [ -z "$r8_score" ] && [ -f "$jvm_r8_dir/jmh-output.txt" ]; then
                r8_score=$(grep 'graphCreationAndInitialization' "$jvm_r8_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
            fi

            if [ -z "$r8_score" ]; then
                continue
            fi

            local r8_comparison="-"
            if [ "$mode" = "metro" ]; then
                r8_comparison="baseline"
            elif [ -n "$metro_jvm_r8_score" ] && [ "$metro_jvm_r8_score" != "0" ]; then
                local pct=$(printf "%.1f" "$(echo "scale=4; (($r8_score - $metro_jvm_r8_score) / $metro_jvm_r8_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                if [ -n "$pct" ]; then
                    if [[ "$pct" != -* ]]; then
                        r8_comparison="+${pct}%"
                    else
                        r8_comparison="${pct}%"
                    fi
                fi
            fi

            local r8_display_score=$(printf "%.2f" "$r8_score")
            echo "| $mode | $r8_display_score | $r8_comparison |" >> "$summary_file"
        done
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

    # Add binary metrics section
    generate_binary_metrics_summary "$summary_file"

    cat >> "$summary_file" << EOF

## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`jvm_<mode>/\` - JMH benchmark results
- \`jvm-r8_<mode>/\` - JMH R8-minified benchmark results
- \`android_<mode>/\` - Android benchmark results
EOF

    print_success "Summary saved to $(pwd)/$summary_file"
    echo ""
    cat "$summary_file"

    # Generate HTML report for non-ref benchmarks
    generate_non_ref_html_report "all"
}

# Generate binary metrics summary tables
# Args: summary_file [ref_label]
# If ref_label is provided, uses ref-based path structure: ${ref_label}/jvm_${mode}/
# Otherwise uses non-ref structure: jvm_${mode}/
generate_binary_metrics_summary() {
    local summary_file="$1"
    local ref_label="${2:-}"

    # Build path prefix based on whether ref_label is provided
    local path_prefix=""
    if [ -n "$ref_label" ]; then
        path_prefix="${ref_label}/"
    fi

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"

    # Check if any class metrics exist
    local has_class_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ -f "$RESULTS_DIR/${TIMESTAMP}/${path_prefix}jvm_${mode}/class-metrics.json" ]; then
            has_class_metrics=true
            break
        fi
    done

    if [ "$has_class_metrics" = true ]; then
        cat >> "$summary_file" << EOF

## Binary Metrics

### Pre-Minification Component Classes

| Framework | Fields | Methods | Shards | Size (KB) |
|-----------|--------|---------|--------|-----------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${path_prefix}jvm_${mode}/class-metrics.json"
            if [ -f "$metrics_file" ]; then
                local fields=$(jq -r '.fields' "$metrics_file" 2>/dev/null || echo "0")
                local methods=$(jq -r '.methods' "$metrics_file" 2>/dev/null || echo "0")
                local shards=$(jq -r '.shards' "$metrics_file" 2>/dev/null || echo "0")
                local size_bytes=$(jq -r '.total_size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")

                echo "| $mode | $fields | $methods | $shards | $size_kb |" >> "$summary_file"
            fi
        done
    fi

    # Check if any R8 JAR metrics exist
    local has_jar_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ -f "$RESULTS_DIR/${TIMESTAMP}/${path_prefix}jvm-r8_${mode}/jar-metrics.json" ]; then
            has_jar_metrics=true
            break
        fi
    done

    if [ "$has_jar_metrics" = true ]; then
        cat >> "$summary_file" << EOF

### R8-Minified JAR

| Framework | JAR Size (KB) | Classes | Methods | Fields |
|-----------|---------------|---------|---------|--------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${path_prefix}jvm-r8_${mode}/jar-metrics.json"
            if [ -f "$metrics_file" ]; then
                local size_bytes=$(jq -r '.size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                local class_count=$(jq -r '.classes // 0' "$metrics_file" 2>/dev/null || echo "0")
                local method_count=$(jq -r '.methods // 0' "$metrics_file" 2>/dev/null || echo "0")
                local field_count=$(jq -r '.fields // 0' "$metrics_file" 2>/dev/null || echo "0")
                local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")

                echo "| $mode | $size_kb | $class_count | $method_count | $field_count |" >> "$summary_file"
            fi
        done
    fi

    # Check if any APK metrics exist
    local has_apk_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ -f "$RESULTS_DIR/${TIMESTAMP}/${path_prefix}android_${mode}/apk-metrics.json" ]; then
            has_apk_metrics=true
            break
        fi
    done

    if [ "$has_apk_metrics" = true ]; then
        cat >> "$summary_file" << EOF

### Android APK

| Framework | APK Size (KB) | DEX Size (KB) | DEX Classes | DEX Methods | DEX Fields |
|-----------|---------------|---------------|-------------|-------------|------------|
EOF

        for mode in "${MODE_ARRAY[@]}"; do
            local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${path_prefix}android_${mode}/apk-metrics.json"
            if [ -f "$metrics_file" ]; then
                local size_bytes=$(jq -r '.size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                local dex_size_bytes=$(jq -r '.dex_size_bytes // 0' "$metrics_file" 2>/dev/null || echo "0")
                local dex_classes=$(jq -r '.dex_classes // 0' "$metrics_file" 2>/dev/null || echo "0")
                local dex_methods=$(jq -r '.dex_methods // 0' "$metrics_file" 2>/dev/null || echo "0")
                local dex_fields=$(jq -r '.dex_fields // 0' "$metrics_file" 2>/dev/null || echo "0")
                local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")
                local dex_kb=$(echo "scale=1; $dex_size_bytes / 1024" | bc 2>/dev/null || echo "0")

                echo "| $mode | $size_kb | $dex_kb | $dex_classes | $dex_methods | $dex_fields |" >> "$summary_file"
            fi
        done
    fi
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
        :root { --metro-color: #4CAF50; --dagger-ksp-color: #2196F3; --dagger-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; }
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
        td.numeric, th.numeric { text-align: right; font-family: 'SF Mono', Monaco, monospace; }
        td.framework { font-weight: 500; }
        .better { color: #43a047; }
        .worse { color: #e53935; }
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
const colors = { 'metro': '#4CAF50', 'dagger_ksp': '#2196F3', 'dagger_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0' };
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
    let totalSpeedup = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0 };
    let counts = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0 };
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
    if (typeof renderBinaryMetrics === 'function') renderBinaryMetrics();
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

    print_success "HTML report saved to $(pwd)/$html_file"
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
                "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
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

    # JVM R8 section
    if [ "$benchmark_type" = "jvm-r8" ] || [ "$benchmark_type" = "all" ]; then
        # Check if any R8 results exist
        local has_r8_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}" ]; then
                has_r8_results=true
                break
            fi
        done

        if [ "$has_r8_results" = true ]; then
            if [ "$first_test" = false ]; then echo ","; fi
            first_test=false

            echo '    {'
            echo '      "name": "JVM Startup R8 Minified (JMH)",'
            echo '      "key": "jvm_r8",'
            echo '      "unit": "ms",'
            echo '      "results": ['

            local first_mode=true
            for mode in "${MODE_ARRAY[@]}"; do
                local mode_key mode_name
                case "$mode" in
                    "metro") mode_key="metro"; mode_name="Metro" ;;
                    "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                    "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                    "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                    *) continue ;;
                esac

                local score=""
                local jvm_r8_dir="$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"
                if [ -f "$jvm_r8_dir/results.json" ]; then
                    score=$(extract_jmh_score "$jvm_r8_dir/results.json")
                fi
                if [ -z "$score" ] && [ -f "$jvm_r8_dir/results.txt" ]; then
                    score=$(grep 'graphCreationAndInitialization' "$jvm_r8_dir/results.txt" 2>/dev/null | awk '{print $4}' || echo "")
                fi
                if [ -z "$score" ] && [ -f "$jvm_r8_dir/jmh-output.txt" ]; then
                    score=$(grep 'graphCreationAndInitialization' "$jvm_r8_dir/jmh-output.txt" 2>/dev/null | grep 'avgt' | tail -1 | awk '{print $4}' || echo "")
                fi

                # Skip if no R8 results for this mode
                if [ -z "$score" ]; then
                    continue
                fi

                if [ "$first_mode" = false ]; then echo ","; fi
                first_mode=false

                echo '        {'
                echo '          "framework": "'"$mode_name"'",'
                echo '          "key": "'"$mode_key"'",'
                echo '          "value": '"$score"
                echo -n '        }'
            done

            echo ''
            echo '      ]'
            echo -n '    }'
        fi
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
                    "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                    "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
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
                "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
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
    print_header "Running JVM R8 Startup Benchmarks"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"
    for mode in "${MODE_ARRAY[@]}"; do
        print_info "Benchmarking: $mode (R8 minified)"
        run_jvm_r8_benchmark "$mode" || true
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

        # Run JVM R8 benchmarks
        print_info "Running JVM R8 benchmarks for $mode..."
        run_jvm_r8_benchmark_only "$mode" || true

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
    local skip_checkout="${5:-false}"

    print_header "Running benchmarks for: $ref_label"

    # Checkout the ref (unless skip_checkout is true for current working state)
    if [ "$skip_checkout" = true ]; then
        print_info "Using current working state (no checkout)"
    else
        checkout_ref "$ref" || return 1
    fi

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
                run_jvm_r8_benchmark_only "$mode" || true
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}" ]; then
                    mkdir -p "$ref_dir/jvm-r8_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"/* "$ref_dir/jvm-r8_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"
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
                # Run R8 benchmark
                run_jvm_r8_benchmark_only "$mode" || true
                if [ -d "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}" ]; then
                    mkdir -p "$ref_dir/jvm-r8_${mode}"
                    cp -r "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"/* "$ref_dir/jvm-r8_${mode}/" 2>/dev/null || true
                    rm -rf "$RESULTS_DIR/${TIMESTAMP}/jvm-r8_${mode}"
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

# Extract JMH R8 score for a ref
extract_jmh_r8_score_for_ref() {
    local ref_label="$1"
    local mode="$2"
    local jvm_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/jvm-r8_${mode}"
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
        jvm-r8)
            [ -d "$ref_dir/jvm-r8_${mode}" ]
            ;;
        android)
            [ -d "$ref_dir/android_${mode}" ]
            ;;
        all)
            [ -d "$ref_dir/jvm_${mode}" ] || [ -d "$ref_dir/jvm-r8_${mode}" ] || [ -d "$ref_dir/android_${mode}" ]
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

    # JVM R8 section
    if [ "$benchmark_type" = "jvm-r8" ] || [ "$benchmark_type" = "all" ]; then
        # Check if any R8 results exist
        local has_r8_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            if [ -d "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}" ]; then
                has_r8_results=true
                break
            fi
        done

        if [ "$has_r8_results" = true ]; then
            # Get metro R8 scores for "vs Metro R8" column
            local metro_jvm_r8_score1=$(extract_jmh_r8_score_for_ref "$ref1_label" "metro")
            local metro_jvm_r8_score2=""
            if mode_was_run_for_ref "$ref2_label" "metro" "jvm-r8"; then
                metro_jvm_r8_score2=$(extract_jmh_r8_score_for_ref "$ref2_label" "metro")
            fi

            cat >> "$summary_file" << EOF
## JVM Benchmarks - R8 Minified (JMH)

Graph creation and initialization time with R8 optimization (lower is better):

| Framework | $ref1_label | vs Metro R8 | $ref2_label | vs Metro R8 | Difference |
|-----------|-------------|-------------|-------------|-------------|------------|
EOF

            for mode in "${MODE_ARRAY[@]}"; do
                local score1=$(extract_jmh_r8_score_for_ref "$ref1_label" "$mode")

                # Skip if no R8 results for this mode
                if [ -z "$score1" ] && ! mode_was_run_for_ref "$ref2_label" "$mode" "jvm-r8"; then
                    continue
                fi

                # Check if this mode was run on ref2
                local mode_ran_on_ref2=false
                if mode_was_run_for_ref "$ref2_label" "$mode" "jvm-r8"; then
                    mode_ran_on_ref2=true
                fi

                local score2=""
                local display2="N/A"
                local vs_metro1="—"
                local vs_metro2="—"
                local diff="-"

                if [ "$mode_ran_on_ref2" = true ]; then
                    score2=$(extract_jmh_r8_score_for_ref "$ref2_label" "$mode")
                    if [ -n "$score2" ]; then
                        display2=$(printf "%.3f ms" "$score2")
                    fi
                elif [ "$mode" != "metro" ] && [ -n "$metro_jvm_r8_score2" ]; then
                    score2="$metro_jvm_r8_score2"
                    display2="-"
                fi

                local display1="${score1:-N/A}"
                if [ -n "$score1" ]; then
                    display1=$(printf "%.3f ms" "$score1")
                    if [ "$mode" = "metro" ]; then
                        vs_metro1="baseline"
                    elif [ -n "$metro_jvm_r8_score1" ] && [ "$metro_jvm_r8_score1" != "0" ]; then
                        local pct1=$(printf "%.0f" "$(echo "scale=4; ($score1 / $metro_jvm_r8_score1) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        local mult1=$(printf "%.1f" "$(echo "scale=4; $score1 / $metro_jvm_r8_score1" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        if [ -n "$pct1" ] && [ -n "$mult1" ]; then
                            vs_metro1="+${pct1}% (${mult1}x)"
                        fi
                    fi
                fi

                if [ -n "$score2" ]; then
                    if [ "$mode" = "metro" ]; then
                        vs_metro2="baseline"
                    elif [ -n "$metro_jvm_r8_score2" ] && [ "$metro_jvm_r8_score2" != "0" ]; then
                        local pct2=$(printf "%.0f" "$(echo "scale=4; ($score2 / $metro_jvm_r8_score2) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                        local mult2=$(printf "%.1f" "$(echo "scale=4; $score2 / $metro_jvm_r8_score2" | bc 2>/dev/null)" 2>/dev/null || echo "")
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

    # Add binary metrics comparison section
    generate_binary_metrics_comparison "$ref1_label" "$ref2_label" "$summary_file" "$benchmark_type"

    cat >> "$summary_file" << EOF
## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`${ref1_label}/\` - Results for baseline ($ref1_commit)
- \`${ref2_label}/\` - Results for comparison ($ref2_commit)
EOF

    print_success "Comparison summary saved to $(pwd)/$summary_file"
    echo ""
    cat "$summary_file"

    # Generate HTML report
    generate_html_report "$ref1_label" "$ref2_label" "$MODES" "$benchmark_type"
}

# Generate binary metrics comparison tables (for compare mode)
generate_binary_metrics_comparison() {
    local ref1_label="$1"
    local ref2_label="$2"
    local summary_file="$3"
    local benchmark_type="$4"

    IFS=',' read -ra MODE_ARRAY <<< "$MODES"

    # Check if any class metrics exist for metro
    local ref1_metro_class_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm_metro/class-metrics.json"
    local ref2_metro_class_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm_metro/class-metrics.json"

    if [ -f "$ref1_metro_class_metrics" ] && [ -f "$ref2_metro_class_metrics" ]; then
        cat >> "$summary_file" << EOF

## Binary Metrics Comparison

### Pre-Minification Component Classes (Metro)

| Metric | $ref1_label | $ref2_label | Difference |
|--------|-------------|-------------|------------|
EOF

        local ref1_fields=$(jq -r '.fields' "$ref1_metro_class_metrics" 2>/dev/null || echo "0")
        local ref2_fields=$(jq -r '.fields' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local fields_diff=$((ref2_fields - ref1_fields))
        local fields_sign=""
        if [ "$fields_diff" -gt 0 ]; then fields_sign="+"; fi
        echo "| Fields | $ref1_fields | $ref2_fields | ${fields_sign}${fields_diff} |" >> "$summary_file"

        local ref1_methods=$(jq -r '.methods' "$ref1_metro_class_metrics" 2>/dev/null || echo "0")
        local ref2_methods=$(jq -r '.methods' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local methods_diff=$((ref2_methods - ref1_methods))
        local methods_sign=""
        if [ "$methods_diff" -gt 0 ]; then methods_sign="+"; fi
        echo "| Methods | $ref1_methods | $ref2_methods | ${methods_sign}${methods_diff} |" >> "$summary_file"

        local ref1_shards=$(jq -r '.shards' "$ref1_metro_class_metrics" 2>/dev/null || echo "0")
        local ref2_shards=$(jq -r '.shards' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local shards_diff=$((ref2_shards - ref1_shards))
        local shards_sign=""
        if [ "$shards_diff" -gt 0 ]; then shards_sign="+"; fi
        echo "| Shards | $ref1_shards | $ref2_shards | ${shards_sign}${shards_diff} |" >> "$summary_file"

        local ref1_size=$(jq -r '.total_size_bytes' "$ref1_metro_class_metrics" 2>/dev/null || echo "0")
        local ref2_size=$(jq -r '.total_size_bytes' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local ref1_size_kb=$(echo "scale=1; $ref1_size / 1024" | bc 2>/dev/null || echo "0")
        local ref2_size_kb=$(echo "scale=1; $ref2_size / 1024" | bc 2>/dev/null || echo "0")
        local size_diff_bytes=$((ref2_size - ref1_size))
        local size_diff_kb=$(echo "scale=1; $size_diff_bytes / 1024" | bc 2>/dev/null || echo "0")
        local size_sign=""
        if [ "$size_diff_bytes" -gt 0 ]; then size_sign="+"; fi
        echo "| Size (KB) | $ref1_size_kb | $ref2_size_kb | ${size_sign}${size_diff_kb} |" >> "$summary_file"
    fi

    # Check if any R8 JAR metrics exist for metro
    local ref1_metro_jar_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_metro/jar-metrics.json"
    local ref2_metro_jar_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm-r8_metro/jar-metrics.json"

    if [ -f "$ref1_metro_jar_metrics" ] && [ -f "$ref2_metro_jar_metrics" ]; then
        cat >> "$summary_file" << EOF

### R8-Minified JAR (Metro)

| Metric | $ref1_label | $ref2_label | Difference |
|--------|-------------|-------------|------------|
EOF

        local ref1_jar_size=$(jq -r '.size_bytes' "$ref1_metro_jar_metrics" 2>/dev/null || echo "0")
        local ref2_jar_size=$(jq -r '.size_bytes' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local ref1_jar_kb=$(echo "scale=1; $ref1_jar_size / 1024" | bc 2>/dev/null || echo "0")
        local ref2_jar_kb=$(echo "scale=1; $ref2_jar_size / 1024" | bc 2>/dev/null || echo "0")
        local jar_diff_bytes=$((ref2_jar_size - ref1_jar_size))
        local jar_diff_kb=$(echo "scale=1; $jar_diff_bytes / 1024" | bc 2>/dev/null || echo "0")
        local jar_sign=""
        if [ "$jar_diff_bytes" -gt 0 ]; then jar_sign="+"; fi
        echo "| JAR Size (KB) | $ref1_jar_kb | $ref2_jar_kb | ${jar_sign}${jar_diff_kb} |" >> "$summary_file"

        local ref1_class_count=$(jq -r '.classes // 0' "$ref1_metro_jar_metrics" 2>/dev/null || echo "0")
        local ref2_class_count=$(jq -r '.classes // 0' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local class_diff=$((ref2_class_count - ref1_class_count))
        local class_sign=""
        if [ "$class_diff" -gt 0 ]; then class_sign="+"; fi
        echo "| Class Count | $ref1_class_count | $ref2_class_count | ${class_sign}${class_diff} |" >> "$summary_file"

        # Run diffuse JAR comparison between refs using saved JAR files
        local ref1_jar_file="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_metro/minified-jar.jar"
        local ref2_jar_file="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm-r8_metro/minified-jar.jar"

        local diffuse_dir="$RESULTS_DIR/${TIMESTAMP}/diffuse"
        mkdir -p "$diffuse_dir"

        # Run diffuse if available
        source "$SCRIPT_DIR/install-diffuse.sh" 2>/dev/null || true
        local diffuse_bin=$(get_diffuse_bin 2>/dev/null || echo "")

        if [ -x "$diffuse_bin" ] && [ -f "$ref1_jar_file" ] && [ -f "$ref2_jar_file" ]; then
            local jar_comparison_name="jar_metro_${ref1_label}_vs_${ref2_label}"
            print_step "Running diffuse JAR comparison..."
            if run_diffuse_diff "$ref1_jar_file" "$ref2_jar_file" "$diffuse_dir" "jar" "$jar_comparison_name"; then
                cat >> "$summary_file" << EOF

See \`diffuse/diffuse-${jar_comparison_name}.txt\` for detailed JAR analysis.
EOF
            fi
        fi
    fi

    # Check if APK metrics exist and run diffuse comparison
    local ref1_metro_apk="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_metro/app-release.apk"
    local ref2_metro_apk="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_metro/app-release.apk"

    local diffuse_dir="$RESULTS_DIR/${TIMESTAMP}/diffuse"
    mkdir -p "$diffuse_dir"

    # Run diffuse if available
    source "$SCRIPT_DIR/install-diffuse.sh" 2>/dev/null || true
    local diffuse_bin=$(get_diffuse_bin 2>/dev/null || echo "")

    if [ -x "$diffuse_bin" ] && [ -f "$ref1_metro_apk" ] && [ -f "$ref2_metro_apk" ]; then
        local comparison_name="apk_metro_${ref1_label}_vs_${ref2_label}"
        print_step "Running diffuse APK comparison..."
        if run_diffuse_diff "$ref1_metro_apk" "$ref2_metro_apk" "$diffuse_dir" "apk" "$comparison_name"; then
            local diffuse_output_file="$diffuse_dir/diffuse-${comparison_name}.txt"
            cat >> "$summary_file" << EOF

### Android APK (Diffuse)

\`\`\`
EOF
            # Include just the APK and DEX summary tables (first ~40 lines usually has these)
            head -50 "$diffuse_output_file" >> "$summary_file" 2>/dev/null || echo "Diffuse output not available" >> "$summary_file"
            cat >> "$summary_file" << EOF
\`\`\`

See \`diffuse/diffuse-${comparison_name}.txt\` for complete analysis.
EOF
        fi
    elif [ -f "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_metro/apk-metrics.json" ] && [ -f "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_metro/apk-metrics.json" ]; then
        # Fallback to simple size comparison if diffuse not available
        local ref1_apk_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_metro/apk-metrics.json"
        local ref2_apk_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_metro/apk-metrics.json"

        cat >> "$summary_file" << EOF

### Android APK (Metro)

| Metric | $ref1_label | $ref2_label | Difference |
|--------|-------------|-------------|------------|
EOF

        local ref1_apk_size=$(jq -r '.size_bytes' "$ref1_apk_metrics" 2>/dev/null || echo "0")
        local ref2_apk_size=$(jq -r '.size_bytes' "$ref2_apk_metrics" 2>/dev/null || echo "0")
        local ref1_apk_kb=$(echo "scale=1; $ref1_apk_size / 1024" | bc 2>/dev/null || echo "0")
        local ref2_apk_kb=$(echo "scale=1; $ref2_apk_size / 1024" | bc 2>/dev/null || echo "0")
        local apk_diff_bytes=$((ref2_apk_size - ref1_apk_size))
        local apk_diff_kb=$(echo "scale=1; $apk_diff_bytes / 1024" | bc 2>/dev/null || echo "0")
        local apk_sign=""
        if [ "$apk_diff_bytes" -gt 0 ]; then apk_sign="+"; fi
        echo "| APK Size (KB) | $ref1_apk_kb | $ref2_apk_kb | ${apk_sign}${apk_diff_kb} |" >> "$summary_file"
    fi

    # Now add comparison of Metro (ref2) vs other frameworks (ref1)
    # This shows how Metro compares to competitors after the change
    local has_other_class_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ "$mode" != "metro" ] && [ -f "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm_${mode}/class-metrics.json" ]; then
            has_other_class_metrics=true
            break
        fi
    done

    if [ "$has_other_class_metrics" = true ] && [ -f "$ref2_metro_class_metrics" ]; then
        cat >> "$summary_file" << EOF

### Framework Comparison (Metro $ref2_label vs Others $ref1_label)

Pre-minification component classes:

| Framework | Fields | Methods | Shards | Size (KB) |
|-----------|--------|---------|--------|-----------|
EOF

        # First add Metro ref2
        local metro_fields=$(jq -r '.fields' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local metro_methods=$(jq -r '.methods' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local metro_shards=$(jq -r '.shards' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local metro_size=$(jq -r '.total_size_bytes' "$ref2_metro_class_metrics" 2>/dev/null || echo "0")
        local metro_size_kb=$(echo "scale=1; $metro_size / 1024" | bc 2>/dev/null || echo "0")
        echo "| **metro** ($ref2_label) | $metro_fields | $metro_methods | $metro_shards | $metro_size_kb |" >> "$summary_file"

        # Then add other frameworks from ref1
        for mode in "${MODE_ARRAY[@]}"; do
            if [ "$mode" != "metro" ]; then
                local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm_${mode}/class-metrics.json"
                if [ -f "$metrics_file" ]; then
                    local fields=$(jq -r '.fields' "$metrics_file" 2>/dev/null || echo "0")
                    local methods=$(jq -r '.methods' "$metrics_file" 2>/dev/null || echo "0")
                    local shards=$(jq -r '.shards' "$metrics_file" 2>/dev/null || echo "0")
                    local size_bytes=$(jq -r '.total_size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                    local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")
                    echo "| $mode ($ref1_label) | $fields | $methods | $shards | $size_kb |" >> "$summary_file"
                fi
            fi
        done
    fi

    # R8 JAR framework comparison
    local has_other_jar_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ "$mode" != "metro" ] && [ -f "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}/jar-metrics.json" ]; then
            has_other_jar_metrics=true
            break
        fi
    done

    if [ "$has_other_jar_metrics" = true ] && [ -f "$ref2_metro_jar_metrics" ]; then
        cat >> "$summary_file" << EOF

R8-minified JAR:

| Framework | JAR Size (KB) | Classes | Methods | Fields |
|-----------|---------------|---------|---------|--------|
EOF

        # First add Metro ref2
        local metro_jar_size=$(jq -r '.size_bytes' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local metro_class_count=$(jq -r '.classes // 0' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local metro_method_count=$(jq -r '.methods // 0' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local metro_field_count=$(jq -r '.fields // 0' "$ref2_metro_jar_metrics" 2>/dev/null || echo "0")
        local metro_jar_kb=$(echo "scale=1; $metro_jar_size / 1024" | bc 2>/dev/null || echo "0")
        echo "| **metro** ($ref2_label) | $metro_jar_kb | $metro_class_count | $metro_method_count | $metro_field_count |" >> "$summary_file"

        # Then add other frameworks from ref1
        for mode in "${MODE_ARRAY[@]}"; do
            if [ "$mode" != "metro" ]; then
                local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}/jar-metrics.json"
                if [ -f "$metrics_file" ]; then
                    local size_bytes=$(jq -r '.size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                    local class_count=$(jq -r '.classes // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local method_count=$(jq -r '.methods // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local field_count=$(jq -r '.fields // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")
                    echo "| $mode ($ref1_label) | $size_kb | $class_count | $method_count | $field_count |" >> "$summary_file"
                fi
            fi
        done
    fi

    # APK framework comparison
    local has_other_apk_metrics=false
    for mode in "${MODE_ARRAY[@]}"; do
        if [ "$mode" != "metro" ] && [ -f "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_${mode}/apk-metrics.json" ]; then
            has_other_apk_metrics=true
            break
        fi
    done

    local ref2_metro_apk_metrics="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_metro/apk-metrics.json"
    if [ "$has_other_apk_metrics" = true ] && [ -f "$ref2_metro_apk_metrics" ]; then
        cat >> "$summary_file" << EOF

Android APK:

| Framework | APK Size (KB) | DEX Size (KB) | DEX Classes | DEX Methods | DEX Fields |
|-----------|---------------|---------------|-------------|-------------|------------|
EOF

        # First add Metro ref2
        local metro_apk_size=$(jq -r '.size_bytes' "$ref2_metro_apk_metrics" 2>/dev/null || echo "0")
        local metro_dex_size=$(jq -r '.dex_size_bytes // 0' "$ref2_metro_apk_metrics" 2>/dev/null || echo "0")
        local metro_dex_classes=$(jq -r '.dex_classes // 0' "$ref2_metro_apk_metrics" 2>/dev/null || echo "0")
        local metro_dex_methods=$(jq -r '.dex_methods // 0' "$ref2_metro_apk_metrics" 2>/dev/null || echo "0")
        local metro_dex_fields=$(jq -r '.dex_fields // 0' "$ref2_metro_apk_metrics" 2>/dev/null || echo "0")
        local metro_apk_kb=$(echo "scale=1; $metro_apk_size / 1024" | bc 2>/dev/null || echo "0")
        local metro_dex_kb=$(echo "scale=1; $metro_dex_size / 1024" | bc 2>/dev/null || echo "0")
        echo "| **metro** ($ref2_label) | $metro_apk_kb | $metro_dex_kb | $metro_dex_classes | $metro_dex_methods | $metro_dex_fields |" >> "$summary_file"

        # Then add other frameworks from ref1
        for mode in "${MODE_ARRAY[@]}"; do
            if [ "$mode" != "metro" ]; then
                local metrics_file="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_${mode}/apk-metrics.json"
                if [ -f "$metrics_file" ]; then
                    local size_bytes=$(jq -r '.size_bytes' "$metrics_file" 2>/dev/null || echo "0")
                    local dex_size=$(jq -r '.dex_size_bytes // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local dex_classes=$(jq -r '.dex_classes // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local dex_methods=$(jq -r '.dex_methods // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local dex_fields=$(jq -r '.dex_fields // 0' "$metrics_file" 2>/dev/null || echo "0")
                    local size_kb=$(echo "scale=1; $size_bytes / 1024" | bc 2>/dev/null || echo "0")
                    local dex_kb=$(echo "scale=1; $dex_size / 1024" | bc 2>/dev/null || echo "0")
                    echo "| $mode ($ref1_label) | $size_kb | $dex_kb | $dex_classes | $dex_methods | $dex_fields |" >> "$summary_file"
                fi
            fi
        done
    fi

    # Run cross-framework diffuse comparisons (metro vs other frameworks)
    # These compare Metro's ref2 build against other frameworks' ref1 builds
    source "$SCRIPT_DIR/install-diffuse.sh" 2>/dev/null || true
    local diffuse_bin=$(get_diffuse_bin 2>/dev/null || echo "")

    if [ -x "$diffuse_bin" ]; then
        local metro_ref2_apk="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_metro/app-release.apk"
        local metro_ref2_jar="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm-r8_metro/minified-jar.jar"

        for mode in "${MODE_ARRAY[@]}"; do
            if [ "$mode" != "metro" ]; then
                # APK comparison
                local other_apk="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_${mode}/app-release.apk"
                if [ -f "$metro_ref2_apk" ] && [ -f "$other_apk" ]; then
                    local cross_comparison_name="apk_metro_${ref2_label}_vs_${mode}_${ref1_label}"
                    print_step "Running cross-framework diffuse APK comparison: metro vs $mode..."
                    run_diffuse_diff "$metro_ref2_apk" "$other_apk" "$diffuse_dir" "apk" "$cross_comparison_name" || true
                fi

                # JAR comparison
                local other_jar="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}/minified-jar.jar"
                if [ -f "$metro_ref2_jar" ] && [ -f "$other_jar" ]; then
                    local cross_comparison_name="jar_metro_${ref2_label}_vs_${mode}_${ref1_label}"
                    print_step "Running cross-framework diffuse JAR comparison: metro vs $mode..."
                    run_diffuse_diff "$metro_ref2_jar" "$other_jar" "$diffuse_dir" "jar" "$cross_comparison_name" || true
                fi
            fi
        done
    fi
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

    # JVM R8 section
    if [ "$benchmark_type" = "jvm-r8" ] || [ "$benchmark_type" = "all" ]; then
        # Check if any R8 results exist
        local has_r8_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            if [ -d "$RESULTS_DIR/${TIMESTAMP}/${ref_label}/jvm-r8_${mode}" ]; then
                has_r8_results=true
                break
            fi
        done

        if [ "$has_r8_results" = true ]; then
            # Get metro R8 score for "vs Metro R8" column
            local metro_jvm_r8_score=$(extract_jmh_r8_score_for_ref "$ref_label" "metro")

            cat >> "$summary_file" << EOF
## JVM Benchmarks - R8 Minified (JMH)

Graph creation and initialization time with R8 optimization (lower is better):

| Framework | Time (ms) | vs Metro R8 |
|-----------|-----------|-------------|
EOF

            for mode in "${MODE_ARRAY[@]}"; do
                local score=$(extract_jmh_r8_score_for_ref "$ref_label" "$mode")

                # Skip if no R8 results for this mode
                if [ -z "$score" ]; then
                    continue
                fi

                local display=$(printf "%.3f" "$score")
                local vs_metro="—"

                if [ "$mode" = "metro" ]; then
                    vs_metro="baseline"
                elif [ -n "$metro_jvm_r8_score" ] && [ "$metro_jvm_r8_score" != "0" ]; then
                    local pct=$(printf "%.0f" "$(echo "scale=4; ($score / $metro_jvm_r8_score) * 100" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    local mult=$(printf "%.1f" "$(echo "scale=4; $score / $metro_jvm_r8_score" | bc 2>/dev/null)" 2>/dev/null || echo "")
                    if [ -n "$pct" ] && [ -n "$mult" ]; then
                        vs_metro="+${pct}% (${mult}x)"
                    fi
                fi
                echo "| $mode | $display | $vs_metro |" >> "$summary_file"
            done

            echo "" >> "$summary_file"
        fi
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

    # Add binary metrics summary
    generate_binary_metrics_summary "$summary_file" "$ref_label"

    cat >> "$summary_file" << EOF

## Raw Results

Results are stored in: \`$RESULTS_DIR/${TIMESTAMP}/\`

- \`${ref_label}/\` - Results ($ref_commit)
EOF

    print_success "Summary saved to $(pwd)/$summary_file"
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
                "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
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

    # JVM R8 section
    if [ "$benchmark_type" = "jvm-r8" ] || [ "$benchmark_type" = "all" ]; then
        # Check if any R8 results exist
        local has_r8_results=false
        for mode in "${MODE_ARRAY[@]}"; do
            if [ -d "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}" ]; then
                has_r8_results=true
                break
            fi
        done

        if [ "$has_r8_results" = true ]; then
            if [ "$first_test" = false ]; then echo ","; fi
            first_test=false

            echo '    {'
            echo '      "name": "JVM Startup R8 Minified (JMH)",'
            echo '      "key": "jvm_r8",'
            echo '      "unit": "ms",'
            echo '      "results": ['

            local first_mode=true
            for mode in "${MODE_ARRAY[@]}"; do
                local mode_key
                local mode_name
                case "$mode" in
                    "metro") mode_key="metro"; mode_name="Metro" ;;
                    "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                    "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                    "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject-anvil" ;;
                    *) continue ;;
                esac

                local score1=$(extract_jmh_r8_score_for_ref "$ref1_label" "$mode")
                local score2=""
                if [ -n "$ref2_label" ]; then
                    score2=$(extract_jmh_r8_score_for_ref "$ref2_label" "$mode")
                fi

                # Skip if no R8 results for this mode in either ref
                if [ -z "$score1" ] && [ -z "$score2" ]; then
                    continue
                fi

                if [ "$first_mode" = false ]; then echo ","; fi
                first_mode=false

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
                    "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                    "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
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
                "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
                "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
                "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
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
    echo '  ],'

    # Binary metrics section
    echo '  "binaryMetrics": {'

    # Class metrics
    echo '    "classes": ['
    local first_mode=true
    for mode in "${MODE_ARRAY[@]}"; do
        local mode_key
        local mode_name
        case "$mode" in
            "metro") mode_key="metro"; mode_name="Metro" ;;
            "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
            "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
            "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
            *) continue ;;
        esac

        local metrics_file1="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm_${mode}/class-metrics.json"
        local metrics_file2=""
        if [ -n "$ref2_label" ]; then
            metrics_file2="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm_${mode}/class-metrics.json"
        fi

        # Skip if no metrics exist for this mode
        if [ ! -f "$metrics_file1" ] && [ -z "$metrics_file2" -o ! -f "$metrics_file2" ]; then
            continue
        fi

        if [ "$first_mode" = false ]; then echo ","; fi
        first_mode=false

        echo '      {'
        echo '        "framework": "'"$mode_name"'",'
        echo '        "key": "'"$mode_key"'",'

        if [ -f "$metrics_file1" ]; then
            local fields1=$(jq -r '.fields' "$metrics_file1" 2>/dev/null || echo "0")
            local methods1=$(jq -r '.methods' "$metrics_file1" 2>/dev/null || echo "0")
            local shards1=$(jq -r '.shards' "$metrics_file1" 2>/dev/null || echo "0")
            local size1=$(jq -r '.total_size_bytes' "$metrics_file1" 2>/dev/null || echo "0")
            local classes1=$(jq -c '.classes' "$metrics_file1" 2>/dev/null || echo "[]")
            echo '        "ref1": { "fields": '"$fields1"', "methods": '"$methods1"', "shards": '"$shards1"', "sizeBytes": '"$size1"', "classes": '"$classes1"' },'
        else
            echo '        "ref1": null,'
        fi

        if [ -n "$ref2_label" ] && [ -f "$metrics_file2" ]; then
            local fields2=$(jq -r '.fields' "$metrics_file2" 2>/dev/null || echo "0")
            local methods2=$(jq -r '.methods' "$metrics_file2" 2>/dev/null || echo "0")
            local shards2=$(jq -r '.shards' "$metrics_file2" 2>/dev/null || echo "0")
            local size2=$(jq -r '.total_size_bytes' "$metrics_file2" 2>/dev/null || echo "0")
            local classes2=$(jq -c '.classes' "$metrics_file2" 2>/dev/null || echo "[]")
            echo '        "ref2": { "fields": '"$fields2"', "methods": '"$methods2"', "shards": '"$shards2"', "sizeBytes": '"$size2"', "classes": '"$classes2"' }'
        else
            echo '        "ref2": null'
        fi

        echo -n '      }'
    done
    echo ''
    echo '    ],'

    # R8 JAR metrics
    echo '    "r8Jars": ['
    first_mode=true
    for mode in "${MODE_ARRAY[@]}"; do
        local mode_key
        local mode_name
        case "$mode" in
            "metro") mode_key="metro"; mode_name="Metro" ;;
            "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
            "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
            "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
            *) continue ;;
        esac

        local jar_file1="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/jvm-r8_${mode}/jar-metrics.json"
        local jar_file2=""
        if [ -n "$ref2_label" ]; then
            jar_file2="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/jvm-r8_${mode}/jar-metrics.json"
        fi

        if [ ! -f "$jar_file1" ] && [ -z "$jar_file2" -o ! -f "$jar_file2" ]; then
            continue
        fi

        if [ "$first_mode" = false ]; then echo ","; fi
        first_mode=false

        echo '      {'
        echo '        "framework": "'"$mode_name"'",'
        echo '        "key": "'"$mode_key"'",'

        if [ -f "$jar_file1" ]; then
            local size1=$(jq -r '.size_bytes' "$jar_file1" 2>/dev/null || echo "0")
            local fields1=$(jq -r '.fields // 0' "$jar_file1" 2>/dev/null || echo "0")
            local methods1=$(jq -r '.methods // 0' "$jar_file1" 2>/dev/null || echo "0")
            local classes1=$(jq -r '.classes // 0' "$jar_file1" 2>/dev/null || echo "0")
            echo '        "ref1": { "sizeBytes": '"$size1"', "fields": '"$fields1"', "methods": '"$methods1"', "classCount": '"$classes1"' },'
        else
            echo '        "ref1": null,'
        fi

        if [ -n "$ref2_label" ] && [ -f "$jar_file2" ]; then
            local size2=$(jq -r '.size_bytes' "$jar_file2" 2>/dev/null || echo "0")
            local fields2=$(jq -r '.fields // 0' "$jar_file2" 2>/dev/null || echo "0")
            local methods2=$(jq -r '.methods // 0' "$jar_file2" 2>/dev/null || echo "0")
            local classes2=$(jq -r '.classes // 0' "$jar_file2" 2>/dev/null || echo "0")
            echo '        "ref2": { "sizeBytes": '"$size2"', "fields": '"$fields2"', "methods": '"$methods2"', "classCount": '"$classes2"' }'
        else
            echo '        "ref2": null'
        fi

        echo -n '      }'
    done
    echo ''
    echo '    ],'

    # APK metrics
    echo '    "apks": ['
    first_mode=true
    for mode in "${MODE_ARRAY[@]}"; do
        local mode_key
        local mode_name
        case "$mode" in
            "metro") mode_key="metro"; mode_name="Metro" ;;
            "dagger-ksp") mode_key="dagger_ksp"; mode_name="Dagger (KSP)" ;;
            "dagger-kapt") mode_key="dagger_kapt"; mode_name="Dagger (KAPT)" ;;
            "kotlin-inject-anvil") mode_key="kotlin_inject_anvil"; mode_name="kotlin-inject" ;;
            *) continue ;;
        esac

        local apk_file1="$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/android_${mode}/apk-metrics.json"
        local apk_file2=""
        if [ -n "$ref2_label" ]; then
            apk_file2="$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/android_${mode}/apk-metrics.json"
        fi

        if [ ! -f "$apk_file1" ] && [ -z "$apk_file2" -o ! -f "$apk_file2" ]; then
            continue
        fi

        if [ "$first_mode" = false ]; then echo ","; fi
        first_mode=false

        echo '      {'
        echo '        "framework": "'"$mode_name"'",'
        echo '        "key": "'"$mode_key"'",'

        if [ -f "$apk_file1" ]; then
            local size1=$(jq -r '.size_bytes' "$apk_file1" 2>/dev/null || echo "0")
            local dex_size1=$(jq -r '.dex_size_bytes // 0' "$apk_file1" 2>/dev/null || echo "0")
            local dex_classes1=$(jq -r '.dex_classes // 0' "$apk_file1" 2>/dev/null || echo "0")
            local dex_methods1=$(jq -r '.dex_methods // 0' "$apk_file1" 2>/dev/null || echo "0")
            local dex_fields1=$(jq -r '.dex_fields // 0' "$apk_file1" 2>/dev/null || echo "0")
            echo '        "ref1": { "sizeBytes": '"$size1"', "dexSizeBytes": '"$dex_size1"', "dexClasses": '"$dex_classes1"', "dexMethods": '"$dex_methods1"', "dexFields": '"$dex_fields1"' },'
        else
            echo '        "ref1": null,'
        fi

        if [ -n "$ref2_label" ] && [ -f "$apk_file2" ]; then
            local size2=$(jq -r '.size_bytes' "$apk_file2" 2>/dev/null || echo "0")
            local dex_size2=$(jq -r '.dex_size_bytes // 0' "$apk_file2" 2>/dev/null || echo "0")
            local dex_classes2=$(jq -r '.dex_classes // 0' "$apk_file2" 2>/dev/null || echo "0")
            local dex_methods2=$(jq -r '.dex_methods // 0' "$apk_file2" 2>/dev/null || echo "0")
            local dex_fields2=$(jq -r '.dex_fields // 0' "$apk_file2" 2>/dev/null || echo "0")
            echo '        "ref2": { "sizeBytes": '"$size2"', "dexSizeBytes": '"$dex_size2"', "dexClasses": '"$dex_classes2"', "dexMethods": '"$dex_methods2"', "dexFields": '"$dex_fields2"' }'
        else
            echo '        "ref2": null'
        fi

        echo -n '      }'
    done
    echo ''
    echo '    ]'

    echo '  }'
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
        :root { --metro-color: #4CAF50; --dagger-ksp-color: #2196F3; --dagger-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; }
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
        td.numeric, th.numeric { text-align: right; font-family: 'SF Mono', Monaco, monospace; }
        td.framework { font-weight: 500; }
        .better { color: #43a047; }
        .worse { color: #e53935; }
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
        <div id="binaryMetrics"></div>
        <div id="metadata"></div>
    </div>
<script>
const benchmarkData =
HTMLHEAD

    echo "$json_data" >> "$html_file"

    cat >> "$html_file" << 'HTMLTAIL'
;
const colors = { 'metro': '#4CAF50', 'dagger_ksp': '#2196F3', 'dagger_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0' };

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
    if (typeof renderBinaryMetrics === 'function') renderBinaryMetrics();
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

function formatBytes(bytes) {
    if (bytes === null || bytes === undefined) return '—';
    const kb = bytes / 1024;
    if (kb < 1024) return kb.toFixed(1) + ' KB';
    return (kb / 1024).toFixed(2) + ' MB';
}

function formatDiffBytes(ref1, ref2) {
    if (!ref1 || !ref2) return '—';
    const diff = ref2 - ref1;
    const pct = ref1 ? ((diff / ref1) * 100).toFixed(1) : 0;
    const sign = diff > 0 ? '+' : '';
    const cls = diff < 0 ? 'better' : (diff > 0 ? 'worse' : '');
    return `<span class="${cls}">${sign}${formatBytes(diff)} (${sign}${pct}%)</span>`;
}

function formatDiffCount(ref1, ref2) {
    if (ref1 === null || ref1 === undefined || ref2 === null || ref2 === undefined) return '—';
    const diff = ref2 - ref1;
    if (diff === 0) return '<span>0</span>';
    const pct = ref1 ? ((diff / ref1) * 100).toFixed(1) : 0;
    const sign = diff > 0 ? '+' : '';
    const cls = diff < 0 ? 'better' : (diff > 0 ? 'worse' : '');
    return `<span class="${cls}">${sign}${diff.toLocaleString()} (${sign}${pct}%)</span>`;
}

// Format value with delta annotation on second line for readability
function formatBytesWithDelta(newVal, oldVal) {
    if (newVal === null || newVal === undefined) return '—';
    const formatted = formatBytes(newVal);
    if (oldVal === null || oldVal === undefined) return formatted;
    const diff = newVal - oldVal;
    if (diff === 0) return formatted;
    const pct = oldVal ? ((diff / oldVal) * 100).toFixed(1) : 0;
    const sign = diff > 0 ? '+' : '';
    const cls = diff < 0 ? 'better' : (diff > 0 ? 'worse' : '');
    return `${formatted}<br><span class="${cls}">(${sign}${pct}%)</span>`;
}

function formatCountWithDelta(newVal, oldVal) {
    if (newVal === null || newVal === undefined) return '—';
    const formatted = newVal.toLocaleString();
    if (oldVal === null || oldVal === undefined) return formatted;
    const diff = newVal - oldVal;
    if (diff === 0) return formatted;
    const pct = oldVal ? ((diff / oldVal) * 100).toFixed(1) : 0;
    const sign = diff > 0 ? '+' : '';
    const cls = diff < 0 ? 'better' : (diff > 0 ? 'worse' : '');
    return `${formatted}<br><span class="${cls}">(${sign}${pct}%)</span>`;
}

function formatVsBaseline(value, baselineValue) {
    if (value === null || value === undefined || baselineValue === null || baselineValue === undefined) return '—';
    if (value === baselineValue) return '<span class="vs-baseline baseline">baseline</span>';
    const pct = ((value - baselineValue) / baselineValue * 100).toFixed(1);
    const sign = pct > 0 ? '+' : '';
    // For size/count metrics: lower is better, so positive % is worse
    const cls = pct < 0 ? 'better' : (pct > 0 ? 'worse' : '');
    return `<span class="vs-baseline ${cls}">${sign}${pct}%</span>`;
}

function renderBinaryMetrics() {
    const bm = benchmarkData.binaryMetrics;
    if (!bm) return;
    const container = document.getElementById('binaryMetrics');
    const hasRef2 = benchmarkData.refs?.ref2;

    // Get list of available frameworks
    const frameworks = [];
    if (bm.classes) bm.classes.forEach(c => { if (!frameworks.find(f => f.key === c.key)) frameworks.push({key: c.key, name: c.framework}); });
    if (bm.r8Jars) bm.r8Jars.forEach(j => { if (!frameworks.find(f => f.key === j.key)) frameworks.push({key: j.key, name: j.framework}); });
    if (bm.apks) bm.apks.forEach(a => { if (!frameworks.find(f => f.key === a.key)) frameworks.push({key: a.key, name: a.framework}); });

    // Default baseline to metro if available
    if (!frameworks.find(f => f.key === selectedBaseline)) {
        selectedBaseline = frameworks[0]?.key || 'metro';
    }

    const showVsBaseline = frameworks.length > 1;
    const getBaseline = (items, refKey) => items?.find(i => i.key === selectedBaseline)?.[refKey];

    let html = '';

    // Class metrics
    if (bm.classes && bm.classes.length > 0) {
        const baselineData = getBaseline(bm.classes, 'ref1');
        // Get metro's ref2 data for comparing non-metro frameworks
        const metroClass = bm.classes.find(c => c.key === 'metro');
        const metroRef2 = metroClass?.ref2;

        html += '<div class="benchmark-section"><h2>Binary Metrics: Pre-Minification Classes</h2>';
        html += '<table><thead><tr>';
        if (showVsBaseline) html += '<th></th>';
        html += '<th>Framework</th>';
        html += '<th class="numeric">Fields</th><th class="numeric">Methods</th><th class="numeric">Shards</th><th class="numeric">Size</th><th class="numeric">Classes</th>';
        if (showVsBaseline) html += '<th class="numeric">vs <span class="baseline-header">' + getBaselineLabel() + '</span></th>';
        if (hasRef2) html += '<th class="numeric">ref2 Fields</th><th class="numeric">ref2 Methods</th><th class="numeric">ref2 Shards</th><th class="numeric">ref2 Size</th>';
        html += '</tr></thead><tbody>';
        bm.classes.forEach(c => {
            const isBaseline = c.key === selectedBaseline;
            const rowClass = isBaseline ? 'baseline-row' : '';
            // For non-metro frameworks, use metro's ref2 as comparison target
            const isMetro = c.key === 'metro';
            const compareData = isMetro ? c.ref2 : metroRef2;
            const compareRef1 = isMetro ? c.ref1 : c.ref1;

            html += `<tr class="${rowClass}">`;
            if (showVsBaseline) html += `<td class="baseline-select" onclick="setBaseline('${c.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>`;
            html += `<td class="framework" style="color: ${colors[c.key]}">${c.framework}</td>`;
            html += `<td class="numeric">${c.ref1?.fields ?? '—'}</td>`;
            html += `<td class="numeric">${c.ref1?.methods ?? '—'}</td>`;
            html += `<td class="numeric">${c.ref1?.shards ?? '—'}</td>`;
            html += `<td class="numeric">${c.ref1 ? formatBytes(c.ref1.sizeBytes) : '—'}</td>`;
            html += `<td class="numeric">${c.ref1?.classes?.length ?? '—'}</td>`;
            if (showVsBaseline) html += `<td class="numeric">${formatVsBaseline(c.ref1?.sizeBytes, baselineData?.sizeBytes)}</td>`;
            if (hasRef2) {
                // Show ref2 values with delta annotation (comparing to ref1)
                html += `<td class="numeric">${formatCountWithDelta(compareData?.fields, compareRef1?.fields)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.methods, compareRef1?.methods)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.shards, compareRef1?.shards)}</td>`;
                html += `<td class="numeric">${formatBytesWithDelta(compareData?.sizeBytes, compareRef1?.sizeBytes)}</td>`;
            }
            html += '</tr>';
        });
        html += '</tbody></table></div>';
    }

    // R8 JAR metrics
    if (bm.r8Jars && bm.r8Jars.length > 0) {
        const baselineData = getBaseline(bm.r8Jars, 'ref1');
        // Get metro's ref2 data for comparing non-metro frameworks
        const metroJar = bm.r8Jars.find(j => j.key === 'metro');
        const metroRef2 = metroJar?.ref2;

        html += '<div class="benchmark-section"><h2>Binary Metrics: R8-Minified JAR</h2>';
        html += '<table><thead><tr>';
        if (showVsBaseline) html += '<th></th>';
        html += '<th>Framework</th>';
        html += '<th class="numeric">JAR Size</th><th class="numeric">Classes</th><th class="numeric">Methods</th><th class="numeric">Fields</th>';
        if (showVsBaseline) html += '<th class="numeric">vs <span class="baseline-header">' + getBaselineLabel() + '</span></th>';
        if (hasRef2) html += '<th class="numeric">ref2 Size</th><th class="numeric">ref2 Classes</th><th class="numeric">ref2 Methods</th><th class="numeric">ref2 Fields</th>';
        html += '</tr></thead><tbody>';
        bm.r8Jars.forEach(j => {
            const isBaseline = j.key === selectedBaseline;
            const rowClass = isBaseline ? 'baseline-row' : '';
            // For non-metro frameworks, use metro's ref2 as comparison target
            const isMetro = j.key === 'metro';
            const compareData = isMetro ? j.ref2 : metroRef2;
            const compareRef1 = isMetro ? j.ref1 : j.ref1;

            html += `<tr class="${rowClass}">`;
            if (showVsBaseline) html += `<td class="baseline-select" onclick="setBaseline('${j.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>`;
            html += `<td class="framework" style="color: ${colors[j.key]}">${j.framework}</td>`;
            html += `<td class="numeric">${j.ref1 ? formatBytes(j.ref1.sizeBytes) : '—'}</td>`;
            html += `<td class="numeric">${j.ref1?.classCount?.toLocaleString() ?? '—'}</td>`;
            html += `<td class="numeric">${j.ref1?.methods?.toLocaleString() ?? '—'}</td>`;
            html += `<td class="numeric">${j.ref1?.fields?.toLocaleString() ?? '—'}</td>`;
            if (showVsBaseline) html += `<td class="numeric">${formatVsBaseline(j.ref1?.sizeBytes, baselineData?.sizeBytes)}</td>`;
            if (hasRef2) {
                // Show ref2 values with delta annotation (comparing to ref1)
                html += `<td class="numeric">${formatBytesWithDelta(compareData?.sizeBytes, compareRef1?.sizeBytes)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.classCount, compareRef1?.classCount)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.methods, compareRef1?.methods)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.fields, compareRef1?.fields)}</td>`;
            }
            html += '</tr>';
        });
        html += '</tbody></table></div>';
    }

    // APK metrics
    if (bm.apks && bm.apks.length > 0) {
        const baselineData = getBaseline(bm.apks, 'ref1');
        // Get metro's ref2 data for comparing non-metro frameworks
        const metroApk = bm.apks.find(a => a.key === 'metro');
        const metroRef2 = metroApk?.ref2;

        html += '<div class="benchmark-section"><h2>Binary Metrics: Android APK</h2>';
        html += '<table><thead><tr>';
        if (showVsBaseline) html += '<th></th>';
        html += '<th>Framework</th>';
        html += '<th class="numeric">APK Size</th>';
        html += '<th class="numeric">DEX Size</th>';
        html += '<th class="numeric">DEX Classes</th>';
        html += '<th class="numeric">DEX Methods</th>';
        html += '<th class="numeric">DEX Fields</th>';
        if (showVsBaseline) html += '<th class="numeric">vs <span class="baseline-header">' + getBaselineLabel() + '</span></th>';
        if (hasRef2) html += '<th class="numeric">ref2 APK</th><th class="numeric">ref2 DEX</th><th class="numeric">ref2 Classes</th><th class="numeric">ref2 Methods</th><th class="numeric">ref2 Fields</th>';
        html += '</tr></thead><tbody>';
        bm.apks.forEach(a => {
            const isBaseline = a.key === selectedBaseline;
            const rowClass = isBaseline ? 'baseline-row' : '';
            // For non-metro frameworks, use metro's ref2 as comparison target
            const isMetro = a.key === 'metro';
            const compareData = isMetro ? a.ref2 : metroRef2;
            const compareRef1 = isMetro ? a.ref1 : a.ref1;

            html += `<tr class="${rowClass}">`;
            if (showVsBaseline) html += `<td class="baseline-select" onclick="setBaseline('${a.key}')"><span class="baseline-radio ${isBaseline ? 'selected' : ''}"></span></td>`;
            html += `<td class="framework" style="color: ${colors[a.key]}">${a.framework}</td>`;
            html += `<td class="numeric">${a.ref1 ? formatBytes(a.ref1.sizeBytes) : '—'}</td>`;
            html += `<td class="numeric">${a.ref1?.dexSizeBytes ? formatBytes(a.ref1.dexSizeBytes) : '—'}</td>`;
            html += `<td class="numeric">${a.ref1?.dexClasses?.toLocaleString() ?? '—'}</td>`;
            html += `<td class="numeric">${a.ref1?.dexMethods?.toLocaleString() ?? '—'}</td>`;
            html += `<td class="numeric">${a.ref1?.dexFields?.toLocaleString() ?? '—'}</td>`;
            if (showVsBaseline) html += `<td class="numeric">${formatVsBaseline(a.ref1?.sizeBytes, baselineData?.sizeBytes)}</td>`;
            if (hasRef2) {
                // Show ref2 values with delta annotation (comparing to ref1)
                html += `<td class="numeric">${formatBytesWithDelta(compareData?.sizeBytes, compareRef1?.sizeBytes)}</td>`;
                html += `<td class="numeric">${formatBytesWithDelta(compareData?.dexSizeBytes, compareRef1?.dexSizeBytes)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.dexClasses, compareRef1?.dexClasses)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.dexMethods, compareRef1?.dexMethods)}</td>`;
                html += `<td class="numeric">${formatCountWithDelta(compareData?.dexFields, compareRef1?.dexFields)}</td>`;
            }
            html += '</tr>';
        });
        html += '</tbody></table></div>';
    }

    container.innerHTML = html;
}

document.getElementById('date').textContent = new Date(benchmarkData.date).toLocaleString();
renderRefsInfo(); renderBenchmarks(); renderBinaryMetrics(); renderMetadata();
</script>
</body>
</html>
HTMLTAIL

    print_success "HTML report saved to $(pwd)/$html_file"
}

# Run single ref command
run_single() {
    local benchmark_type="${COMPARE_BENCHMARK_TYPE:-all}"

    if [ -z "$SINGLE_REF" ]; then
        print_error "Single requires --ref argument"
        show_usage
        exit 1
    fi

    # Check if using current working state (HEAD with possible uncommitted changes)
    local use_current_state=false
    if [ "$SINGLE_REF" = "HEAD" ] || [ "$SINGLE_REF" = "head" ] || [ "$SINGLE_REF" = "current" ]; then
        use_current_state=true
        SINGLE_REF="HEAD"
    fi

    # Validate ref exists (HEAD always exists)
    if [ "$use_current_state" = false ]; then
        if ! git rev-parse --verify "$SINGLE_REF" > /dev/null 2>&1; then
            print_error "Invalid git ref: $SINGLE_REF"
            exit 1
        fi

        # Check for uncommitted changes only when checking out a different ref
        if ! git diff-index --quiet HEAD -- 2>/dev/null; then
            print_error "You have uncommitted changes. Please commit or stash them before running benchmarks."
            print_info "Or use '--ref HEAD' to benchmark the current working state (including uncommitted changes)."
            exit 1
        fi
    fi

    print_header "Running Benchmarks on Single Git Ref"
    if [ "$use_current_state" = true ]; then
        print_info "Ref: HEAD (current working state)"
    else
        print_info "Ref: $SINGLE_REF"
    fi
    print_info "Benchmark type: $benchmark_type"
    print_info "Modes: $MODES"
    echo ""

    # Create safe label for directory name
    local ref_label
    if [ "$use_current_state" = true ]; then
        # Use current branch name or "HEAD" if detached
        ref_label=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")
        if [ "$ref_label" = "HEAD" ]; then
            ref_label="current"
        fi
    else
        ref_label=$(get_ref_safe_name "$SINGLE_REF")
    fi

    if [ "$use_current_state" = false ]; then
        # Save current git state and set up restore trap
        save_git_state
        trap 'restore_git_state' EXIT
    fi

    # Run benchmarks for the ref (all modes, not second ref)
    # Pass use_current_state as 5th arg to skip checkout
    run_benchmarks_for_ref "$SINGLE_REF" "$benchmark_type" "$ref_label" false "$use_current_state" || {
        print_error "Failed to run benchmarks for $SINGLE_REF"
        exit 1
    }

    # Generate summary
    generate_single_summary "$ref_label" "$benchmark_type"

    print_final_results "$RESULTS_DIR/${TIMESTAMP}"
}

# Run compare command
run_compare() {
    local benchmark_type="${COMPARE_BENCHMARK_TYPE:-all}"

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

    print_final_results "$RESULTS_DIR/${TIMESTAMP}"

    # Restore will happen via trap
}

# Default benchmark type for single/compare commands
COMPARE_BENCHMARK_TYPE="all"

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
            --binary-metrics-only)
                BINARY_METRICS_ONLY=true
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
        print_final_results "$RESULTS_DIR/${TIMESTAMP}"
    fi
}

main "$@"
