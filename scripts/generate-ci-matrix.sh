#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Generate CI matrix for compiler-compat modules
# This script finds all k-prefixed directories in compiler-compat that contain version.txt
# and generates a JSON matrix object for use in GitHub Actions

# --versions-only flag is for ./metrow check use to only print the versions and exit
versions_only=false
if [[ "${1:-}" == "--versions-only" ]]; then
    versions_only=true
fi

if [[ "$versions_only" != true ]]; then
    echo "🔍 Scanning for compiler-compat modules..."
fi

# Find all k-prefixed directories in compiler-compat that contain version.txt
modules=$(find compiler-compat -maxdepth 1 -type d -name 'k*' -exec test -f {}/version.txt \; -print | sort)

if [ -z "$modules" ]; then
    if [[ "$versions_only" != true ]]; then
        echo "❌ No compiler-compat modules found with version.txt files"
    fi
    exit 1
fi

if [[ "$versions_only" == true ]]; then
    # Just output the versions, one per line
    for module_dir in $modules; do
        cat "$module_dir/version.txt" | tr -d '\n'
        echo
    done
    exit 0
fi

echo "📦 Found modules:"
for module_dir in $modules; do
    version=$(cat "$module_dir/version.txt" | tr -d '\n')
    echo "  - $module_dir → $version"
done

# Convert to JSON matrix object
json_array="["
first=true
for module_dir in $modules; do
    if [ "$first" = true ]; then
        first=false
    else
        json_array="$json_array,"
    fi
    
    # Read version from version.txt file
    version=$(cat "$module_dir/version.txt" | tr -d '\n')
    json_array="$json_array\"$version\""
done
json_array="$json_array]"

# Create the matrix JSON object with the kotlin-compiler key
matrix_json="{\"kotlin-compiler\":$json_array}"

echo ""
echo "✅ Generated matrix JSON:"
echo "$matrix_json"

# Pretty print for better readability
if command -v jq >/dev/null 2>&1; then
    echo ""
    echo "📋 Pretty-printed matrix:"
    echo "$matrix_json" | jq .
fi

# Output for GitHub Actions (if running in CI)
if [ "${GITHUB_OUTPUT:-}" ]; then
    echo "matrix=$matrix_json" >> "$GITHUB_OUTPUT"
    echo "🚀 Matrix written to GITHUB_OUTPUT"
fi