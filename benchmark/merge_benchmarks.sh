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
    
    # Extract JSON between "const benchmarkResult =" and "};"
    sed -n '/const benchmarkResult =/,/^};$/p' "$html_file" | \
    sed '1s/^const benchmarkResult =//' | \
    sed '$s/;$//' | \
    # Remove any trailing whitespace and ensure proper JSON format
    sed '/^$/d' | \
    head -n -1 | \
    tail -n +1
}

# Function to create merged HTML from JSON data
create_merged_html() {
    local merged_json="$1"
    local output_file="$2"
    
    cat > "$output_file" << 'EOF'
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Merged Benchmark Results</title>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/4.6.2/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/select2/4.0.13/css/select2.min.css">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.2.1/css/all.min.css">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Roboto+Mono:wght@300&display=swap">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Lato:wght@300;400;700&display=swap">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/vue/2.7.14/vue.min.js"></script>
    <script src='https://cdnjs.cloudflare.com/ajax/libs/Chart.js/3.9.1/chart.min.js'></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/mathjs/11.5.1/math.min.js"></script>
    <style>
        /* Include the full CSS from the original file */
        html { position: relative; min-height: 100%; }
        body { font-family: 'Lato', sans-serif; font-size: 13.5px; margin-bottom: 3rem; }
        .data { font-size: 12px; font-family: 'Roboto Mono', monospace; white-space: nowrap; }
        th { font-size: 16px; }
        th.center, td.center { text-align: center; }
        th.dark, td.dark { background-color: #f8f8f8; }
        tr:hover td.dark { background-color: #e8e8e8; }
        td.numeric { text-align: right; padding-left: 1em; padding-right: 1em; }
        td.clickable { cursor: pointer; }
        td.WARM_UP { background-color: #fff8ee; }
        td.MEASURE { background-color: #eeffee; }
        tr.baseline { background-color: #ffeedd; }
        #data-table { overflow-x: scroll; }
        .navbar { background-color: #343a40; }
        .navbar-brand { color: white; }
        .btn { margin: 0.2em; }
    </style>
</head>
<body>
<div id="app">
    <nav class="navbar navbar-dark bg-dark">
        <div class="navbar-brand mb-0 h1">
            <span>Merged Benchmark Results</span>
        </div>
        <div class="navbar-brand mb-0 h1">Gradle Profiler</div>
    </nav>
    <div class='container-fluid'>
        <div class='row mt-3'>
            <div class='col'>
                <canvas id='samples' style="width: 100%;"></canvas>
            </div>
        </div>
        <div class="form-row mt-2 controls ml-auto mr-auto">
            <div class="btn-group btn-group-toggle" data-toggle="buttons">
                <button class="btn btn-secondary" :class="{ active: !options.sorted }" @click="options.sorted = false">Historical</button>
                <button class="btn btn-secondary" :class="{ active: options.sorted }" @click="options.sorted = true">Sorted</button>
            </div>
            <div class="btn-group">
                <button class="btn btn-secondary" @click="toggleAll(true)">Select all</button>
                <button class="btn btn-secondary" @click="toggleAll(false)">Clear all</button>
            </div>
            <div class="btn-group btn-group-toggle" data-toggle="buttons">
                <button class="btn btn-secondary" :class="{ active: options.showAll }" @click="options.showAll = true">Show all</button>
                <button class="btn btn-secondary" :class="{ active: !options.showAll }" @click="options.showAll = false">Show selected only</button>
            </div>
        </div>
        <div class="row mt-3">
            <div class='col' id="data-table">
                <table class='table table-sm table-hover'>
                    <thead>
                        <tr>
                            <th></th>
                            <th>Scenario</th>
                            <th class="center dark" v-if="benchmarkResult.scenarios.length > 1">Baseline</th>
                            <th class="center">Sample</th>
                            <th class="center">Mean</th>
                            <th class="center dark">Min</th>
                            <th class="center">P25</th>
                            <th class="center dark">Median</th>
                            <th class="center">P75</th>
                            <th class="center dark">Max</th>
                            <th class="center">Std.dev</th>
                            <th :colspan="benchmarkResult.scenarios.map(scenario => scenario.iterations.length).reduce((a,b) => Math.max(a,b), 0)">Iterations</th>
                        </tr>
                    </thead>
                    <tbody>
                        <template v-for="(scenario, scenarioIndex) in benchmarkResult.scenarios">
                            <template v-for="(sample, index) in scenario.samples.filter(sample => options.showAll || sample.selected)">
                                <tr :class="{ baseline: baseline === scenario }">
                                    <td class="title" v-if="index === 0" :rowspan="scenario.samples.filter(sample => options.showAll || sample.selected).length">
                                        <span class="title">{{ scenario.definition.title }}</span>
                                    </td>
                                    <td class="center title dark" v-if="benchmarkResult.scenarios.length > 1 && index === 0" :rowspan="scenario.samples.filter(sample => options.showAll || sample.selected).length" @click="baseline = baseline === scenario ? null : scenario">
                                        <input type="checkbox" :checked="baseline === scenario">
                                    </td>
                                    <td class="clickable data" @click="select(sample)" :style="{ color: sample.color }">
                                        <span class="fa selection-icon" :class="{ selected: sample.selected }" :style="{ color: sample.color }"></span>
                                        <span>{{ sample.name }}</span>
                                    </td>
                                    <td class="numeric data">{{ (sample.mean || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data dark">{{ (sample.min || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data">{{ (sample.p25 || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data dark">{{ (sample.median || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data">{{ (sample.p75 || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data dark">{{ (sample.max || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <td class="numeric data">{{ (sample.stddev || 0).toFixed(2) }} {{ sample.unit }}</td>
                                    <template v-for="(iteration, iterIndex) in scenario.iterations">
                                        <td class="numeric data" :class="iteration.phase" :title="iteration.phase + ' #' + iteration.iteration">
                                            <span>{{ (iteration.values[sample.name] || 0).toFixed(2) }} {{ sample.unit }}</span>
                                        </td>
                                    </template>
                                </tr>
                            </template>
                        </template>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>
<script>
const benchmarkResult = 
EOF

    # Append the JSON data
    echo "$merged_json" >> "$output_file"
    
    cat >> "$output_file" << 'EOF'
;

// Simple Vue.js application
new Vue({
    el: '#app',
    data: {
        options: {
            sorted: false,
            showAll: true
        },
        benchmarkResult: benchmarkResult,
        baseline: null
    },
    methods: {
        select: function(sample) {
            sample.selected = !sample.selected;
        },
        toggleAll: function(selected) {
            benchmarkResult.scenarios.forEach(scenario => 
                scenario.samples.forEach(sample => sample.selected = selected)
            );
        }
    },
    beforeCreate: function() {
        // Initialize sample config and calculate statistics
        benchmarkResult.scenarios.forEach((scenario, scenarioIndex) => {
            scenario.showDetails = false;
            scenario.samples.forEach((sample, sampleIndex) => {
                sample.color = `hsl(${scenarioIndex * 360 / benchmarkResult.scenarios.length}, ${100 - 80 * sampleIndex / scenario.samples.length}%, ${30 + 40 * sampleIndex / scenario.samples.length}%)`;
                sample.selected = sampleIndex === 0;
                
                // Calculate statistics from measured iterations
                const measuredValues = scenario.iterations
                    .filter(iter => iter.phase === 'MEASURE')
                    .map(iter => iter.values[sample.name])
                    .filter(val => val != null)
                    .sort((a, b) => a - b);
                
                if (measuredValues.length > 0) {
                    sample.mean = measuredValues.reduce((a, b) => a + b) / measuredValues.length;
                    sample.min = measuredValues[0];
                    sample.max = measuredValues[measuredValues.length - 1];
                    sample.p25 = measuredValues[Math.floor(measuredValues.length * 0.25)];
                    sample.median = measuredValues[Math.floor(measuredValues.length * 0.5)];
                    sample.p75 = measuredValues[Math.floor(measuredValues.length * 0.75)];
                    
                    const mean = sample.mean;
                    const variance = measuredValues.reduce((sum, val) => sum + Math.pow(val - mean, 2), 0) / measuredValues.length;
                    sample.stddev = Math.sqrt(variance);
                }
            });
        });
    },
    created: function() {
        this.baseline = benchmarkResult.scenarios.length > 1 ? benchmarkResult.scenarios[0] : null;
    }
});
</script>
</body>
</html>
EOF
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
    
    # Extract JSON from each mode's HTML file
    for mode_dir in "$results_dir"/*"$timestamp"; do
        if [ -d "$mode_dir" ]; then
            local html_file="$mode_dir/benchmark.html"
            if [ -f "$html_file" ]; then
                local json_file="$temp_dir/$(basename "$mode_dir").json"
                
                print_status "Extracting JSON from $html_file"
                if extract_json "$html_file" > "$json_file"; then
                    # Filter scenarios for this test type and add to scenarios array
                    local filtered_scenarios=$(jq --arg test_type "$test_type" '
                        .scenarios | map(select(.definition.name | contains($test_type)))
                    ' "$json_file")
                    
                    if [ "$filtered_scenarios" != "[]" ]; then
                        scenarios+=("$filtered_scenarios")
                        json_files+=("$json_file")
                    fi
                fi
            fi
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
        .date = now | strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    ' "$template_file" > "$merged_json_file"
    
    # Create output HTML
    local output_file="$results_dir/merged_${test_type}_${timestamp}.html"
    local merged_json=$(cat "$merged_json_file")
    
    create_merged_html "$merged_json" "$output_file"
    
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