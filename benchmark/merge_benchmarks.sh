#!/bin/bash

# Simple HTML benchmark merging using jq
# Extracts JSON data from HTML files and creates merged comparison files

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

# Function to extract JSON from HTML file
extract_json() {
    local html_file="$1"
    
    # Extract JSON between "const benchmarkResult =" and the closing "}"
    awk '/const benchmarkResult =/{flag=1; next} flag && /^}$/{print; exit} flag' "$html_file"
}

# Function to create merged HTML from JSON data
create_merged_html() {
    local merged_json="$1"
    local output_file="$2"
    local template_file="$3"
    
    # Extract everything before the JSON data
    awk '/const benchmarkResult =/{print; exit} {print}' "$template_file" > "$output_file"
    
    # Append the merged JSON data
    echo "$merged_json" >> "$output_file"
    
    # Add the required semicolon and continue with the rest of the script
    echo ";" >> "$output_file"
    
    # Append everything after the JSON data ends - skip to the JavaScript that follows
    # We know the JavaScript starts with the IIFE pattern "(function(){function r(e,n,t)"
    awk '/\(function\(\)\{function r\(e,n,t\)/{found=1} found{print}' "$template_file" >> "$output_file"
}

# Main merging function
merge_benchmarks() {
    local test_type="$1"
    local timestamp="$2"
    local results_dir="$3"
    
    print_status "Merging $test_type benchmark results"
    
    local temp_dir=$(mktemp -d)
    local json_files=()
    local scenarios=()
    
    # Extract JSON from each mode's scenario-specific HTML file
    local template_html_file=""
    for mode_dir in "$results_dir"/*"$timestamp"; do
        if [ -d "$mode_dir" ]; then
            # Look for all scenario directories and filter by scenario name
            for scenario_dir in "$mode_dir"/*; do
                if [ -d "$scenario_dir" ]; then
                    local html_file="$scenario_dir/benchmark.html"
                    if [ -f "$html_file" ]; then
                        # Use the first HTML file as template
                        if [ -z "$template_html_file" ]; then
                            template_html_file="$html_file"
                        fi
                        
                        local json_file="$temp_dir/$(basename "$mode_dir")_$(basename "$scenario_dir").json"
                        
                        print_status "Extracting JSON from $html_file"
                        if extract_json "$html_file" > "$json_file"; then
                            # Filter scenarios by exact test type match (handle non_abi_change vs abi_change)
                            local scenarios_data=$(jq --arg test_type "$test_type" '
                                .scenarios | map(select(
                                    (.definition.name | test("_" + $test_type + "$")) and
                                    (.definition.name | test("_non_" + $test_type + "$") | not)
                                ))
                            ' "$json_file")
                            
                            if [ "$scenarios_data" != "[]" ] && [ "$scenarios_data" != "null" ]; then
                                scenarios+=("$scenarios_data")
                                json_files+=("$json_file")
                            fi
                        fi
                    fi
                fi
            done
        fi
    done
    
    if [ ${#scenarios[@]} -eq 0 ]; then
        print_status "No scenarios found for test type: $test_type"
        rm -rf "$temp_dir"
        return 1
    fi
    
    # Create merged JSON
    local merged_json_file="$temp_dir/merged.json"
    
    # Start with the first file as template
    local template_file="${json_files[0]}"
    jq --argjson scenarios "$(printf '%s\n' "${scenarios[@]}" | jq -s 'add')" '
        .scenarios = $scenarios |
        .date = (now | todate)
    ' "$template_file" > "$merged_json_file"
    
    # Create output HTML
    local output_file="$results_dir/merged_${test_type}_${timestamp}.html"
    local merged_json=$(cat "$merged_json_file")
    
    create_merged_html "$merged_json" "$output_file" "$template_html_file"
    
    # Cleanup
    rm -rf "$temp_dir"
    
    print_success "Created merged result: $output_file"
}

# Usage check
if [ $# -lt 3 ]; then
    echo "Usage: $0 <test_type> <timestamp> <results_dir>"
    echo "Example: $0 abi_change 20231201_120000 benchmark-results"
    exit 1
fi

merge_benchmarks "$1" "$2" "$3"