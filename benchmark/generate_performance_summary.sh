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

# Function to calculate median from measured build times
calculate_median() {
    local csv_file="$1"
    
    # Extract measured build times (skip header and warm-up builds)
    local times=$(awk -F, '/^measured build/ {print $2}' "$csv_file" | sort -n)
    
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
    
    echo "scale=0; (($value - $baseline) * 100) / $baseline" | bc
}

# Function to collect performance data for a test type
collect_performance_data() {
    local test_type="$1"
    local timestamp="$2"
    local results_dir="$3"
    
    local metro_csv="$results_dir/metro_${timestamp}/metro_${test_type}/benchmark.csv"
    local anvil_ksp_csv="$results_dir/anvil_ksp_${timestamp}/anvil_ksp_${test_type}/benchmark.csv"
    local anvil_kapt_csv="$results_dir/anvil_kapt_${timestamp}/anvil_kapt_${test_type}/benchmark.csv"
    local kotlin_inject_csv="$results_dir/kotlin_inject_anvil_${timestamp}/kotlin_inject_anvil_${test_type}/benchmark.csv"
    
    # Calculate medians
    local metro_median=""
    local anvil_ksp_median=""
    local anvil_kapt_median=""
    local kotlin_inject_median=""
    
    if [ -f "$metro_csv" ]; then
        metro_median=$(calculate_median "$metro_csv")
    fi
    
    if [ -f "$anvil_ksp_csv" ]; then
        anvil_ksp_median=$(calculate_median "$anvil_ksp_csv")
    fi
    
    if [ -f "$anvil_kapt_csv" ]; then
        anvil_kapt_median=$(calculate_median "$anvil_kapt_csv")
    fi
    
    if [ -f "$kotlin_inject_csv" ]; then
        kotlin_inject_median=$(calculate_median "$kotlin_inject_csv")
    fi
    
    # Convert to seconds
    local metro_seconds=""
    local anvil_ksp_seconds=""
    local anvil_kapt_seconds=""
    local kotlin_inject_seconds=""
    
    if [ -n "$metro_median" ] && [ "$metro_median" != "0" ]; then
        metro_seconds=$(ms_to_seconds "$metro_median")
    fi
    
    if [ -n "$anvil_ksp_median" ] && [ "$anvil_ksp_median" != "0" ]; then
        anvil_ksp_seconds=$(ms_to_seconds "$anvil_ksp_median")
    fi
    
    if [ -n "$anvil_kapt_median" ] && [ "$anvil_kapt_median" != "0" ]; then
        anvil_kapt_seconds=$(ms_to_seconds "$anvil_kapt_median")
    fi
    
    if [ -n "$kotlin_inject_median" ] && [ "$kotlin_inject_median" != "0" ]; then
        kotlin_inject_seconds=$(ms_to_seconds "$kotlin_inject_median")
    fi
    
    # Calculate percentage increases relative to Metro
    local anvil_ksp_pct=""
    local anvil_kapt_pct=""
    local kotlin_inject_pct=""
    
    if [ -n "$metro_median" ] && [ "$metro_median" != "0" ]; then
        if [ -n "$anvil_ksp_median" ] && [ "$anvil_ksp_median" != "0" ]; then
            anvil_ksp_pct=$(calculate_percentage "$metro_median" "$anvil_ksp_median")
        fi
        
        if [ -n "$anvil_kapt_median" ] && [ "$anvil_kapt_median" != "0" ]; then
            anvil_kapt_pct=$(calculate_percentage "$metro_median" "$anvil_kapt_median")
        fi
        
        if [ -n "$kotlin_inject_median" ] && [ "$kotlin_inject_median" != "0" ]; then
            kotlin_inject_pct=$(calculate_percentage "$metro_median" "$kotlin_inject_median")
        fi
    fi
    
    # Return the data in a structured format
    echo "${metro_seconds}|${anvil_ksp_seconds}|${anvil_ksp_pct}|${anvil_kapt_seconds}|${anvil_kapt_pct}|${kotlin_inject_seconds}|${kotlin_inject_pct}"
}

# Function to format a table cell with percentage
format_cell() {
    local time="$1"
    local pct="$2"
    
    if [ -n "$time" ]; then
        local result="${time}s"
        if [ -n "$pct" ] && [ "$pct" != "0" ]; then
            result="${result} (+${pct}%)"
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
    
    # Parse the data
    IFS='|' read -r abi_metro abi_anvil_ksp abi_anvil_ksp_pct abi_anvil_kapt abi_anvil_kapt_pct abi_kotlin_inject abi_kotlin_inject_pct <<< "$abi_data"
    IFS='|' read -r non_abi_metro non_abi_anvil_ksp non_abi_anvil_ksp_pct non_abi_anvil_kapt non_abi_anvil_kapt_pct non_abi_kotlin_inject non_abi_kotlin_inject_pct <<< "$non_abi_data"
    IFS='|' read -r raw_metro raw_anvil_ksp raw_anvil_ksp_pct raw_anvil_kapt raw_anvil_kapt_pct raw_kotlin_inject raw_kotlin_inject_pct <<< "$raw_data"
    
    # Generate the table in docs format
    echo ""
    echo "_(Median times in seconds)_"
    echo ""
    echo "|                      | Metro | Anvil KSP     | Anvil Kapt    | Kotlin-Inject |"
    echo "|----------------------|-------|---------------|---------------|---------------|"
    
    # ABI row
    echo -n "| **ABI**              | "
    if [ -n "$abi_metro" ]; then
        echo -n "${abi_metro}s"
    else
        echo -n "N/A"
    fi
    echo -n "  | $(format_cell "$abi_anvil_ksp" "$abi_anvil_ksp_pct") | $(format_cell "$abi_anvil_kapt" "$abi_anvil_kapt_pct") | $(format_cell "$abi_kotlin_inject" "$abi_kotlin_inject_pct") |"
    echo ""
    
    # Non-ABI row
    echo -n "| **Non-ABI**          | "
    if [ -n "$non_abi_metro" ]; then
        echo -n "${non_abi_metro}s"
    else
        echo -n "N/A"
    fi
    echo -n "  | $(format_cell "$non_abi_anvil_ksp" "$non_abi_anvil_ksp_pct") | $(format_cell "$non_abi_anvil_kapt" "$non_abi_anvil_kapt_pct") | $(format_cell "$non_abi_kotlin_inject" "$non_abi_kotlin_inject_pct") |"
    echo ""
    
    # Graph processing row
    echo -n "| **Graph processing** | "
    if [ -n "$raw_metro" ]; then
        echo -n "${raw_metro}s"
    else
        echo -n "N/A"
    fi
    echo -n " | $(format_cell "$raw_anvil_ksp" "$raw_anvil_ksp_pct") | $(format_cell "$raw_anvil_kapt" "$raw_anvil_kapt_pct") | $(format_cell "$raw_kotlin_inject" "$raw_kotlin_inject_pct") |"
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