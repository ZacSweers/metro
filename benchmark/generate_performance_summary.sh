#!/bin/bash

# Generate performance summary tables in the format used in docs/performance.md
# Extracts p50/median data from benchmark CSV files and formats them as markdown tables

set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Function to calculate median from a specific column in measured build times
# Args: csv_file, column_number (default: 2 for build time, 3 for GC time)
calculate_median() {
    local csv_file="$1"
    local column="${2:-2}"

    # Extract measured build times from specified column (skip header and warm-up builds)
    local times=$(awk -F, -v col="$column" '/^measured build/ {print $col}' "$csv_file" | sort -n)

    if [ -z "$times" ]; then
        echo "0"
        return
    fi

    # Convert to array and calculate median
    local times_array=($times)
    local count=${#times_array[@]}

    if [ $count -eq 0 ]; then
        echo "0"
        return
    fi

    local median_index=$((count / 2))

    if [ $((count % 2)) -eq 1 ]; then
        # Odd number of elements
        echo "${times_array[$median_index]}"
    else
        # Even number of elements - average the two middle values
        local mid1_index=$((median_index - 1))
        local mid1=${times_array[$mid1_index]}
        local mid2=${times_array[$median_index]}
        echo "scale=2; ($mid1 + $mid2) / 2" | bc
    fi
}

# Function to calculate median GC time from measured build times (column 3)
calculate_gc_median() {
    local csv_file="$1"
    calculate_median "$csv_file" 3
}

# Function to convert milliseconds to seconds with proper formatting
ms_to_seconds() {
    local ms="$1"
    echo "scale=1; $ms / 1000" | bc
}

# Function to calculate percentage increase
calculate_percentage() {
    local baseline="$1"
    local value="$2"

    if [ "$baseline" = "0" ] || [ -z "$baseline" ]; then
        echo "0"
        return
    fi

    # Use scale=1 for one decimal place, then truncate trailing zeros
    local pct=$(echo "scale=1; (($value - $baseline) * 100) / $baseline" | bc)
    # Remove trailing .0 if present
    echo "$pct" | sed 's/\.0$//'
}

# Function to collect performance data for a test type
collect_performance_data() {
    local test_type="$1"
    local timestamp="$2"
    local results_dir="$3"

    local metro_csv="$results_dir/metro_${timestamp}/metro_${test_type}/benchmark.csv"
    local dagger_ksp_csv="$results_dir/dagger_ksp_${timestamp}/dagger_ksp_${test_type}/benchmark.csv"
    local dagger_kapt_csv="$results_dir/dagger_kapt_${timestamp}/dagger_kapt_${test_type}/benchmark.csv"
    local kotlin_inject_csv="$results_dir/kotlin_inject_anvil_${timestamp}/kotlin_inject_anvil_${test_type}/benchmark.csv"

    # Calculate medians for build time
    local metro_median=""
    local dagger_ksp_median=""
    local dagger_kapt_median=""
    local kotlin_inject_median=""

    # Calculate medians for GC time
    local metro_gc=""
    local dagger_ksp_gc=""
    local dagger_kapt_gc=""
    local kotlin_inject_gc=""

    if [ -f "$metro_csv" ]; then
        metro_median=$(calculate_median "$metro_csv")
        metro_gc=$(calculate_gc_median "$metro_csv")
    fi

    if [ -f "$dagger_ksp_csv" ]; then
        dagger_ksp_median=$(calculate_median "$dagger_ksp_csv")
        dagger_ksp_gc=$(calculate_gc_median "$dagger_ksp_csv")
    fi

    if [ -f "$dagger_kapt_csv" ]; then
        dagger_kapt_median=$(calculate_median "$dagger_kapt_csv")
        dagger_kapt_gc=$(calculate_gc_median "$dagger_kapt_csv")
    fi

    if [ -f "$kotlin_inject_csv" ]; then
        kotlin_inject_median=$(calculate_median "$kotlin_inject_csv")
        kotlin_inject_gc=$(calculate_gc_median "$kotlin_inject_csv")
    fi

    # Convert to seconds
    local metro_seconds=""
    local dagger_ksp_seconds=""
    local dagger_kapt_seconds=""
    local kotlin_inject_seconds=""

    # Convert GC to seconds
    local metro_gc_seconds=""
    local dagger_ksp_gc_seconds=""
    local dagger_kapt_gc_seconds=""
    local kotlin_inject_gc_seconds=""

    if [ -n "$metro_median" ] && [ "$metro_median" != "0" ]; then
        metro_seconds=$(ms_to_seconds "$metro_median")
    fi
    if [ -n "$metro_gc" ] && [ "$metro_gc" != "0" ]; then
        metro_gc_seconds=$(ms_to_seconds "$metro_gc")
    fi

    if [ -n "$dagger_ksp_median" ] && [ "$dagger_ksp_median" != "0" ]; then
        dagger_ksp_seconds=$(ms_to_seconds "$dagger_ksp_median")
    fi
    if [ -n "$dagger_ksp_gc" ] && [ "$dagger_ksp_gc" != "0" ]; then
        dagger_ksp_gc_seconds=$(ms_to_seconds "$dagger_ksp_gc")
    fi

    if [ -n "$dagger_kapt_median" ] && [ "$dagger_kapt_median" != "0" ]; then
        dagger_kapt_seconds=$(ms_to_seconds "$dagger_kapt_median")
    fi
    if [ -n "$dagger_kapt_gc" ] && [ "$dagger_kapt_gc" != "0" ]; then
        dagger_kapt_gc_seconds=$(ms_to_seconds "$dagger_kapt_gc")
    fi

    if [ -n "$kotlin_inject_median" ] && [ "$kotlin_inject_median" != "0" ]; then
        kotlin_inject_seconds=$(ms_to_seconds "$kotlin_inject_median")
    fi
    if [ -n "$kotlin_inject_gc" ] && [ "$kotlin_inject_gc" != "0" ]; then
        kotlin_inject_gc_seconds=$(ms_to_seconds "$kotlin_inject_gc")
    fi

    # Calculate percentage increases relative to Metro
    local dagger_ksp_pct=""
    local dagger_kapt_pct=""
    local kotlin_inject_pct=""

    if [ -n "$metro_median" ] && [ "$metro_median" != "0" ]; then
        if [ -n "$dagger_ksp_median" ] && [ "$dagger_ksp_median" != "0" ]; then
            dagger_ksp_pct=$(calculate_percentage "$metro_median" "$dagger_ksp_median")
        fi

        if [ -n "$dagger_kapt_median" ] && [ "$dagger_kapt_median" != "0" ]; then
            dagger_kapt_pct=$(calculate_percentage "$metro_median" "$dagger_kapt_median")
        fi

        if [ -n "$kotlin_inject_median" ] && [ "$kotlin_inject_median" != "0" ]; then
            kotlin_inject_pct=$(calculate_percentage "$metro_median" "$kotlin_inject_median")
        fi
    fi

    # Return the data in a structured format (now includes GC times)
    echo "${metro_seconds}|${metro_gc_seconds}|${dagger_ksp_seconds}|${dagger_ksp_gc_seconds}|${dagger_ksp_pct}|${dagger_kapt_seconds}|${dagger_kapt_gc_seconds}|${dagger_kapt_pct}|${kotlin_inject_seconds}|${kotlin_inject_gc_seconds}|${kotlin_inject_pct}"
}

# Function to format a table cell with percentage and optional GC time
format_cell() {
    local time="$1"
    local gc="$2"
    local pct="$3"

    if [ -n "$time" ]; then
        local result="${time}s"
        if [ -n "$gc" ]; then
            result="${result} (gc: ${gc}s)"
        fi
        if [ -n "$pct" ] && [ "$pct" != "0" ]; then
            result="${result} (+${pct}%)"
        fi
        echo "$result"
    else
        echo "N/A"
    fi
}

# Function to format Metro cell (no percentage, just time and GC)
format_metro_cell() {
    local time="$1"
    local gc="$2"

    if [ -n "$time" ]; then
        local result="${time}s"
        if [ -n "$gc" ]; then
            result="${result} (gc: ${gc}s)"
        fi
        echo "$result"
    else
        echo "N/A"
    fi
}

# Function to generate the unified performance table
generate_performance_table() {
    local timestamp="$1"
    local results_dir="$2"
    local clean_output="${3:-false}"
    
    if [ "$clean_output" != "true" ]; then
        print_status "Collecting performance data for all test types"
    fi
    
    # Collect data for all test types
    local abi_data=$(collect_performance_data "abi_change" "$timestamp" "$results_dir")
    local non_abi_data=$(collect_performance_data "non_abi_change" "$timestamp" "$results_dir")
    local raw_data=$(collect_performance_data "raw_compilation" "$timestamp" "$results_dir")
    local plain_abi_data=$(collect_performance_data "plain_abi_change" "$timestamp" "$results_dir")
    local plain_non_abi_data=$(collect_performance_data "plain_non_abi_change" "$timestamp" "$results_dir")
    
    # Parse the data (format: metro|metro_gc|dagger_ksp|dagger_ksp_gc|dagger_ksp_pct|dagger_kapt|dagger_kapt_gc|dagger_kapt_pct|kotlin_inject|kotlin_inject_gc|kotlin_inject_pct)
    IFS='|' read -r abi_metro abi_metro_gc abi_dagger_ksp abi_dagger_ksp_gc abi_dagger_ksp_pct abi_dagger_kapt abi_dagger_kapt_gc abi_dagger_kapt_pct abi_kotlin_inject abi_kotlin_inject_gc abi_kotlin_inject_pct <<< "$abi_data"
    IFS='|' read -r non_abi_metro non_abi_metro_gc non_abi_dagger_ksp non_abi_dagger_ksp_gc non_abi_dagger_ksp_pct non_abi_dagger_kapt non_abi_dagger_kapt_gc non_abi_dagger_kapt_pct non_abi_kotlin_inject non_abi_kotlin_inject_gc non_abi_kotlin_inject_pct <<< "$non_abi_data"
    IFS='|' read -r raw_metro raw_metro_gc raw_dagger_ksp raw_dagger_ksp_gc raw_dagger_ksp_pct raw_dagger_kapt raw_dagger_kapt_gc raw_dagger_kapt_pct raw_kotlin_inject raw_kotlin_inject_gc raw_kotlin_inject_pct <<< "$raw_data"
    IFS='|' read -r plain_abi_metro plain_abi_metro_gc plain_abi_dagger_ksp plain_abi_dagger_ksp_gc plain_abi_dagger_ksp_pct plain_abi_dagger_kapt plain_abi_dagger_kapt_gc plain_abi_dagger_kapt_pct plain_abi_kotlin_inject plain_abi_kotlin_inject_gc plain_abi_kotlin_inject_pct <<< "$plain_abi_data"
    IFS='|' read -r plain_non_abi_metro plain_non_abi_metro_gc plain_non_abi_dagger_ksp plain_non_abi_dagger_ksp_gc plain_non_abi_dagger_ksp_pct plain_non_abi_dagger_kapt plain_non_abi_dagger_kapt_gc plain_non_abi_dagger_kapt_pct plain_non_abi_kotlin_inject plain_non_abi_kotlin_inject_gc plain_non_abi_kotlin_inject_pct <<< "$plain_non_abi_data"

    # Generate the table in docs format
    echo ""
    echo "_(Median times in seconds, GC time in parentheses)_"
    echo ""
    echo "|                          | Metro | Dagger (KSP) | Dagger (KAPT) | Kotlin-Inject |"
    echo "|--------------------------|-------|--------------|---------------|---------------|"

    # ABI row
    echo -n "| **ABI**                  | "
    echo -n "$(format_metro_cell "$abi_metro" "$abi_metro_gc")"
    echo -n "  | $(format_cell "$abi_dagger_ksp" "$abi_dagger_ksp_gc" "$abi_dagger_ksp_pct") | $(format_cell "$abi_dagger_kapt" "$abi_dagger_kapt_gc" "$abi_dagger_kapt_pct") | $(format_cell "$abi_kotlin_inject" "$abi_kotlin_inject_gc" "$abi_kotlin_inject_pct") |"
    echo ""

    # Non-ABI row
    echo -n "| **Non-ABI**              | "
    echo -n "$(format_metro_cell "$non_abi_metro" "$non_abi_metro_gc")"
    echo -n "  | $(format_cell "$non_abi_dagger_ksp" "$non_abi_dagger_ksp_gc" "$non_abi_dagger_ksp_pct") | $(format_cell "$non_abi_dagger_kapt" "$non_abi_dagger_kapt_gc" "$non_abi_dagger_kapt_pct") | $(format_cell "$non_abi_kotlin_inject" "$non_abi_kotlin_inject_gc" "$non_abi_kotlin_inject_pct") |"
    echo ""

    # Plain Kotlin ABI row
    echo -n "| **Plain Kotlin ABI**     | "
    echo -n "$(format_metro_cell "$plain_abi_metro" "$plain_abi_metro_gc")"
    echo -n "  | $(format_cell "$plain_abi_dagger_ksp" "$plain_abi_dagger_ksp_gc" "$plain_abi_dagger_ksp_pct") | $(format_cell "$plain_abi_dagger_kapt" "$plain_abi_dagger_kapt_gc" "$plain_abi_dagger_kapt_pct") | $(format_cell "$plain_abi_kotlin_inject" "$plain_abi_kotlin_inject_gc" "$plain_abi_kotlin_inject_pct") |"
    echo ""

    # Plain Kotlin Non-ABI row
    echo -n "| **Plain Kotlin Non-ABI** | "
    echo -n "$(format_metro_cell "$plain_non_abi_metro" "$plain_non_abi_metro_gc")"
    echo -n "  | $(format_cell "$plain_non_abi_dagger_ksp" "$plain_non_abi_dagger_ksp_gc" "$plain_non_abi_dagger_ksp_pct") | $(format_cell "$plain_non_abi_dagger_kapt" "$plain_non_abi_dagger_kapt_gc" "$plain_non_abi_dagger_kapt_pct") | $(format_cell "$plain_non_abi_kotlin_inject" "$plain_non_abi_kotlin_inject_gc" "$plain_non_abi_kotlin_inject_pct") |"
    echo ""

    # Graph processing row
    echo -n "| **Graph processing**     | "
    echo -n "$(format_metro_cell "$raw_metro" "$raw_metro_gc")"
    echo -n " | $(format_cell "$raw_dagger_ksp" "$raw_dagger_ksp_gc" "$raw_dagger_ksp_pct") | $(format_cell "$raw_dagger_kapt" "$raw_dagger_kapt_gc" "$raw_dagger_kapt_pct") | $(format_cell "$raw_kotlin_inject" "$raw_kotlin_inject_gc" "$raw_kotlin_inject_pct") |"
    echo ""
    echo ""
}

# Main function
main() {
    local timestamp="$1"
    local results_dir="$2"
    local clean_output="${3:-false}"
    
    if [ "$clean_output" != "true" ]; then
        print_status "Generating performance summary for benchmark results from $timestamp"
    fi
    
    echo "# Benchmark Results Summary"
    echo ""
    echo "Generated from benchmark results: $timestamp"
    
    # Generate unified table in docs format
    generate_performance_table "$timestamp" "$results_dir" "$clean_output"
    
    if [ "$clean_output" != "true" ]; then
        print_success "Performance summary generated successfully"
    fi
}

# Usage check
if [ $# -lt 2 ]; then
    echo "Usage: $0 <timestamp> <results_dir>"
    echo "Example: $0 20250610_130443 benchmark-results"
    exit 1
fi

main "$1" "$2"