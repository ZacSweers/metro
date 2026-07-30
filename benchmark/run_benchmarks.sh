#!/bin/bash

# Metro vs Anvil Benchmark Runner
# 
# This script automatically regenerates projects for each mode and runs
# the corresponding benchmark scenarios to compare performance.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source common utilities
source "$SCRIPT_DIR/benchmark-utils.sh"

# Configuration
DEFAULT_MODULE_COUNT=500
DEFAULT_SEED=0
RESULTS_DIR="benchmark-results"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
WORKLOAD_SEED=$DEFAULT_SEED
WORKLOAD_MANIFEST_FILE="$SCRIPT_DIR/workload-manifest.json"
WORKLOAD_FINGERPRINT=""
WORKLOAD_MODULE_COUNT=""
WORKLOAD_CORE_MODULE_COUNT=""
WORKLOAD_FEATURE_MODULE_COUNT=""
WORKLOAD_APP_MODULE_COUNT=""
WORKLOAD_DEPENDENCY_EDGE_COUNT=""
WORKLOAD_CONTRIBUTION_COUNT=""
WORKLOAD_BINDING_COUNT=""
WORKLOAD_PLUGIN_COUNT=""
WORKLOAD_INITIALIZER_COUNT=""
WORKLOAD_L1_SUBCOMPONENT_COUNT=""
WORKLOAD_L2_PER_L1=""
WORKLOAD_L3_PER_L2=""
WORKLOAD_SUBCOMPONENT_COUNT=""
INCLUDE_CLEAN_BUILDS=false

# Mode lists
# Standard published modes
STANDARD_MODES="metro,dagger-ksp,dagger-kapt,kotlin-inject-anvil,koin"
# Optional compiler-plugin overhead baseline
BASELINE_MODES="metro-noop"
# All modes including the optional baseline
ALL_MODES_WITH_BASELINES="metro,metro-noop,dagger-ksp,dagger-kapt,kotlin-inject-anvil,koin"

# Git refs
SINGLE_REF=""
COMPARE_REF1=""
COMPARE_REF2=""
COMPARE_MODES="$STANDARD_MODES"
# Scenarios filter (empty = all default scenarios for the mode)
SCENARIOS_FILTER=""
ORIGINAL_GIT_REF=""
ORIGINAL_GIT_IS_BRANCH=false
# Whether to re-run non-metro modes in ref2 (default: false to save time)
RERUN_NON_METRO=false
# Whether to include the optional Metro-NOOP baseline
INCLUDE_BASELINES=false
# Profile options to pass to gradle-profiler (e.g., "jfr", "async-profiler-heap")
PROFILE_OPTIONS=()
PROFILER_GRADLE_USER_HOME="${BENCHMARK_GRADLE_USER_HOME:-$SCRIPT_DIR/tmp/gradle-profiler-home}"
BENCHMARK_MIN_IDLE_PERCENT="${BENCHMARK_MIN_IDLE_PERCENT:-85}"
BENCHMARK_IDLE_SAMPLES="${BENCHMARK_IDLE_SAMPLES:-3}"
BENCHMARK_IDLE_TIMEOUT_SECONDS="${BENCHMARK_IDLE_TIMEOUT_SECONDS:-600}"
BENCHMARK_COOLDOWN_SECONDS="${BENCHMARK_COOLDOWN_SECONDS:-30}"
BENCHMARK_INCREMENTAL_WARMUP_COUNT=5
BENCHMARK_GRAPH_PROCESSING_WARMUP_COUNT=10
BENCHMARK_CLEAN_WARMUP_COUNT=2
BENCHMARK_MEASURED_SAMPLE_COUNT=10
BENCHMARK_MAX_RELATIVE_MAD_PERCENT=10
BENCHMARK_MAX_HALF_DRIFT_PERCENT=10
BENCHMARK_OUTLIER_THRESHOLD_PERCENT=20
BENCHMARK_MAX_OUTLIER_COUNT=1
BENCHMARK_STABILITY_CHECKER="$SCRIPT_DIR/check-benchmark-stability.py"
export BENCHMARK_COOLDOWN_SECONDS

# Script-specific print functions (styles differ from run_startup_benchmarks.sh)
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

json_quote() {
    python3 - "$1" << 'PY'
import json
import sys

print(json.dumps(sys.argv[1]))
PY
}

load_workload_manifest_metadata() {
    local manifest_file="$1"

    if [ ! -f "$manifest_file" ]; then
        print_error "Missing workload manifest: $manifest_file" >&2
        return 1
    fi

    local values
    if ! values=$(python3 - "$manifest_file" << 'PY'
import json
import re
import sys

manifest_path = sys.argv[1]
with open(manifest_path, encoding="utf-8") as manifest_file:
    manifest = json.load(manifest_file)

if manifest.get("schemaVersion") != 1:
    raise SystemExit(f"Unsupported workload manifest schema: {manifest.get('schemaVersion')!r}")

fingerprint = manifest.get("fingerprint")
if not isinstance(fingerprint, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint):
    raise SystemExit(f"Invalid workload fingerprint: {fingerprint!r}")

workload = manifest["workload"]
modules_by_layer = workload["modulesByLayer"]
contributions_by_kind = workload["contributionsByKind"]
subcomponents = workload["subcomponents"]

values = [
    fingerprint,
    workload["seed"],
    workload["moduleCount"],
    modules_by_layer["core"],
    modules_by_layer["features"],
    modules_by_layer["app"],
    workload["dependencyEdgeCount"],
    workload["contributionCount"],
    contributions_by_kind["binding"],
    contributions_by_kind["plugin"],
    contributions_by_kind["initializer"],
    subcomponents["l1"],
    subcomponents["l2PerL1"],
    subcomponents["l3PerL2"],
    subcomponents["total"],
]

if not all(isinstance(value, int) for value in values[1:]):
    raise SystemExit("Workload manifest counts and seed must be integers")

print("|".join(str(value) for value in values))
PY
    ); then
        print_error "Invalid workload manifest: $manifest_file" >&2
        return 1
    fi

    local manifest_seed
    IFS='|' read -r \
        WORKLOAD_FINGERPRINT \
        manifest_seed \
        WORKLOAD_MODULE_COUNT \
        WORKLOAD_CORE_MODULE_COUNT \
        WORKLOAD_FEATURE_MODULE_COUNT \
        WORKLOAD_APP_MODULE_COUNT \
        WORKLOAD_DEPENDENCY_EDGE_COUNT \
        WORKLOAD_CONTRIBUTION_COUNT \
        WORKLOAD_BINDING_COUNT \
        WORKLOAD_PLUGIN_COUNT \
        WORKLOAD_INITIALIZER_COUNT \
        WORKLOAD_L1_SUBCOMPONENT_COUNT \
        WORKLOAD_L2_PER_L1 \
        WORKLOAD_L3_PER_L2 \
        WORKLOAD_SUBCOMPONENT_COUNT \
        <<< "$values"

    if [ "$manifest_seed" != "$WORKLOAD_SEED" ]; then
        print_error "Workload manifest seed $manifest_seed does not match requested seed $WORKLOAD_SEED" >&2
        return 1
    fi
}

verify_and_capture_workload_manifest() {
    local mode="$1"
    local ref_dir="$2"
    local expected_module_count="$3"
    local canonical_manifest="$RESULTS_DIR/${TIMESTAMP}/workload-manifest.json"
    local ref_manifest="$ref_dir/workload-manifest.json"

    if ! load_workload_manifest_metadata "$WORKLOAD_MANIFEST_FILE"; then
        return 1
    fi

    if [ "$WORKLOAD_MODULE_COUNT" != "$expected_module_count" ]; then
        print_error "Workload manifest module count $WORKLOAD_MODULE_COUNT does not match requested count $expected_module_count" >&2
        return 1
    fi

    if [ -f "$canonical_manifest" ]; then
        if ! cmp -s "$canonical_manifest" "$WORKLOAD_MANIFEST_FILE"; then
            print_error "Generated workload for $mode does not match the first benchmark mode" >&2
            print_error "Expected the byte-stable manifest and fingerprint to remain identical across modes" >&2
            return 1
        fi
    else
        cp "$WORKLOAD_MANIFEST_FILE" "$canonical_manifest"
    fi

    if [ -f "$ref_manifest" ]; then
        if ! cmp -s "$ref_manifest" "$WORKLOAD_MANIFEST_FILE"; then
            print_error "Generated workload for $mode does not match the first mode for this ref" >&2
            return 1
        fi
    else
        cp "$WORKLOAD_MANIFEST_FILE" "$ref_manifest"
    fi

    print_success "Verified workload for $mode: $WORKLOAD_FINGERPRINT"
}

load_report_workload_metadata() {
    local canonical_manifest="$RESULTS_DIR/${TIMESTAMP}/workload-manifest.json"
    load_workload_manifest_metadata "$canonical_manifest"
}

# Source the gradle-profiler installer script
source "$SCRIPT_DIR/install-gradle-profiler.sh"

# Get the path to gradle-profiler binary
GRADLE_PROFILER_BIN="$(get_gradle_profiler_bin)"

# Collect build environment metadata and save to JSON file
collect_build_metadata() {
    local output_dir="$1"
    local metadata_file="$output_dir/build-metadata.json"
    local dirty_diff_file="$output_dir/repo-dirty-diff-fingerprint.txt"
    local dirty_diff_fingerprint

    if [ ! -f "$dirty_diff_file" ]; then
        print_error "Missing repository dirty diff fingerprint: $dirty_diff_file" >&2
        return 1
    fi
    dirty_diff_fingerprint=$(cat "$dirty_diff_file")

    print_status "Collecting build environment metadata..."

    # Get repo root for libs.versions.toml
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local versions_file="$repo_root/gradle/libs.versions.toml"
    local metro_version="${METRO_VERSION:-}"
    if [ -z "$metro_version" ]; then
        metro_version=$(grep "^VERSION_NAME=" "$repo_root/gradle.properties" 2>/dev/null | cut -d= -f2- | head -1)
    fi

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
    local koin_version=$(get_version "koin")
    local koin_compiler_version=$(get_version "koin-compiler")
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

    # Record the effective Gradle daemon JVM args reported by Gradle Profiler.
    local daemon_jvm_args=""
    local daemon_jvm_args_source="benchmark/gradle.properties"
    local representative_profile_log
    representative_profile_log=$(find "$output_dir" -name profile.log -type f -print | sort | head -1)
    if [ -n "$representative_profile_log" ]; then
        daemon_jvm_args=$(
            awk '
                /^JVM args:$/ {
                    collecting = 1
                    next
                }
                /^Gradle args:$/ {
                    exit
                }
                collecting && /^  / {
                    sub(/^  /, "")
                    print
                }
            ' "$representative_profile_log" | paste -sd ' ' -
        )
        daemon_jvm_args_source="Gradle Profiler profile.log"
    elif [ -f "$SCRIPT_DIR/gradle.properties" ]; then
        daemon_jvm_args=$(grep "^org.gradle.jvmargs=" "$SCRIPT_DIR/gradle.properties" 2>/dev/null | cut -d= -f2- || echo "")
    fi
    local daemon_jvm_args_json
    daemon_jvm_args_json=$(json_quote "$daemon_jvm_args")
    local daemon_jvm_args_source_json
    daemon_jvm_args_source_json=$(json_quote "$daemon_jvm_args_source")
    local profiler_gradle_user_home_json
    profiler_gradle_user_home_json=$(json_quote "$PROFILER_GRADLE_USER_HOME")

    # Write JSON
    cat > "$metadata_file" << EOF
{
  "git": {
    "branch": "$git_branch",
    "sha": "$git_sha",
    "shaShort": "$git_sha_short",
    "dirtyDiffFingerprint": "$dirty_diff_fingerprint"
  },
  "versions": {
    "metro": "$metro_version",
    "kotlin": "$kotlin_version",
    "dagger": "$dagger_version",
    "ksp": "$ksp_version",
    "kotlinInject": "$kotlin_inject_version",
    "anvil": "$anvil_version",
    "kotlinInjectAnvil": "$kotlin_inject_anvil_version",
    "koin": "$koin_version",
    "koinCompiler": "$koin_compiler_version"
  },
  "build": {
    "gradle": "$gradle_version",
    "gradleProfiler": "$profiler_version",
    "jdk": "$java_version",
    "jvmTarget": "$jvm_target",
    "kotlinCompilerExecutionStrategy": "in-process"
  },
  "workload": {
    "seed": $WORKLOAD_SEED,
    "fingerprint": "$WORKLOAD_FINGERPRINT",
    "moduleCount": $WORKLOAD_MODULE_COUNT,
    "modulesByLayer": {
      "core": $WORKLOAD_CORE_MODULE_COUNT,
      "features": $WORKLOAD_FEATURE_MODULE_COUNT,
      "app": $WORKLOAD_APP_MODULE_COUNT
    },
    "dependencyEdgeCount": $WORKLOAD_DEPENDENCY_EDGE_COUNT,
    "contributionCount": $WORKLOAD_CONTRIBUTION_COUNT,
    "contributionsByKind": {
      "binding": $WORKLOAD_BINDING_COUNT,
      "plugin": $WORKLOAD_PLUGIN_COUNT,
      "initializer": $WORKLOAD_INITIALIZER_COUNT
    },
    "subcomponents": {
      "l1": $WORKLOAD_L1_SUBCOMPONENT_COUNT,
      "l2PerL1": $WORKLOAD_L2_PER_L1,
      "l3PerL2": $WORKLOAD_L3_PER_L2,
      "total": $WORKLOAD_SUBCOMPONENT_COUNT
    }
  },
  "daggerOptions": {
    "mapMultibindingDuplicateDetectionFix": "ENABLED (explicit)",
    "useBindingGraphFix": "ENABLED default",
    "ignoreProvisionKeyWildcards": "ENABLED default",
    "validateTransitiveComponentDependencies": "ENABLED default",
    "strictSuperficialValidation": "ENABLED default",
    "fullBindingGraphValidation": "NONE default",
    "fastInit": "DISABLED",
    "providerMultibindings": false
  },
  "system": {
    "os": "$os_info",
    "cpu": "$cpu_info",
    "ram": "$ram_info",
    "daemonJvmArgs": $daemon_jvm_args_json,
    "daemonJvmArgsSource": $daemon_jvm_args_source_json,
    "gradleUserHome": $profiler_gradle_user_home_json,
    "minimumIdleCpuPercent": $BENCHMARK_MIN_IDLE_PERCENT,
    "idleSamplesRequired": $BENCHMARK_IDLE_SAMPLES,
    "cooldownSeconds": $BENCHMARK_COOLDOWN_SECONDS
  },
  "stability": {
    "incrementalWarmups": $BENCHMARK_INCREMENTAL_WARMUP_COUNT,
    "graphProcessingWarmups": $BENCHMARK_GRAPH_PROCESSING_WARMUP_COUNT,
    "measuredSamples": $BENCHMARK_MEASURED_SAMPLE_COUNT,
    "maxRelativeMadPercent": $BENCHMARK_MAX_RELATIVE_MAD_PERCENT,
    "maxHalfDriftPercent": $BENCHMARK_MAX_HALF_DRIFT_PERCENT,
    "outlierThresholdPercent": $BENCHMARK_OUTLIER_THRESHOLD_PERCENT,
    "maxOutlierCount": $BENCHMARK_MAX_OUTLIER_COUNT,
    "outliersDiscarded": false
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

    if ! command -v python3 &> /dev/null; then
        missing_tools+=("python3")
    fi

    if [ ! -f "$BENCHMARK_STABILITY_CHECKER" ]; then
        missing_tools+=("check-benchmark-stability.py")
    fi

    if ! command -v sha256sum &> /dev/null && ! command -v shasum &> /dev/null; then
        missing_tools+=("sha256sum or shasum")
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
        kotlin generate-projects.main.kts --mode "DAGGER" --processor "$(echo "$processor" | tr '[:lower:]' '[:upper:]')" --count "$count" --seed "$WORKLOAD_SEED"
    elif [ "$mode" = "kotlin-inject-anvil" ]; then
        kotlin generate-projects.main.kts --mode "KOTLIN_INJECT_ANVIL" --count "$count" --seed "$WORKLOAD_SEED"
    elif [ "$mode" = "koin" ]; then
        kotlin generate-projects.main.kts --mode "KOIN" --count "$count" --seed "$WORKLOAD_SEED"
    elif [ "$mode" = "control" ]; then
        kotlin generate-projects.main.kts --mode "CONTROL" --count "$count" --seed "$WORKLOAD_SEED"
    elif [ "$mode" = "metro-noop" ]; then
        kotlin generate-projects.main.kts --mode "METRO_NOOP" --count "$count" --seed "$WORKLOAD_SEED"
    else
        kotlin generate-projects.main.kts --mode "$(echo "$mode" | tr '[:lower:]' '[:upper:]')" --count "$count" --seed "$WORKLOAD_SEED"
    fi
    
    if [ $? -eq 0 ]; then
        print_success "Project generation completed for $mode mode"
    else
        print_error "Project generation failed for $mode mode"
        exit 1
    fi
}

expected_warmup_count_for_scenario() {
    case "$1" in
        raw_compilation|raw_compilation_ksp|raw_compilation_java)
            echo "$BENCHMARK_GRAPH_PROCESSING_WARMUP_COUNT"
            ;;
        clean_build)
            echo "$BENCHMARK_CLEAN_WARMUP_COUNT"
            ;;
        *)
            echo "$BENCHMARK_INCREMENTAL_WARMUP_COUNT"
            ;;
    esac
}

validate_benchmark_csv() {
    local csv_file="$1"
    local mode="$2"
    local scenario="$3"
    local expected_warmups
    expected_warmups=$(expected_warmup_count_for_scenario "$scenario")

    if [ ! -f "$csv_file" ]; then
        print_error "Missing benchmark result for $mode/$scenario: $csv_file" >&2
        return 1
    fi

    if ! awk -F, -v expected_warmups="$expected_warmups" -v expected_measured="$BENCHMARK_MEASURED_SAMPLE_COUNT" '
        /^warm-up build/ {
            warmups++
            if ($1 != "warm-up build #" warmups) {
                invalid = 1
            }
        }
        /^measured build/ {
            measured++
            if ($1 != "measured build #" measured ||
                $2 !~ /^[0-9]+([.][0-9]+)?$/ ||
                $3 !~ /^[0-9]+([.][0-9]+)?$/) {
                invalid = 1
            }
        }
        END {
            exit !(warmups == expected_warmups && measured == expected_measured && !invalid)
        }
    ' "$csv_file"; then
        print_error "Benchmark result for $mode/$scenario has unexpected warm-up or measured build rows: $csv_file" >&2
        return 1
    fi
}

validate_benchmark_stability() {
    local csv_file="$1"
    local mode="$2"
    local scenario="$3"
    local stability_file
    stability_file="$(dirname "$csv_file")/stability.json"

    if ! python3 "$BENCHMARK_STABILITY_CHECKER" \
        "$csv_file" \
        --output "$stability_file" \
        --expected-samples "$BENCHMARK_MEASURED_SAMPLE_COUNT" \
        --max-relative-mad-percent "$BENCHMARK_MAX_RELATIVE_MAD_PERCENT" \
        --max-half-drift-percent "$BENCHMARK_MAX_HALF_DRIFT_PERCENT" \
        --outlier-threshold-percent "$BENCHMARK_OUTLIER_THRESHOLD_PERCENT" \
        --max-outlier-count "$BENCHMARK_MAX_OUTLIER_COUNT" \
        > /dev/null; then
        print_error "Unstable benchmark result for $mode/$scenario" >&2
        if [ -f "$stability_file" ]; then
            sed 's/^/  /' "$stability_file" >&2
        fi
        return 1
    fi
}

validate_publishable_benchmark_csv() {
    validate_benchmark_csv "$@" && validate_benchmark_stability "$@"
}

validate_profiler_log() {
    local profile_log="$1"
    local mode="$2"
    local scenario="$3"
    local expected_warmups
    local expected_executions
    local execution_count
    expected_warmups=$(expected_warmup_count_for_scenario "$scenario")
    expected_executions=$((expected_warmups + BENCHMARK_MEASURED_SAMPLE_COUNT))

    if [ ! -f "$profile_log" ]; then
        print_error "Missing profiler log for $mode/$scenario: $profile_log" >&2
        return 1
    fi

    if ! grep -Fq "Warm-ups: $expected_warmups" "$profile_log" ||
        ! grep -Fq "Builds: $BENCHMARK_MEASURED_SAMPLE_COUNT" "$profile_log"; then
        print_error "Profiler log for $mode/$scenario does not record the expected iteration counts" >&2
        return 1
    fi

    execution_count=$(grep -c '^Execution time ' "$profile_log" || true)
    if [ "$execution_count" -ne "$expected_executions" ]; then
        print_error "Profiler log for $mode/$scenario contains $execution_count executions; expected $expected_executions" >&2
        return 1
    fi

    if grep -q "Publishing Build Scan" "$profile_log"; then
        print_error "Develocity published a build scan during $mode/$scenario" >&2
        return 1
    fi

    if [ "$mode" = "koin" ]; then
        local strict_safety_projects
        local unexpected_strict_safety_projects
        strict_safety_projects=$(
            sed -n 's/.*Auto-enabling strictSafety on \(:[^ ]*\).*/\1/p' "$profile_log" \
                | sort -u \
                || true
        )
        if ! grep -qx ':app:component' <<< "$strict_safety_projects"; then
            print_error "Koin strictSafety was not auto-enabled on :app:component during $scenario" >&2
            return 1
        fi
        unexpected_strict_safety_projects=$(
            grep -v '^:app:component$' <<< "$strict_safety_projects" \
                || true
        )
        if [ -n "$unexpected_strict_safety_projects" ]; then
            print_error "Koin strictSafety was auto-enabled outside :app:component during $scenario:" >&2
            echo "$unexpected_strict_safety_projects" >&2
            return 1
        fi
    fi
}

wait_for_benchmark_host_idle() {
    if [ "$(uname -s)" != "Darwin" ]; then
        return 0
    fi

    local idle_samples=0
    local elapsed_seconds=0
    print_status "Waiting for ${BENCHMARK_MIN_IDLE_PERCENT}% host CPU idle before measurement"

    while [ "$elapsed_seconds" -lt "$BENCHMARK_IDLE_TIMEOUT_SECONDS" ]; do
        local idle_percent
        idle_percent=$(
            top -l 2 -n 0 -s 1 \
                | awk '/CPU usage/ { idle=$7 } END { gsub("%", "", idle); print idle }'
        )

        if awk -v idle="$idle_percent" -v minimum="$BENCHMARK_MIN_IDLE_PERCENT" 'BEGIN { exit !(idle >= minimum) }'; then
            idle_samples=$((idle_samples + 1))
        else
            idle_samples=0
        fi

        print_status "Host CPU idle: ${idle_percent}% (${idle_samples}/${BENCHMARK_IDLE_SAMPLES} stable samples)"
        if [ "$idle_samples" -ge "$BENCHMARK_IDLE_SAMPLES" ]; then
            return 0
        fi

        sleep 10
        elapsed_seconds=$((elapsed_seconds + 11))
    done

    print_error "Host did not remain ${BENCHMARK_MIN_IDLE_PERCENT}% idle for ${BENCHMARK_IDLE_SAMPLES} samples" >&2
    return 1
}

# Function to run benchmark scenarios for a specific mode
run_scenarios() {
    local mode=$1
    local processor=${2:-""}
    local include_clean_builds=${3:-false}

    # Determine mode name for output directory
    local mode_name
    if [ "$mode" = "metro" ]; then
        mode_name="metro"
    elif [ "$mode" = "control" ]; then
        mode_name="control"
    elif [ "$mode" = "metro-noop" ]; then
        mode_name="metro_noop"
    elif [ "$mode" = "dagger" ] && [ "$processor" = "ksp" ]; then
        mode_name="dagger_ksp"
    elif [ "$mode" = "dagger" ] && [ "$processor" = "kapt" ]; then
        mode_name="dagger_kapt"
    elif [ "$mode" = "kotlin-inject-anvil" ]; then
        mode_name="kotlin_inject_anvil"
    elif [ "$mode" = "koin" ]; then
        mode_name="koin"
    else
        print_error "Invalid mode/processor combination: $mode/$processor"
        exit 1
    fi

    # Build scenario list based on mode
    local scenarios=()

    # Determine the raw compilation variant for this mode
    local raw_compilation_variant
    case "$mode_name" in
        metro|control|metro_noop|koin)
            # Metro/Koin use pure K2 compiler plugins and Control uses no compiler plugin.
            # None of these modes need annotation-processing tasks for raw compilation.
            raw_compilation_variant="raw_compilation"
            ;;
        kotlin_inject_anvil)
            raw_compilation_variant="raw_compilation_ksp"
            ;;
        dagger_ksp|dagger_kapt)
            raw_compilation_variant="raw_compilation_java"
            ;;
    esac

    # If SCENARIOS_FILTER is set, only run those scenarios
    if [ -n "$SCENARIOS_FILTER" ]; then
        IFS=',' read -ra REQUESTED_SCENARIOS <<< "$SCENARIOS_FILTER"
        for scenario in "${REQUESTED_SCENARIOS[@]}"; do
            # Map "raw_compilation" to the mode-appropriate variant
            if [ "$scenario" = "raw_compilation" ]; then
                scenarios+=("$raw_compilation_variant")
            else
                scenarios+=("$scenario")
            fi
        done
    else
        # Default: all scenarios
        # All modes use the same ABI change scenario - only the underlying project differs
        scenarios=("abi_change")

        # Common scenarios for all modes
        scenarios+=(
            "non_abi_change"
            "plain_abi_change"
            "plain_non_abi_change"
        )

        # Add the appropriate raw compilation scenario
        scenarios+=("$raw_compilation_variant")

        # Add clean build scenario if requested
        if [ "$include_clean_builds" = true ]; then
            scenarios+=("clean_build")
        fi
    fi

    # Create mode-specific results directory to avoid overwrites
    local mode_results_dir="$RESULTS_DIR/${mode_name}_${TIMESTAMP}"
    mkdir -p "$mode_results_dir"
    mkdir -p "$PROFILER_GRADLE_USER_HOME"

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

        # Build profile arguments (use ${arr[@]+"${arr[@]}"} to handle empty array with set -u)
        local profile_args=()
        if [ ${#PROFILE_OPTIONS[@]} -gt 0 ]; then
            for profile_type in "${PROFILE_OPTIONS[@]}"; do
                profile_args+=("--profile" "$profile_type")
            done
        fi

        if ! wait_for_benchmark_host_idle; then
            return 1
        fi

        "$profiler_cmd" \
            --benchmark \
            --measure-gc \
            --scenario-file benchmark.scenarios \
            --output-dir "$scenario_output_dir" \
            --gradle-user-home "$PROFILER_GRADLE_USER_HOME" \
            ${profile_args[@]+"${profile_args[@]}"} \
            "$scenario" \
            || {
                print_error "Benchmark failed for scenario $scenario in $mode mode"
                return 1
            }

        if ! validate_publishable_benchmark_csv "$scenario_output_dir/benchmark.csv" "$mode_name" "$scenario"; then
            return 1
        fi
        if ! validate_profiler_log "$scenario_output_dir/profile.log" "$mode_name" "$scenario"; then
            return 1
        fi

        print_success "Completed scenario: $scenario"
    done

    print_success "All scenarios completed for $mode mode"
}

# Function to merge benchmark results
merge_benchmark_results() {
    local timestamp=$1
    local include_clean_builds=${2:-false}

    print_header "Merging Benchmark Results"

    # Define test types (scenario names without mode prefix)
    # Note: raw_compilation and raw_compilation_java are both treated as "raw_compilation" for merging
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
            # Handle both raw_compilation and raw_compilation_java variants
            if [ -d "$mode_dir" ]; then
                for scenario_dir in "$mode_dir"/${test_type}* "$mode_dir"/${test_type}; do
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

# Function to show usage information
show_usage() {
    echo "Metro vs Anvil Benchmark Runner"
    echo ""
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  all                           Run all benchmark modes on current branch"
    echo "  metro [COUNT]                 Run only Metro mode on current branch"
    echo "  metro-noop [COUNT]            Run only Metro-NOOP mode (Metro plugin, no annotations)"
    echo "  dagger-ksp [COUNT]            Run only Dagger (KSP) mode"
    echo "  dagger-kapt [COUNT]           Run only Dagger (KAPT) mode"
    echo "  kotlin-inject-anvil [COUNT]   Run only Kotlin-inject + Anvil mode"
    echo "  koin [COUNT]                  Run only Koin mode (koin-annotations + compiler plugin)"
    echo "  single                        Run benchmarks on a git ref, Metro version, or HEAD (current branch)"
    echo "  compare                       Compare benchmarks across two refs (git refs or Metro versions)"
    echo "  help                          Show this help message"
    echo ""
    echo "Options:"
    echo "  COUNT                        Number of modules to generate (default: $DEFAULT_MODULE_COUNT)"
    echo "  --seed <int>                 Seed for deterministic workload generation (default: $DEFAULT_SEED)"
    echo "  --build-only                 Only run ./gradlew :app:component:run --quiet, skip gradle-profiler"
    echo "  --include-clean-builds       Include clean build scenarios in benchmarks"
    echo "  --include-baselines          Also include the optional Metro-NOOP plugin-overhead baseline"
    echo ""
    echo "Single/Compare Options:"
    echo "  --ref <ref>                  Git ref (branch name/commit) or Metro version (e.g., 1.0.0)"
    echo "  --modes <list>               Comma-separated list of modes to benchmark, or 'all'"
    echo "                               Available: metro, metro-noop, dagger-ksp, dagger-kapt, kotlin-inject-anvil, koin, all"
    echo "                               Default: metro,dagger-ksp,dagger-kapt,kotlin-inject-anvil,koin"
    echo "                               Use 'all' to run all five published modes"
    echo "  --scenarios <list>           Comma-separated list of scenarios to run"
    echo "                               Available: abi_change, non_abi_change, plain_abi_change, plain_non_abi_change, raw_compilation, clean_build"
    echo "                               Default: all scenarios (except clean_build unless --include-clean-builds)"
    echo "                               Use 'raw_compilation' to auto-select the right variant for each mode"
    echo ""
    echo "Compare-specific Options:"
    echo "  --ref1 <ref>                 First ref (baseline) - git ref or Metro version"
    echo "  --ref2 <ref>                 Second ref to compare - git ref or Metro version"
    echo "  --rerun-non-metro            Re-run non-metro modes on ref2 (default: only run metro on ref2)"
    echo "                               When disabled (default), ref2 uses ref1's non-metro results for comparison"
    echo ""
    echo "Profiling Options:"
    echo "  --profile <type>             Add a profiling option (can be specified multiple times)"
    echo "                               Available types: jfr, async-profiler-heap, async-profiler-all,"
    echo "                               yourkit-heap, heap-dump"
    echo "                               Note: GC time is always measured via --measure-gc"
    echo ""
    echo "Ref Types:"
    echo "  Refs can be either git refs or Metro versions. The script automatically detects"
    echo "  the type based on the format:"
    echo "  - Git refs: branch names (main, feature-branch), commit hashes (abc123), tags"
    echo "  - Metro versions: semantic versions like 1.0.0, 2.0.0-alpha01, 1.5.0-RC1"
    echo ""
    echo "  When using a Metro version, benchmarks run on the current branch with the"
    echo "  specified Metro version from Maven Central (instead of the included build)."
    echo ""
    echo "Prerequisites:"
    echo "  Run benchmark/install-gradle-profiler.sh to install gradle-profiler from source"
    echo "  Or pass --install-gradle-profiler to install before running benchmarks"
    echo ""
    echo "Examples:"
    echo "  $0                           # Run all benchmarks with default settings"
    echo "  $0 all 1000                  # Run all benchmarks with 1000 modules"
    echo "  $0 all --include-baselines   # Also run the Metro-NOOP plugin-overhead baseline"
    echo "  $0 all --seed 0              # Reproduce the published workload"
    echo "  $0 metro 250                 # Run only Metro benchmarks with 250 modules"
    echo "  $0 dagger-ksp                # Run only Dagger (KSP) benchmarks with default count"
    echo "  $0 all --include-clean-builds # Run all benchmarks including clean build scenarios"
    echo ""
    echo "  # Using single command explicitly:"
    echo "  $0 single --ref main --modes metro,dagger-ksp"
    echo "  $0 single --ref main --modes all --include-baselines"
    echo "  $0 single --ref feature-branch --modes metro"
    echo ""
    echo "  # Run benchmarks with a specific Metro version:"
    echo "  $0 single --ref 1.0.0 --modes metro   # Benchmark Metro 1.0.0"
    echo "  $0 single --ref 2.0.0-alpha01         # Benchmark a pre-release version"
    echo ""
    echo "  # Compare benchmarks across git refs:"
    echo "  $0 compare --ref1 main --ref2 feature-branch"
    echo "  $0 compare --ref1 main --ref2 feature-branch --modes all"
    echo "  $0 compare --ref1 main --ref2 feature --rerun-non-metro"
    echo ""
    echo "  # Compare Metro versions:"
    echo "  $0 compare --ref1 1.0.0 --ref2 1.1.0  # Compare two released versions"
    echo "  $0 compare --ref1 1.0.0 --ref2 main   # Compare release to git branch"
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

validate_seed() {
    local seed="$1"
    if ! [[ "$seed" =~ ^-?[0-9]+$ ]] || [ "$seed" -lt -2147483648 ] || [ "$seed" -gt 2147483647 ]; then
        print_error "Invalid workload seed: $seed"
        print_error "Seed must be a 32-bit signed integer"
        exit 1
    fi
}

# expand_modes: Expands "all" mode to the published modes and optional Metro-NOOP baseline.
expand_modes() {
    local modes="$1"
    if [ "$modes" = "all" ]; then
        if [ "$INCLUDE_BASELINES" = true ]; then
            echo "$ALL_MODES_WITH_BASELINES"
        else
            echo "$STANDARD_MODES"
        fi
    else
        # If requested, add Metro-NOOP to an explicit mode list.
        if [ "$INCLUDE_BASELINES" = true ]; then
            if [[ ",$modes," != *",metro-noop,"* ]]; then
                echo "${modes},${BASELINE_MODES}"
            else
                echo "$modes"
            fi
        else
            echo "$modes"
        fi
    fi
}

mode_to_prefix() {
    case "$1" in
        control) echo "control" ;;
        metro) echo "metro" ;;
        metro-noop) echo "metro_noop" ;;
        dagger-ksp) echo "dagger_ksp" ;;
        dagger-kapt) echo "dagger_kapt" ;;
        kotlin-inject-anvil) echo "kotlin_inject_anvil" ;;
        koin) echo "koin" ;;
        *)
            print_error "Unknown benchmark mode: $1" >&2
            return 1
            ;;
    esac
}

mode_display_name() {
    case "$1" in
        control) echo "Control" ;;
        metro) echo "Metro" ;;
        metro-noop) echo "Metro-NOOP" ;;
        dagger-ksp) echo "Dagger (KSP)" ;;
        dagger-kapt) echo "Dagger (KAPT)" ;;
        kotlin-inject-anvil) echo "kotlin-inject" ;;
        koin) echo "Koin" ;;
        *)
            print_error "Unknown benchmark mode: $1" >&2
            return 1
            ;;
    esac
}

select_report_baseline_mode() {
    local ref_label="$1"
    local modes="$2"
    local -a selected_modes
    IFS=',' read -ra selected_modes <<< "$modes"

    local preferred_mode
    for preferred_mode in metro; do
        local selected_mode
        for selected_mode in "${selected_modes[@]}"; do
            if [ "$selected_mode" != "$preferred_mode" ]; then
                continue
            fi

            local preferred_prefix
            if ! preferred_prefix=$(mode_to_prefix "$preferred_mode"); then
                return 1
            fi
            if mode_was_run_for_ref "$ref_label" "$preferred_prefix"; then
                echo "$preferred_mode"
                return
            fi
        done
    done

    local selected_mode
    for selected_mode in "${selected_modes[@]}"; do
        local selected_prefix
        if ! selected_prefix=$(mode_to_prefix "$selected_mode"); then
            return 1
        fi
        if mode_was_run_for_ref "$ref_label" "$selected_prefix"; then
            echo "$selected_mode"
            return
        fi
    done

    print_error "No benchmark mode is available to use as the report baseline for $ref_label" >&2
    return 1
}

scenario_name_for_mode() {
    local mode_prefix="$1"
    local test_type="$2"

    if [ "$test_type" != "raw_compilation" ]; then
        echo "$test_type"
        return
    fi

    case "$mode_prefix" in
        control|metro|metro_noop|koin) echo "raw_compilation" ;;
        kotlin_inject_anvil) echo "raw_compilation_ksp" ;;
        dagger_ksp|dagger_kapt) echo "raw_compilation_java" ;;
        *)
            print_error "Unknown benchmark result mode: $mode_prefix" >&2
            return 1
            ;;
    esac
}

REPORT_TEST_TYPES=()
REPORT_TEST_NAMES=()

configure_report_scenarios() {
    REPORT_TEST_TYPES=()
    REPORT_TEST_NAMES=()

    local requested_scenarios
    if [ -n "$SCENARIOS_FILTER" ]; then
        requested_scenarios="$SCENARIOS_FILTER"
    else
        requested_scenarios="abi_change,non_abi_change,plain_abi_change,plain_non_abi_change,raw_compilation"
        if [ "$INCLUDE_CLEAN_BUILDS" = true ]; then
            requested_scenarios="${requested_scenarios},clean_build"
        fi
    fi

    local requested
    IFS=',' read -ra requested <<< "$requested_scenarios"
    local scenario
    for scenario in "${requested[@]}"; do
        case "$scenario" in
            abi_change)
                REPORT_TEST_TYPES+=("abi_change")
                REPORT_TEST_NAMES+=("ABI Change")
                ;;
            non_abi_change)
                REPORT_TEST_TYPES+=("non_abi_change")
                REPORT_TEST_NAMES+=("Non-ABI Change")
                ;;
            plain_abi_change)
                REPORT_TEST_TYPES+=("plain_abi_change")
                REPORT_TEST_NAMES+=("Plain Kotlin ABI")
                ;;
            plain_non_abi_change)
                REPORT_TEST_TYPES+=("plain_non_abi_change")
                REPORT_TEST_NAMES+=("Plain Kotlin Non-ABI")
                ;;
            raw_compilation)
                REPORT_TEST_TYPES+=("raw_compilation")
                REPORT_TEST_NAMES+=("Graph Processing")
                ;;
            clean_build)
                REPORT_TEST_TYPES+=("clean_build")
                REPORT_TEST_NAMES+=("Clean Build")
                ;;
            *)
                print_error "Unknown report scenario: $scenario" >&2
                return 1
                ;;
        esac
    done
}

move_mode_results() {
    local mode_prefix="$1"
    local ref_dir="$2"
    local source_dir="$RESULTS_DIR/${mode_prefix}_${TIMESTAMP}"
    local destination_dir="$ref_dir/${mode_prefix}_${TIMESTAMP}"

    if [ ! -d "$source_dir" ]; then
        print_error "Missing result directory for $mode_prefix: $source_dir" >&2
        return 1
    fi
    if [ -e "$destination_dir" ]; then
        print_error "Result directory already exists: $destination_dir" >&2
        return 1
    fi
    if ! mv "$source_dir" "$ref_dir/"; then
        print_error "Failed to move $source_dir to $ref_dir" >&2
        return 1
    fi
}

# Run benchmarks for a specific git ref or Metro version
# Arguments: ref, ref_label, count, include_clean_builds, modes, is_second_ref, build_only
run_benchmarks_for_ref() {
    local ref="$1"
    local ref_label="$2"
    local count="$3"
    local include_clean_builds="$4"
    local modes="$5"
    local is_second_ref="${6:-false}"
    local build_only="${7:-false}"

    if [ "$build_only" = true ]; then
        print_header "Building benchmark for: $ref_label"
    else
        print_header "Running benchmarks for: $ref_label"
    fi

    # Check if ref is a Metro version, HEAD/current, or git ref
    if is_metro_version "$ref"; then
        print_status "Using Metro version: $ref (staying on current branch)"
        export METRO_VERSION="$ref"
    elif [[ "$ref" =~ ^(HEAD|head|current)$ ]]; then
        # HEAD/current means stay on current branch, no checkout needed
        print_status "Using current branch (no checkout)"
        unset METRO_VERSION
    else
        # It's a git ref - checkout
        checkout_ref "$ref" || return 1
        # Unset METRO_VERSION to use included build
        unset METRO_VERSION
    fi

    # Create ref-specific results directory
    local ref_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}"
    mkdir -p "$ref_dir"

    local dirty_diff_fingerprint
    if ! dirty_diff_fingerprint=$(benchmark_repo_state_fingerprint); then
        return 1
    fi
    echo "$dirty_diff_fingerprint" > "$ref_dir/repo-dirty-diff-fingerprint.txt"

    # Save version/commit info for reference
    if is_metro_version "$ref"; then
        echo "Metro version: $ref" > "$ref_dir/version-info.txt"
        git rev-parse HEAD > "$ref_dir/commit.txt"
        git log -1 --format='%h %s (Metro $ref)' | sed "s/\$ref/$ref/" > "$ref_dir/commit-info.txt"
    else
        git rev-parse HEAD > "$ref_dir/commit.txt"
        git log -1 --format='%h %s' > "$ref_dir/commit-info.txt"
    fi

    # Run benchmarks for each mode
    IFS=',' read -ra MODE_ARRAY <<< "$modes"
    for mode in "${MODE_ARRAY[@]}"; do
        # Skip non-metro modes on second ref unless RERUN_NON_METRO is true
        if [ "$is_second_ref" = true ] && [ "$mode" != "metro" ] && [ "$RERUN_NON_METRO" != true ]; then
            print_status "Skipping $mode for $ref_label (using ref1 results for comparison)"
            continue
        fi

        print_header "Benchmarking $mode for $ref_label"

        local generator_mode
        local processor=""
        local mode_prefix
        case "$mode" in
            control)
                generator_mode="control"
                mode_prefix="control"
                ;;
            metro)
                generator_mode="metro"
                mode_prefix="metro"
                ;;
            metro-noop)
                generator_mode="metro-noop"
                mode_prefix="metro_noop"
                ;;
            dagger-ksp)
                generator_mode="dagger"
                processor="ksp"
                mode_prefix="dagger_ksp"
                ;;
            dagger-kapt)
                generator_mode="dagger"
                processor="kapt"
                mode_prefix="dagger_kapt"
                ;;
            kotlin-inject-anvil)
                generator_mode="kotlin-inject-anvil"
                mode_prefix="kotlin_inject_anvil"
                ;;
            koin)
                generator_mode="koin"
                mode_prefix="koin"
                ;;
            *)
                print_error "Unknown mode: $mode" >&2
                return 1
                ;;
        esac

        generate_projects "$generator_mode" "$processor" "$count"
        verify_and_capture_workload_manifest "$mode" "$ref_dir" "$count"
        if [ "$build_only" = true ]; then
            print_status "Build-only mode: running ./gradlew :app:component:run --quiet"
            if ! ./gradlew :app:component:run --quiet; then
                print_error "Build failed for $mode"
                return 1
            fi
            print_success "Completed build for $mode"
        else
            run_scenarios "$generator_mode" "$processor" "$include_clean_builds"
            move_mode_results "$mode_prefix" "$ref_dir"
        fi
    done

    if [ "$build_only" = true ]; then
        print_success "Completed builds for $ref_label"
    else
        collect_build_metadata "$ref_dir"
        print_success "Completed benchmarks for $ref_label"
    fi
}

# Extract median time from benchmark CSV for a specific test type
extract_median_for_ref() {
    local ref_label="$1"
    local mode_prefix="$2"
    local test_type="$3"

    local scenario_name
    if ! scenario_name=$(scenario_name_for_mode "$mode_prefix" "$test_type"); then
        return 1
    fi

    local csv_file="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/${mode_prefix}_${TIMESTAMP}/${scenario_name}/benchmark.csv"
    if ! validate_publishable_benchmark_csv "$csv_file" "$mode_prefix" "$scenario_name"; then
        return 1
    fi

    local times
    times=$(awk -F, '/^measured build/ {print $2}' "$csv_file" | sort -n)
    local times_array=($times)
    local count=${#times_array[@]}
    local median_index=$((count / 2))

    if [ $((count % 2)) -eq 1 ]; then
        echo "${times_array[$median_index]}"
    else
        local mid1_index=$((median_index - 1))
        local mid1=${times_array[$mid1_index]}
        local mid2=${times_array[$median_index]}
        local median
        if ! median=$(echo "scale=2; ($mid1 + $mid2) / 2" | bc 2>/dev/null); then
            print_error "Failed to calculate median for $mode_prefix/$scenario_name" >&2
            return 1
        fi
        echo "$median"
    fi
}

# Extract median GC time from benchmark CSV for a specific test type
# GC time is in column 3 when --measure-gc is enabled
extract_gc_for_ref() {
    local ref_label="$1"
    local mode_prefix="$2"
    local test_type="$3"

    local scenario_name
    if ! scenario_name=$(scenario_name_for_mode "$mode_prefix" "$test_type"); then
        return 1
    fi

    local csv_file="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/${mode_prefix}_${TIMESTAMP}/${scenario_name}/benchmark.csv"
    if ! validate_publishable_benchmark_csv "$csv_file" "$mode_prefix" "$scenario_name"; then
        return 1
    fi

    local times
    times=$(awk -F, '/^measured build/ {print $3}' "$csv_file" | sort -n)
    local times_array=($times)
    local count=${#times_array[@]}
    local median_index=$((count / 2))

    if [ $((count % 2)) -eq 1 ]; then
        echo "${times_array[$median_index]}"
    else
        local mid1_index=$((median_index - 1))
        local mid1=${times_array[$mid1_index]}
        local mid2=${times_array[$median_index]}
        local median
        if ! median=$(echo "scale=2; ($mid1 + $mid2) / 2" | bc 2>/dev/null); then
            print_error "Failed to calculate median GC time for $mode_prefix/$scenario_name" >&2
            return 1
        fi
        echo "$median"
    fi
}

extract_stability_for_ref() {
    local ref_label="$1"
    local mode_prefix="$2"
    local test_type="$3"

    local scenario_name
    if ! scenario_name=$(scenario_name_for_mode "$mode_prefix" "$test_type"); then
        return 1
    fi

    local scenario_dir="$RESULTS_DIR/${TIMESTAMP}/${ref_label}/${mode_prefix}_${TIMESTAMP}/${scenario_name}"
    local csv_file="$scenario_dir/benchmark.csv"
    local stability_file="$scenario_dir/stability.json"
    if ! validate_publishable_benchmark_csv "$csv_file" "$mode_prefix" "$scenario_name"; then
        return 1
    fi

    python3 - "$stability_file" << 'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as input_file:
    stability = json.load(input_file)

if stability.get("status") != "pass":
    raise SystemExit("Benchmark stability data does not have pass status")

json.dump(stability, sys.stdout, separators=(",", ":"))
PY
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

validate_report_results() {
    local ref_label="$1"
    local modes="$2"
    local allow_skipped_non_metro="${3:-false}"

    if ! configure_report_scenarios; then
        return 1
    fi

    local selected_modes
    IFS=',' read -ra selected_modes <<< "$modes"
    local mode
    for mode in "${selected_modes[@]}"; do
        local mode_prefix
        if ! mode_prefix=$(mode_to_prefix "$mode"); then
            return 1
        fi

        if ! mode_was_run_for_ref "$ref_label" "$mode_prefix"; then
            if [ "$allow_skipped_non_metro" = true ] && [ "$mode" != "metro" ]; then
                continue
            fi
            print_error "Missing selected mode results for $ref_label/$mode" >&2
            return 1
        fi

        local test_type
        for test_type in "${REPORT_TEST_TYPES[@]}"; do
            local score
            local gc_time
            if ! score=$(extract_median_for_ref "$ref_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if ! gc_time=$(extract_gc_for_ref "$ref_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if [ -z "$score" ] || [ -z "$gc_time" ]; then
                print_error "Missing selected result for $ref_label/$mode/$test_type" >&2
                return 1
            fi
        done
    done
}

# Generate comparison summary between two refs
generate_comparison_summary() {
    local ref1_label="$1"
    local ref2_label="$2"
    local modes="$3"

    local summary_file="$RESULTS_DIR/${TIMESTAMP}/comparison-summary.md"
    local ref1_commit
    local ref2_commit
    ref1_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    ref2_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Comparison Summary"

    if ! load_report_workload_metadata; then
        return 1
    fi
    if ! validate_report_results "$ref1_label" "$modes" false; then
        return 1
    fi
    if ! validate_report_results "$ref2_label" "$modes" true; then
        return 1
    fi

    local baseline_mode
    local baseline_prefix
    local baseline_name
    if ! baseline_mode=$(select_report_baseline_mode "$ref1_label" "$modes"); then
        return 1
    fi
    if ! baseline_prefix=$(mode_to_prefix "$baseline_mode"); then
        return 1
    fi
    if ! baseline_name=$(mode_display_name "$baseline_mode"); then
        return 1
    fi

    local -a mode_array
    IFS=',' read -ra mode_array <<< "$modes"

    # Determine which modes were actually run on ref2
    local ref2_modes=""
    local mode
    for mode in "${mode_array[@]}"; do
        local mode_prefix
        if ! mode_prefix=$(mode_to_prefix "$mode"); then
            return 1
        fi
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
**Module Count:** $WORKLOAD_MODULE_COUNT
**Workload Seed:** $WORKLOAD_SEED
**Workload Fingerprint:** \`$WORKLOAD_FINGERPRINT\`
**Modes benchmarked on ref1:** $modes
**Modes benchmarked on ref2:** ${ref2_modes:-none}

The Koin benchmarks deserve a couple notes because the work is not exactly like-for-like:

- Koin's compiler plugin does less work. It aggregates definitions, generates module and factory wiring, and checks for missing dependencies and cycles, but leaves final graph resolution to runtime. Metro, Dagger, and kotlin-inject resolve and validate graphs from their roots and generate static implementations at compile time.
- Koin's runtime does more work as a result. The graph work deferred during compilation happens during startup.

**ABI scenario note:** Gradle Profiler's generic ABI mutation appends an unrelated top-level function to a foundation file used by every module.

## Git Refs

| Ref | Commit |
|-----|--------|
| $ref1_label (baseline) | $ref1_commit |
| $ref2_label | $ref2_commit |

EOF

    local i
    for i in "${!REPORT_TEST_TYPES[@]}"; do
        local test_type="${REPORT_TEST_TYPES[$i]}"
        local test_name="${REPORT_TEST_NAMES[$i]}"

        local baseline_score1
        local baseline_score2=""
        if ! baseline_score1=$(extract_median_for_ref "$ref1_label" "$baseline_prefix" "$test_type"); then
            return 1
        fi
        if mode_was_run_for_ref "$ref2_label" "$baseline_prefix"; then
            if ! baseline_score2=$(extract_median_for_ref "$ref2_label" "$baseline_prefix" "$test_type"); then
                return 1
            fi
        fi

        cat >> "$summary_file" << EOF
## $test_name

| Framework | $ref1_label | vs $baseline_name | $ref2_label | vs $baseline_name | Difference |
|-----------|-------------|----------|-------------|----------|------------|
EOF

        for mode in "${mode_array[@]}"; do
            local mode_prefix
            local mode_name
            if ! mode_prefix=$(mode_to_prefix "$mode"); then
                return 1
            fi
            if ! mode_name=$(mode_display_name "$mode"); then
                return 1
            fi

            local score1
            local gc1
            if ! score1=$(extract_median_for_ref "$ref1_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if ! gc1=$(extract_gc_for_ref "$ref1_label" "$mode_prefix" "$test_type"); then
                return 1
            fi

            # Check if this mode was run on ref2
            local mode_ran_on_ref2=false
            if mode_was_run_for_ref "$ref2_label" "$mode_prefix"; then
                mode_ran_on_ref2=true
            fi

            local score2=""
            local gc2=""
            if [ "$mode_ran_on_ref2" = true ]; then
                if ! score2=$(extract_median_for_ref "$ref2_label" "$mode_prefix" "$test_type"); then
                    return 1
                fi
                if ! gc2=$(extract_gc_for_ref "$ref2_label" "$mode_prefix" "$test_type"); then
                    return 1
                fi
            fi

            local display1="N/A"
            local display2="N/A"
            local vs_baseline1="—"
            local vs_baseline2="—"
            local diff="-"

            if [ -n "$score1" ]; then
                local secs1
                secs1=$(echo "scale=1; $score1 / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs1" ]; then
                    display1="${secs1}s"
                    # Add GC time if available
                    if [ -n "$gc1" ]; then
                        local gc_secs1
                        gc_secs1=$(echo "scale=2; $gc1 / 1000" | bc 2>/dev/null || echo "")
                        if [ -n "$gc_secs1" ]; then
                            display1="${secs1}s (gc: ${gc_secs1}s)"
                        fi
                    fi
                fi
                if [ "$mode" = "$baseline_mode" ]; then
                    vs_baseline1="baseline"
                elif [ -n "$baseline_score1" ] && [ "$baseline_score1" != "0" ]; then
                    vs_baseline1=$(format_vs_baseline "$score1" "$baseline_score1")
                fi
            fi

            if [ "$mode_ran_on_ref2" = true ]; then
                if [ -n "$score2" ]; then
                    local secs2
                    secs2=$(echo "scale=1; $score2 / 1000" | bc 2>/dev/null || echo "")
                    if [ -n "$secs2" ]; then
                        display2="${secs2}s"
                        # Add GC time if available
                        if [ -n "$gc2" ]; then
                            local gc_secs2
                            gc_secs2=$(echo "scale=2; $gc2 / 1000" | bc 2>/dev/null || echo "")
                            if [ -n "$gc_secs2" ]; then
                                display2="${secs2}s (gc: ${gc_secs2}s)"
                            fi
                        fi
                    fi
                    if [ "$mode" = "$baseline_mode" ]; then
                        vs_baseline2="baseline"
                    elif [ -n "$baseline_score2" ] && [ "$baseline_score2" != "0" ]; then
                        vs_baseline2=$(format_vs_baseline "$score2" "$baseline_score2")
                    fi
                fi

                if [ -n "$score1" ] && [ -n "$score2" ] && [ "$score1" != "0" ]; then
                    local pct_diff
                    pct_diff=$(format_pct_diff "$score2" "$score1" 2)
                    if [ "$pct_diff" = "0%" ] || [ "$pct_diff" = "0.00%" ]; then
                        diff="+0.00% (no change)"
                    else
                        diff="$pct_diff"
                    fi
                fi
            else
                # Mode was not run on ref2 - show ref1 value as reference
                display2="(not run)"
                diff="n/a"
            fi

            echo "| $mode_name | $display1 | $vs_baseline1 | $display2 | $vs_baseline2 | $diff |" >> "$summary_file"
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
    if ! generate_html_report "$ref1_label" "$ref2_label" "$modes"; then
        return 1
    fi
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
    if ! json_data=$(build_benchmark_json "$ref1_label" "$ref2_label" "$modes"); then
        print_error "Could not generate the HTML report because selected benchmark results or metadata are missing" >&2
        return 1
    fi

    # Generate HTML
    cat > "$html_file" << 'HTMLHEAD'
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Metro Benchmark Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root { --baseline-color: #4CAF50; --metro-color: #4CAF50; --control-color: #607D8B; --metro-noop-color: #795548; --dagger-ksp-color: #2196F3; --dagger-kapt-color: #FF9800; --kotlin-inject-color: #9C27B0; --koin-color: #E91E63; }
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
        .ref-card .commit a, .metadata-group a { color: inherit; text-underline-offset: 0.15em; }
        .versions-section { background: white; border-radius: 8px; padding: 1.5rem; margin-bottom: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .versions-section h2 { margin: 0 0 1rem 0; font-size: 1.1rem; font-weight: 500; color: #666; border-bottom: 2px solid #eee; padding-bottom: 0.5rem; }
        .versions-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); border-top: 1px solid #e5e5e5; border-left: 1px solid #e5e5e5; }
        .version-item { display: flex; justify-content: space-between; gap: 1rem; min-width: 0; border-right: 1px solid #e5e5e5; border-bottom: 1px solid #e5e5e5; padding: 0.55rem 0.75rem; }
        .version-item .label { color: #666; }
        .version-item .value { flex: 0 0 auto; min-width: 0; font-family: 'SF Mono', Monaco, monospace; text-align: right; white-space: nowrap; }
        .versions-section p { margin: 1rem 0 0; color: #666; font-size: 0.85rem; }
        .versions-section a { color: inherit; text-underline-offset: 0.15em; }
        .benchmark-section { background: white; border-radius: 8px; padding: 1.5rem; margin-bottom: 2rem; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .benchmark-section h2 { margin: 0 0 0.25rem 0; font-size: 1.3rem; font-weight: 500; }
        .benchmark-section .chart-hint { font-size: 0.8rem; color: #888; margin-bottom: 1rem; padding-bottom: 0.5rem; border-bottom: 2px solid #eee; }
        .chart-container { position: relative; height: 300px; margin-bottom: 1.5rem; }
        table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
        th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #eee; }
        th { background: #f8f9fa; font-weight: 600; color: #555; font-size: 0.8rem; text-transform: uppercase; }
        td.numeric, th.numeric { text-align: right; font-family: 'SF Mono', Monaco, monospace; }
        td.framework { font-weight: 500; }
        .baseline-select { cursor: pointer; width: 30px; }
        .baseline-radio { display: inline-block; width: 16px; height: 16px; border: 2px solid #ccc; border-radius: 50%; }
        .baseline-radio.selected { border-color: var(--baseline-color); background: var(--baseline-color); }
        .baseline-row { background: #f3f6f7; }
        .vs-baseline { color: #888; font-size: 0.85em; }
        .vs-baseline.baseline { font-weight: 500; }
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
        .metadata-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1.5rem; }
        .metadata-group { min-width: 0; }
        .metadata-group h3 { margin: 0 0 0.75rem 0; font-size: 0.9rem; font-weight: 600; color: #555; text-transform: uppercase; }
        .metadata-group dl { margin: 0; display: grid; grid-template-columns: minmax(160px, 240px) minmax(0, 1fr); gap: 0.25rem 1rem; align-items: start; font-size: 0.85rem; }
        .metadata-group dt { min-width: 0; color: #888; overflow-wrap: anywhere; }
        .metadata-group dd { min-width: 0; margin: 0; font-family: 'SF Mono', Monaco, monospace; color: #333; overflow-wrap: anywhere; word-break: break-word; }
        .notes-section { background: #fffdf4; border: 1px solid #f0e2a2; border-radius: 8px; padding: 1.25rem 1.5rem; margin: 2rem 0; font-size: 0.9rem; line-height: 1.5; }
        .notes-section h2 { margin: 0 0 0.75rem 0; font-size: 1.1rem; font-weight: 500; color: #665c2c; }
        .notes-section ul { margin: 0; padding-left: 1.25rem; }
        .notes-section li + li { margin-top: 0.5rem; }
        .gc-time { color: #888; font-size: 0.85em; }
        .stability { display: block; color: #888; font-size: 0.78em; margin-top: 0.2rem; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Metro Build Benchmark Results</h1>
        <div class="subtitle" id="date"></div>
    </div>
    <div class="container">
        <div class="refs-info" id="refs-info"></div>
        <div class="versions-section" id="versions"></div>
        <div class="notes-section">
            <h2>Notes</h2>
            <ul>
                <li>
                    The Koin benchmarks deserve a couple notes because the work is not exactly like-for-like:
                    <ul>
                        <li>Koin's compiler plugin does less work. It aggregates definitions, generates module and factory wiring, and checks for missing dependencies and cycles, but leaves final graph resolution to runtime. Metro, Dagger, and kotlin-inject resolve and validate graphs from their roots and generate static implementations at compile time.</li>
                        <li>Koin's runtime does more work as a result. The graph work deferred during compilation happens during startup.</li>
                    </ul>
                </li>
                <li>
                    <strong>Legend</strong>
                    <ul>
                        <li><strong>GC</strong> is time spent in garbage collection.</li>
                        <li><strong>MAD</strong> shows variation around the median.</li>
                        <li><strong>Half-run drift</strong> compares the first and second halves of the run.</li>
                        <li><strong>Outliers</strong> are samples more than 20% from the median. All ten samples remain included.</li>
                    </ul>
                </li>
            </ul>
        </div>
        <div id="benchmarks"></div>
        <div class="metadata-section" id="metadata"></div>
    </div>
<script>
const benchmarkData =
HTMLHEAD

    echo "$json_data" >> "$html_file"

    cat >> "$html_file" << 'HTMLTAIL'
;
const colors = { 'control': '#607D8B', 'metro': '#4CAF50', 'metro_noop': '#795548', 'dagger_ksp': '#2196F3', 'dagger_kapt': '#FF9800', 'kotlin_inject_anvil': '#9C27B0', 'koin': '#E91E63' };
const displayNames = { 'control': 'Control', 'metro': 'Metro', 'metro_noop': 'Metro-NOOP', 'dagger_ksp': 'Dagger (KSP)', 'dagger_kapt': 'Dagger (KAPT)', 'kotlin_inject_anvil': 'kotlin-inject', 'koin': 'Koin' };
const benchmarkDescriptions = {
    'abi_change': 'Lower is better. Measures recompilation after a public API change in a shared module.',
    'non_abi_change': 'Lower is better. Measures recompilation after an implementation-only change in a shared module.',
    'plain_abi_change': 'Lower is better. Measures a public API change in shared code that does not use dependency injection.',
    'plain_non_abi_change': 'Lower is better. Measures an implementation-only change in shared code that does not use dependency injection.',
    'raw_compilation': 'Lower is better. Measures the top-level graph or container processing task.',
};

// State for selectable baseline
const firstBenchmarkResults = benchmarkData.benchmarks[0]?.results || [];
const defaultBaselineResult = firstBenchmarkResults.find(result => result.key === 'metro' && result.ref1 !== null)
    || firstBenchmarkResults.find(result => result.key === 'control' && result.ref1 !== null)
    || firstBenchmarkResults.find(result => result.ref1 !== null);
let selectedBaseline = defaultBaselineResult?.key;
document.documentElement.style.setProperty('--baseline-color', colors[selectedBaseline] || '#888');

function formatTime(ms) {
    if (ms === null || ms === undefined) return '—';
    return (ms / 1000).toFixed(1) + 's';
}

function formatGcTime(ms) {
    if (ms === null || ms === undefined) return '';
    return (ms / 1000).toFixed(2) + 's';
}

function formatTimeWithGc(time, gc, stability) {
    if (time === null || time === undefined) return '—';
    let result = formatTime(time);
    if (gc !== null && gc !== undefined) {
        result += ` <span class="gc-time">(gc: ${formatGcTime(gc)})</span>`;
    }
    if (stability) {
        result += `<span class="stability">MAD ${stability.relativeMadPercent.toFixed(1)}% · half-run drift ${stability.halfDriftPercent.toFixed(1)}% · outliers ${stability.outlierCount}</span>`;
    }
    return result;
}

// Calculate percentage difference vs baseline: (value - baseline) / baseline * 100
// e.g., 30s vs 24s baseline = (30-24)/24*100 = +25%
function calculateVsBaseline(value, baselineValue) {
    if (!value || !baselineValue) return { text: '—', class: '' };
    if (value === baselineValue) return { text: 'baseline', class: 'baseline' };
    const pct = ((value - baselineValue) / baselineValue * 100).toFixed(1);
    const mult = (value / baselineValue).toFixed(2);
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

function displayMetroVersion(version) {
    return version?.replace(/-SNAPSHOT$/, '') || '—';
}

function renderVersions() {
    const container = document.getElementById('versions');
    const m = benchmarkData.metadata;
    if (!m) { container.style.display = 'none'; return; }
    const items = [
        ['Metro', displayMetroVersion(m.versions?.metro)],
        ['Kotlin', m.versions?.kotlin],
        ['Dagger', m.versions?.dagger],
        ['KSP', m.versions?.ksp],
        ['Anvil', m.versions?.anvil],
        ['kotlin-inject', m.versions?.kotlinInject],
        ['kotlin-inject-anvil', m.versions?.kotlinInjectAnvil],
        ['Koin', m.versions?.koin],
        ['Koin Compiler', m.versions?.koinCompiler],
        ['Gradle', m.build?.gradle],
        ['Gradle Profiler', m.build?.gradleProfiler?.replace(/^Gradle Profiler version\s+/, '')],
        ['JDK', m.build?.jdk],
        ['JVM Target', m.build?.jvmTarget],
    ];
    container.innerHTML = `
        <h2>Versions</h2>
        <div class="versions-grid">${items.map(([label, value]) => `<div class="version-item"><span class="label">${label}</span><span class="value">${value || '—'}</span></div>`).join('')}</div>
        <p>See the <a href="#build-environment">full build environment and workload</a> at the bottom of the report.</p>`;
}

function formatCommit(commit) {
    if (!commit) return 'unknown';
    const [sha, ...subjectParts] = commit.split(' ');
    if (!/^[0-9a-f]{7,40}$/i.test(sha)) return commit;
    let subjectText = subjectParts.join(' ');
    const recordedMetroVersion = benchmarkData.metadata?.versions?.metro;
    if (recordedMetroVersion) {
        subjectText = subjectText.replaceAll(recordedMetroVersion, displayMetroVersion(recordedMetroVersion));
    }
    const subject = subjectText ? ` ${subjectText}` : '';
    return `<a href="https://github.com/ZacSweers/metro/commit/${sha}">${sha}</a>${subject}`;
}

function renderRefsInfo() {
    const container = document.getElementById('refs-info');
    let html = '';
    if (benchmarkData.refs.ref1 && !benchmarkData.refs.ref2) {
        html += `<div class="ref-card baseline"><h3>Source</h3><div class="ref-name">Metro ${frameworkVersion('metro')}</div><div class="commit">${formatCommit(benchmarkData.refs.ref1.commit)}</div></div>`;
    } else if (benchmarkData.refs.ref1) {
        html += `<div class="ref-card baseline"><h3>Baseline (ref1)</h3><div class="ref-name">${benchmarkData.refs.ref1.label}</div><div class="commit">${formatCommit(benchmarkData.refs.ref1.commit)}</div></div>`;
    }
    if (benchmarkData.refs.ref2) {
        html += `<div class="ref-card comparison"><h3>Comparison (ref2)</h3><div class="ref-name">${benchmarkData.refs.ref2.label}</div><div class="commit">${formatCommit(benchmarkData.refs.ref2.commit)}</div></div>`;
    }
    container.innerHTML = html;
}

function renderSummaryStats() {
    const container = document.getElementById('summary-stats');
    let totalSpeedup = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0, koin: 0 };
    let counts = { dagger_ksp: 0, dagger_kapt: 0, kotlin_inject_anvil: 0, koin: 0 };
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
    const names = { 'dagger_ksp': 'Dagger (KSP)', 'dagger_kapt': 'Dagger (KAPT)', 'kotlin_inject_anvil': 'kotlin-inject', 'koin': 'Koin' };
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

function frameworkVersion(key) {
    const versions = benchmarkData.metadata?.versions || {};
    switch (key) {
        case 'control':
            return versions.kotlin ? `Kotlin ${versions.kotlin}` : '—';
        case 'metro':
            return displayMetroVersion(versions.metro);
        case 'dagger_ksp':
            if (versions.dagger && versions.ksp) {
                return `${versions.dagger} / KSP ${versions.ksp}`;
            }
            return versions.dagger || (versions.ksp ? `KSP ${versions.ksp}` : '—');
        case 'dagger_kapt':
            return versions.dagger || '—';
        case 'kotlin_inject_anvil':
            const kotlinInjectVersions = [];
            if (versions.kotlinInject) kotlinInjectVersions.push(versions.kotlinInject);
            if (versions.kotlinInjectAnvil) kotlinInjectVersions.push(`anvil ${versions.kotlinInjectAnvil}`);
            if (versions.ksp) kotlinInjectVersions.push(`KSP ${versions.ksp}`);
            return kotlinInjectVersions.join(' / ') || '—';
        case 'koin':
            if (versions.koin && versions.koinCompiler) {
                return `${versions.koin} / compiler ${versions.koinCompiler}`;
            }
            return versions.koin || versions.koinCompiler || '—';
        default:
            return '—';
    }
}

function renderBenchmarks() {
    const container = document.getElementById('benchmarks');
    const isComparison = Boolean(benchmarkData.refs.ref2);
    let html = '';
    benchmarkData.benchmarks.forEach((benchmark, idx) => {
        html += `<div class="benchmark-section"><h2>${benchmark.name}</h2>
            <div class="chart-hint">${benchmarkDescriptions[benchmark.key] || 'Lower is better.'}</div>
            <div class="legend">${benchmark.results.map(r => `<div class="legend-item"><div class="legend-color" style="background: ${colors[r.key]}"></div><span>${r.framework}</span></div>`).join('')}</div>
            <div class="chart-container"><canvas id="chart-${idx}"></canvas></div>
            <table><thead><tr><th></th><th>Framework</th>${isComparison
                ? `<th class="numeric">${benchmarkData.refs.ref1.label}</th><th class="numeric">vs <span class="baseline-header">${getBaselineLabel()}</span></th><th class="numeric">${benchmarkData.refs.ref2.label}</th><th class="numeric">vs <span class="baseline-header">${getBaselineLabel()}</span></th><th class="numeric">Difference</th>`
                : `<th class="numeric">Time</th><th class="numeric">vs <span class="baseline-header">${getBaselineLabel()}</span></th>`}
            </tr></thead><tbody id="table-${idx}"></tbody></table></div>`;
    });
    container.innerHTML = html;
    benchmarkData.benchmarks.forEach((benchmark, idx) => { renderChart(benchmark, idx); renderTable(benchmark, idx); });
}

const charts = [];
function renderChart(benchmark, idx) {
    const ctx = document.getElementById(`chart-${idx}`).getContext('2d');
    const isComparison = Boolean(benchmarkData.refs.ref2);
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
    charts[idx] = new Chart(ctx, { type: 'bar', data: { labels, datasets }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: datasets.length > 1 }, tooltip: { callbacks: { label: ctx => {
        const label = isComparison ? ctx.dataset.label : benchmark.results[ctx.dataIndex].framework;
        return label + ': ' + ctx.raw.toFixed(1) + 's';
    } } } }, scales: { y: { beginAtZero: true, title: { display: true, text: 'Time (seconds)' } } } } });
}

function renderTable(benchmark, idx) {
    const tbody = document.getElementById(`table-${idx}`);
    const isComparison = Boolean(benchmarkData.refs.ref2);
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
            ${isComparison
                ? `<td class="numeric">${result.ref1 ? formatTimeWithGc(result.ref1, result.gc1, result.stability1) : '<span class="no-data">N/A</span>'}</td><td class="numeric vs-baseline ${vsBaseline1.class}">${vsBaseline1.text}</td><td class="numeric">${result.ref2 ? formatTimeWithGc(result.ref2, result.gc2, result.stability2) : '<span class="no-data">(not run)</span>'}</td><td class="numeric vs-baseline ${vsBaseline2.class}">${vsBaseline2.text}</td><td class="numeric diff ${diff.class}">${diff.text}</td>`
                : `<td class="numeric">${result.ref1 ? formatTimeWithGc(result.ref1, result.gc1, result.stability1) : '<span class="no-data">N/A</span>'}</td><td class="numeric vs-baseline ${vsBaseline1.class}">${vsBaseline1.text}</td>`}</tr>`;
    });
    tbody.innerHTML = html;
}

function setBaseline(key) {
    selectedBaseline = key;
    document.documentElement.style.setProperty('--baseline-color', colors[key] || '#888');
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
        <h2 id="build-environment">Build Environment and Workload</h2>
        <div class="metadata-grid">
            <div class="metadata-group">
                <h3>Generated Workload</h3>
                <dl>
                    <dt>Seed</dt><dd>${m.workload?.seed ?? '—'}</dd>
                    <dt>Fingerprint</dt><dd>${m.workload?.fingerprint || '—'}</dd>
                    <dt>Modules</dt><dd>${m.workload?.moduleCount ?? '—'} total (${m.workload?.modulesByLayer?.core ?? '—'} core, ${m.workload?.modulesByLayer?.features ?? '—'} feature, ${m.workload?.modulesByLayer?.app ?? '—'} app)</dd>
                    <dt>Dependency edges</dt><dd>${m.workload?.dependencyEdgeCount ?? '—'}</dd>
                    <dt>Contributions</dt><dd>${m.workload?.contributionCount ?? '—'} total (${m.workload?.contributionsByKind?.binding ?? '—'} bindings, ${m.workload?.contributionsByKind?.plugin ?? '—'} plugins, ${m.workload?.contributionsByKind?.initializer ?? '—'} initializers)</dd>
                    <dt>Subcomponents</dt><dd>${m.workload?.subcomponents?.total ?? '—'} total (L1 ${m.workload?.subcomponents?.l1 ?? '—'}, L2/L1 ${m.workload?.subcomponents?.l2PerL1 ?? '—'}, L3/L2 ${m.workload?.subcomponents?.l3PerL2 ?? '—'})</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>Library Versions</h3>
                <dl>
                    <dt>Metro</dt><dd>${displayMetroVersion(m.versions?.metro)}</dd>
                    <dt>Kotlin</dt><dd>${m.versions?.kotlin || '—'}</dd>
                    <dt>Dagger</dt><dd>${m.versions?.dagger || '—'}</dd>
                    <dt>KSP</dt><dd>${m.versions?.ksp || '—'}</dd>
                    <dt>kotlin-inject</dt><dd>${m.versions?.kotlinInject || '—'}</dd>
                    <dt>Anvil</dt><dd>${m.versions?.anvil || '—'}</dd>
                    <dt>kotlin-inject-anvil</dt><dd>${m.versions?.kotlinInjectAnvil || '—'}</dd>
                    <dt>Koin</dt><dd>${m.versions?.koin || '—'}</dd>
                    <dt>Koin Compiler</dt><dd>${m.versions?.koinCompiler || '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>Build Tools</h3>
                <dl>
                    <dt>Gradle</dt><dd>${m.build?.gradle || '—'}</dd>
                    <dt>Gradle Profiler</dt><dd>${m.build?.gradleProfiler || '—'}</dd>
                    <dt>JDK</dt><dd>${m.build?.jdk || '—'}</dd>
                    <dt>JVM Target</dt><dd>${m.build?.jvmTarget || '—'}</dd>
                    <dt>Kotlin Compiler Execution</dt><dd>${m.build?.kotlinCompilerExecutionStrategy || '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>System</h3>
                <dl>
                    <dt>OS</dt><dd>${m.system?.os || '—'}</dd>
                    <dt>CPU</dt><dd>${m.system?.cpu || '—'}</dd>
                    <dt>RAM</dt><dd>${m.system?.ram || '—'}</dd>
                    <dt>Daemon JVM Args</dt><dd>${m.system?.daemonJvmArgs || '—'}</dd>
                    <dt>JVM Args Source</dt><dd>${m.system?.daemonJvmArgsSource || '—'}</dd>
                    <dt>Gradle User Home</dt><dd>${m.system?.gradleUserHome || '—'}</dd>
                    <dt>Minimum CPU Idle</dt><dd>${m.system?.minimumIdleCpuPercent ?? '—'}%</dd>
                    <dt>Stable Idle Samples</dt><dd>${m.system?.idleSamplesRequired ?? '—'}</dd>
                    <dt>Iteration Cooldown</dt><dd>${m.system?.cooldownSeconds ?? '—'} seconds</dd>
                    <dt>Incremental Warmups</dt><dd>${m.stability?.incrementalWarmups ?? '—'}</dd>
                    <dt>Graph Processing Warmups</dt><dd>${m.stability?.graphProcessingWarmups ?? '—'}</dd>
                    <dt>Measured Samples</dt><dd>${m.stability?.measuredSamples ?? '—'}</dd>
                    <dt>Maximum Relative MAD</dt><dd>${m.stability?.maxRelativeMadPercent ?? '—'}%</dd>
                    <dt>Maximum Half-Run Drift</dt><dd>${m.stability?.maxHalfDriftPercent ?? '—'}%</dd>
                    <dt>Outlier Threshold</dt><dd>${m.stability?.outlierThresholdPercent ?? '—'}%</dd>
                    <dt>Maximum Outlier Count</dt><dd>${m.stability?.maxOutlierCount ?? '—'}</dd>
                    <dt>Outliers Discarded</dt><dd>${m.stability?.outliersDiscarded === false ? 'No' : '—'}</dd>
                </dl>
            </div>
            <div class="metadata-group">
                <h3>Dagger Options (KSP and KAPT)</h3>
                <dl>
                    <dt>mapMultibindingDuplicateDetectionFix</dt><dd>${m.daggerOptions?.mapMultibindingDuplicateDetectionFix || '—'}</dd>
                    <dt>useBindingGraphFix</dt><dd>${m.daggerOptions?.useBindingGraphFix || '—'}</dd>
                    <dt>ignoreProvisionKeyWildcards</dt><dd>${m.daggerOptions?.ignoreProvisionKeyWildcards || '—'}</dd>
                    <dt>validateTransitiveComponentDependencies</dt><dd>${m.daggerOptions?.validateTransitiveComponentDependencies || '—'}</dd>
                    <dt>strictSuperficialValidation</dt><dd>${m.daggerOptions?.strictSuperficialValidation || '—'}</dd>
                    <dt>fullBindingGraphValidation</dt><dd>${m.daggerOptions?.fullBindingGraphValidation || '—'}</dd>
                    <dt>fastInit</dt><dd>${m.daggerOptions?.fastInit || '—'}</dd>
                    <dt>providerMultibindings</dt><dd>${m.daggerOptions?.providerMultibindings ?? '—'}</dd>
                </dl>
            </div>
        </div>`;
}

document.getElementById('date').textContent = new Date(benchmarkData.date).toLocaleString();
renderRefsInfo(); renderVersions(); renderBenchmarks(); renderMetadata();
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

    if ! load_report_workload_metadata; then
        return 1
    fi
    if ! validate_report_results "$ref1_label" "$modes" false; then
        return 1
    fi
    if [ -n "$ref2_label" ] && ! validate_report_results "$ref2_label" "$modes" true; then
        return 1
    fi
    if ! configure_report_scenarios; then
        return 1
    fi

    local run_dir="$RESULTS_DIR/${TIMESTAMP}"
    local metadata_file="$run_dir/${ref1_label}/build-metadata.json"
    local manifest_file="$run_dir/workload-manifest.json"
    local metadata_json
    if ! metadata_json=$(python3 - "$metadata_file" "$manifest_file" << 'PY'
import json
import sys

metadata_path, manifest_path = sys.argv[1:]
with open(metadata_path, encoding="utf-8") as metadata_file:
    metadata = json.load(metadata_file)
with open(manifest_path, encoding="utf-8") as manifest_file:
    manifest = json.load(manifest_file)

expected_workload = manifest["workload"]
actual_workload = metadata["workload"]
workload_paths = [
    ("seed",),
    ("moduleCount",),
    ("modulesByLayer", "core"),
    ("modulesByLayer", "features"),
    ("modulesByLayer", "app"),
    ("dependencyEdgeCount",),
    ("contributionCount",),
    ("contributionsByKind", "binding"),
    ("contributionsByKind", "plugin"),
    ("contributionsByKind", "initializer"),
    ("subcomponents", "l1"),
    ("subcomponents", "l2PerL1"),
    ("subcomponents", "l3PerL2"),
    ("subcomponents", "total"),
]

if actual_workload.get("fingerprint") != manifest["fingerprint"]:
    raise SystemExit("Build metadata workload fingerprint does not match the canonical manifest")

for path in workload_paths:
    expected = expected_workload
    actual = actual_workload
    for key in path:
        expected = expected[key]
        actual = actual[key]
    if actual != expected:
        joined_path = ".".join(path)
        raise SystemExit(f"Build metadata workload value differs at {joined_path}")

expected_dagger_options = {
    "mapMultibindingDuplicateDetectionFix": "ENABLED (explicit)",
    "useBindingGraphFix": "ENABLED default",
    "ignoreProvisionKeyWildcards": "ENABLED default",
    "validateTransitiveComponentDependencies": "ENABLED default",
    "strictSuperficialValidation": "ENABLED default",
    "fullBindingGraphValidation": "NONE default",
    "fastInit": "DISABLED",
    "providerMultibindings": False,
}
if metadata.get("daggerOptions") != expected_dagger_options:
    raise SystemExit("Build metadata does not contain the expected Dagger option matrix")

expected_stability = {
    "incrementalWarmups": 5,
    "graphProcessingWarmups": 10,
    "measuredSamples": 10,
    "maxRelativeMadPercent": 10,
    "maxHalfDriftPercent": 10,
    "outlierThresholdPercent": 20,
    "maxOutlierCount": 1,
    "outliersDiscarded": False,
}
if metadata.get("stability") != expected_stability:
    raise SystemExit("Build metadata does not contain the expected stability policy")

json.dump(metadata, sys.stdout, separators=(",", ":"))
PY
    ); then
        print_error "Invalid report metadata: $metadata_file" >&2
        return 1
    fi

    local ref1_dirty_file="$run_dir/${ref1_label}/repo-dirty-diff-fingerprint.txt"
    if [ ! -f "$ref1_dirty_file" ]; then
        print_error "Missing repository dirty diff fingerprint: $ref1_dirty_file" >&2
        return 1
    fi
    local ref1_dirty
    ref1_dirty=$(cat "$ref1_dirty_file")

    local ref2_dirty=""
    if [ -n "$ref2_label" ]; then
        local ref2_dirty_file="$run_dir/${ref2_label}/repo-dirty-diff-fingerprint.txt"
        if [ ! -f "$ref2_dirty_file" ]; then
            print_error "Missing repository dirty diff fingerprint: $ref2_dirty_file" >&2
            return 1
        fi
        ref2_dirty=$(cat "$ref2_dirty_file")
    fi

    local ref1_commit
    local ref2_commit=""
    ref1_commit=$(cat "$run_dir/${ref1_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    if [ -n "$ref2_label" ]; then
        ref2_commit=$(cat "$run_dir/${ref2_label}/commit-info.txt" 2>/dev/null || echo "unknown")
    fi

    local date_json
    local ref1_label_json
    local ref1_commit_json
    local ref1_dirty_json
    date_json=$(json_quote "$(date -Iseconds)")
    ref1_label_json=$(json_quote "$ref1_label")
    ref1_commit_json=$(json_quote "$ref1_commit")
    ref1_dirty_json=$(json_quote "$ref1_dirty")

    local -a mode_array
    IFS=',' read -ra mode_array <<< "$modes"

    echo "{"
    echo '  "title": "Build Benchmark Comparison",'
    echo "  \"date\": $date_json,"
    echo "  \"moduleCount\": $WORKLOAD_MODULE_COUNT,"
    echo '  "refs": {'
    echo "    \"ref1\": { \"label\": $ref1_label_json, \"commit\": $ref1_commit_json, \"dirtyDiffFingerprint\": $ref1_dirty_json }"
    if [ -n "$ref2_label" ]; then
        local ref2_label_json
        local ref2_commit_json
        local ref2_dirty_json
        ref2_label_json=$(json_quote "$ref2_label")
        ref2_commit_json=$(json_quote "$ref2_commit")
        ref2_dirty_json=$(json_quote "$ref2_dirty")
        echo "    ,\"ref2\": { \"label\": $ref2_label_json, \"commit\": $ref2_commit_json, \"dirtyDiffFingerprint\": $ref2_dirty_json }"
    fi
    echo '  },'
    echo "  \"metadata\": $metadata_json,"
    echo '  "benchmarks": ['

    local first_test=true
    local i
    for i in "${!REPORT_TEST_TYPES[@]}"; do
        local test_type="${REPORT_TEST_TYPES[$i]}"
        local test_name="${REPORT_TEST_NAMES[$i]}"
        local test_type_json
        local test_name_json
        test_type_json=$(json_quote "$test_type")
        test_name_json=$(json_quote "$test_name")

        if [ "$first_test" = false ]; then
            echo ","
        fi
        first_test=false

        echo '    {'
        echo "      \"name\": $test_name_json,"
        echo "      \"key\": $test_type_json,"
        echo '      "results": ['

        local first_mode=true
        local mode
        for mode in "${mode_array[@]}"; do
            local mode_prefix
            local mode_name
            if ! mode_prefix=$(mode_to_prefix "$mode"); then
                return 1
            fi
            if ! mode_name=$(mode_display_name "$mode"); then
                return 1
            fi

            local score1
            local gc1
            local stability1
            if ! score1=$(extract_median_for_ref "$ref1_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if ! gc1=$(extract_gc_for_ref "$ref1_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if ! stability1=$(extract_stability_for_ref "$ref1_label" "$mode_prefix" "$test_type"); then
                return 1
            fi

            local score2=""
            local gc2=""
            local stability2=""
            if [ -n "$ref2_label" ] && mode_was_run_for_ref "$ref2_label" "$mode_prefix"; then
                if ! score2=$(extract_median_for_ref "$ref2_label" "$mode_prefix" "$test_type"); then
                    return 1
                fi
                if ! gc2=$(extract_gc_for_ref "$ref2_label" "$mode_prefix" "$test_type"); then
                    return 1
                fi
                if ! stability2=$(extract_stability_for_ref "$ref2_label" "$mode_prefix" "$test_type"); then
                    return 1
                fi
            fi

            if [ "$first_mode" = false ]; then
                echo ","
            fi
            first_mode=false

            local mode_name_json
            local mode_prefix_json
            mode_name_json=$(json_quote "$mode_name")
            mode_prefix_json=$(json_quote "$mode_prefix")

            echo '        {'
            echo "          \"framework\": $mode_name_json,"
            echo "          \"key\": $mode_prefix_json,"
            echo "          \"ref1\": $score1,"
            echo "          \"gc1\": $gc1,"
            echo "          \"stability1\": $stability1,"
            if [ -n "$score2" ]; then
                echo "          \"ref2\": $score2,"
                echo "          \"gc2\": $gc2,"
                echo "          \"stability2\": $stability2"
            else
                echo '          "ref2": null,'
                echo '          "gc2": null,'
                echo '          "stability2": null'
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
    local ref_commit
    ref_commit=$(cat "$RESULTS_DIR/${TIMESTAMP}/${ref_label}/commit-info.txt" 2>/dev/null || echo "unknown")

    print_header "Generating Single Ref Summary"

    if ! load_report_workload_metadata; then
        return 1
    fi
    if ! validate_report_results "$ref_label" "$modes" false; then
        return 1
    fi

    local baseline_mode
    local baseline_prefix
    local baseline_name
    if ! baseline_mode=$(select_report_baseline_mode "$ref_label" "$modes"); then
        return 1
    fi
    if ! baseline_prefix=$(mode_to_prefix "$baseline_mode"); then
        return 1
    fi
    if ! baseline_name=$(mode_display_name "$baseline_mode"); then
        return 1
    fi

    cat > "$summary_file" << EOF
# Benchmark Results: $ref_label

**Date:** $(date)
**Module Count:** $WORKLOAD_MODULE_COUNT
**Workload Seed:** $WORKLOAD_SEED
**Workload Fingerprint:** \`$WORKLOAD_FINGERPRINT\`
**Modes:** $modes
**Commit:** $ref_commit

The Koin benchmarks deserve a couple notes because the work is not exactly like-for-like:

- Koin's compiler plugin does less work. It aggregates definitions, generates module and factory wiring, and checks for missing dependencies and cycles, but leaves final graph resolution to runtime. Metro, Dagger, and kotlin-inject resolve and validate graphs from their roots and generate static implementations at compile time.
- Koin's runtime does more work as a result. The graph work deferred during compilation happens during startup.

**ABI scenario note:** Gradle Profiler's generic ABI mutation appends an unrelated top-level function to a foundation file used by every module.

EOF

    local -a mode_array
    IFS=',' read -ra mode_array <<< "$modes"

    local i
    for i in "${!REPORT_TEST_TYPES[@]}"; do
        local test_type="${REPORT_TEST_TYPES[$i]}"
        local test_name="${REPORT_TEST_NAMES[$i]}"

        local baseline_score
        if ! baseline_score=$(extract_median_for_ref "$ref_label" "$baseline_prefix" "$test_type"); then
            return 1
        fi

        cat >> "$summary_file" << EOF
## $test_name

| Framework | Time | GC Time | vs $baseline_name |
|-----------|------|---------|----------|
EOF

        local mode
        for mode in "${mode_array[@]}"; do
            local mode_prefix
            local mode_name
            if ! mode_prefix=$(mode_to_prefix "$mode"); then
                return 1
            fi
            if ! mode_name=$(mode_display_name "$mode"); then
                return 1
            fi

            local score
            local gc_time
            if ! score=$(extract_median_for_ref "$ref_label" "$mode_prefix" "$test_type"); then
                return 1
            fi
            if ! gc_time=$(extract_gc_for_ref "$ref_label" "$mode_prefix" "$test_type"); then
                return 1
            fi

            local display="N/A"
            local display_gc="N/A"
            local vs_baseline="—"

            if [ -n "$score" ]; then
                local secs
                secs=$(echo "scale=1; $score / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$secs" ]; then
                    display="${secs}s"
                fi
                if [ "$mode" = "$baseline_mode" ]; then
                    vs_baseline="baseline"
                elif [ -n "$baseline_score" ] && [ "$baseline_score" != "0" ]; then
                    vs_baseline=$(format_vs_baseline "$score" "$baseline_score")
                fi
            fi

            if [ -n "$gc_time" ]; then
                local gc_secs
                gc_secs=$(echo "scale=2; $gc_time / 1000" | bc 2>/dev/null || echo "")
                if [ -n "$gc_secs" ]; then
                    display_gc="${gc_secs}s"
                fi
            fi

            echo "| $mode_name | $display | $display_gc | $vs_baseline |" >> "$summary_file"
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
    if ! generate_html_report "$ref_label" "" "$modes"; then
        return 1
    fi
}

# Run single ref command
run_single() {
    local count="${1:-$DEFAULT_MODULE_COUNT}"
    local include_clean_builds="${2:-false}"
    local build_only="${3:-false}"

    if [ -z "$SINGLE_REF" ]; then
        print_error "Single requires --ref argument"
        show_usage
        exit 1
    fi

    # Expand "all" mode to actual mode list
    local modes=$(expand_modes "$COMPARE_MODES")

    # Check if ref is a Metro version or HEAD/current (no checkout needed)
    local is_metro_ver=false
    local is_current_branch=false
    if is_metro_version "$SINGLE_REF"; then
        is_metro_ver=true
    elif [[ "$SINGLE_REF" =~ ^(HEAD|head|current)$ ]]; then
        is_current_branch=true
    fi

    # Validate git ref exists (skip for Metro versions and HEAD/current)
    if [ "$is_metro_ver" = false ] && [ "$is_current_branch" = false ]; then
        if ! git rev-parse --verify "$SINGLE_REF" > /dev/null 2>&1; then
            print_error "Invalid git ref: $SINGLE_REF"
            exit 1
        fi

        # Check for uncommitted changes only when checking out a different ref
        if ! git diff-index --quiet HEAD -- 2>/dev/null; then
            print_error "You have uncommitted changes. Please commit or stash them before running benchmarks."
            exit 1
        fi
    fi

    if [ "$is_metro_ver" = true ]; then
        print_header "Running Benchmarks with Metro Version"
        print_status "Metro version: $SINGLE_REF"
    elif [ "$is_current_branch" = true ]; then
        print_header "Running Benchmarks on Current Branch"
    else
        print_header "Running Benchmarks on Git Ref"
        print_status "Ref: $SINGLE_REF"
    fi
    print_status "Modes: $modes"
    print_status "Module count: $count"
    print_status "Workload seed: $WORKLOAD_SEED"
    echo ""

    # Create safe label for directory name
    local ref_label=$(get_ref_safe_name "$SINGLE_REF")

    # Create results directory
    mkdir -p "$RESULTS_DIR/${TIMESTAMP}"

    # For git refs that require checkout, save current git state and set up restore trap
    if [ "$is_metro_ver" = false ] && [ "$is_current_branch" = false ]; then
        save_git_state
        trap 'restore_git_state' EXIT
    fi

    # Run benchmarks for the ref (all modes, not second ref)
    run_benchmarks_for_ref "$SINGLE_REF" "$ref_label" "$count" "$include_clean_builds" "$modes" false "$build_only"

    if [ "$build_only" = true ]; then
        print_header "Build Complete"
        return
    fi

    # Generate summary
    generate_single_summary "$ref_label" "$modes"
    print_header "Benchmarks Complete"
    echo "Results saved to: $RESULTS_DIR/${TIMESTAMP}/"
    echo ""
}

# Run compare command
run_compare() {
    local count="${1:-$DEFAULT_MODULE_COUNT}"
    local include_clean_builds="${2:-false}"
    local build_only="${3:-false}"

    if [ -z "$COMPARE_REF1" ] || [ -z "$COMPARE_REF2" ]; then
        print_error "Compare requires both --ref1 and --ref2 arguments"
        show_usage
        exit 1
    fi

    # Expand "all" mode to actual mode list
    local modes=$(expand_modes "$COMPARE_MODES")

    # Check if refs are Metro versions or git refs
    local ref1_is_metro=false
    local ref2_is_metro=false
    if is_metro_version "$COMPARE_REF1"; then
        ref1_is_metro=true
    fi
    if is_metro_version "$COMPARE_REF2"; then
        ref2_is_metro=true
    fi

    # Validate git refs exist (skip for Metro versions)
    if [ "$ref1_is_metro" = false ]; then
        if ! git rev-parse --verify "$COMPARE_REF1" > /dev/null 2>&1; then
            print_error "Invalid git ref: $COMPARE_REF1"
            exit 1
        fi
    fi
    if [ "$ref2_is_metro" = false ]; then
        if ! git rev-parse --verify "$COMPARE_REF2" > /dev/null 2>&1; then
            print_error "Invalid git ref: $COMPARE_REF2"
            exit 1
        fi
    fi

    # Check for uncommitted changes only if we need to checkout git refs
    local needs_git_checkout=false
    if [ "$ref1_is_metro" = false ] || [ "$ref2_is_metro" = false ]; then
        needs_git_checkout=true
        if ! git diff-index --quiet HEAD -- 2>/dev/null; then
            print_error "You have uncommitted changes. Please commit or stash them before comparing."
            exit 1
        fi
    fi

    print_header "Comparing Benchmarks"
    print_status "Baseline (ref1): $COMPARE_REF1 ($(get_ref_type_description "$COMPARE_REF1"))"
    print_status "Compare (ref2):  $COMPARE_REF2 ($(get_ref_type_description "$COMPARE_REF2"))"
    print_status "Modes:           $modes"
    print_status "Module count:    $count"
    print_status "Workload seed:   $WORKLOAD_SEED"
    if [ "$RERUN_NON_METRO" = true ]; then
        print_status "Re-run non-metro on ref2: yes"
    else
        print_status "Re-run non-metro on ref2: no (using ref1 results)"
    fi
    echo ""

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

    # Save current git state only if we need to checkout git refs
    if [ "$needs_git_checkout" = true ]; then
        save_git_state
        # Set up trap to restore git state on exit
        trap 'restore_git_state' EXIT
    fi

    # Run benchmarks for ref1 (baseline) - run all modes
    run_benchmarks_for_ref "$COMPARE_REF1" "$ref1_label" "$count" "$include_clean_builds" "$modes" false "$build_only"

    # Run benchmarks for ref2 - only metro by default (is_second_ref=true)
    run_benchmarks_for_ref "$COMPARE_REF2" "$ref2_label" "$count" "$include_clean_builds" "$modes" true "$build_only"

    if [ "$build_only" = true ]; then
        print_header "Builds Complete"
        return
    fi

    # Generate comparison summary
    generate_comparison_summary "$ref1_label" "$ref2_label" "$modes"
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
            --seed)
                if [ $# -lt 2 ]; then
                    print_error "--seed requires an integer value"
                    exit 1
                fi
                WORKLOAD_SEED="$2"
                shift 2
                ;;
            --include-baselines)
                INCLUDE_BASELINES=true
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
            --scenarios)
                SCENARIOS_FILTER="$2"
                shift 2
                ;;
            --rerun-non-metro)
                RERUN_NON_METRO=true
                shift
                ;;
            --profile)
                PROFILE_OPTIONS+=("$2")
                shift 2
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

        if ! command -v python3 &> /dev/null; then
            missing_tools+=("python3")
        fi

        if ! command -v sha256sum &> /dev/null && ! command -v shasum &> /dev/null; then
            missing_tools+=("sha256sum or shasum")
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
    validate_seed "$WORKLOAD_SEED"
    INCLUDE_CLEAN_BUILDS="$include_clean_builds"

    case "$command" in
        all)
            # 'all' is shorthand for 'single --ref HEAD --modes all' (current branch)
            SINGLE_REF="HEAD"
            COMPARE_MODES="all"
            run_single "$count" "$include_clean_builds" "$build_only"
            ;;
        control|metro|metro-noop|dagger-ksp|dagger-kapt|kotlin-inject-anvil|koin)
            # Single mode is shorthand for 'single --ref HEAD --modes <mode>' (current branch)
            SINGLE_REF="HEAD"
            COMPARE_MODES="$command"
            run_single "$count" "$include_clean_builds" "$build_only"
            ;;
        single)
            run_single "$count" "$include_clean_builds" "$build_only"
            ;;
        compare)
            run_compare "$count" "$include_clean_builds" "$build_only"
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
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
