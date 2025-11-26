#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Generate CI matrix for compiler-compat modules
# This script reads versions from compiler-compat/version-aliases.txt
# and generates a JSON matrix object for use in GitHub Actions

# --versions-only flag is for ./metrow check use to only print the versions and exit
versions_only=false
if [[ "${1:-}" == "--versions-only" ]]; then
    versions_only=true
fi

ALIASES_FILE="compiler-compat/version-aliases.txt"

# First, validate the version-aliases.txt file
if [[ "$versions_only" != true ]]; then
    echo "🔍 Validating version-aliases.txt..."
    ./scripts/validate-version-aliases.sh
    echo ""
fi

if [[ "$versions_only" != true ]]; then
    echo "🔍 Reading versions from $ALIASES_FILE..."
fi

# Read versions from version-aliases.txt (skip comments and blank lines)
versions=$(grep -v '^#' "$ALIASES_FILE" | grep -v '^[[:space:]]*$' | sort)

if [ -z "$versions" ]; then
    if [[ "$versions_only" != true ]]; then
        echo "❌ No versions found in $ALIASES_FILE"
    fi
    exit 1
fi

if [[ "$versions_only" == true ]]; then
    # Just output the versions, one per line
    echo "$versions"
    exit 0
fi

echo "📦 Found versions:"
for version in $versions; do
    echo "  - $version"
done

# Convert to JSON matrix object
json_array="["
first=true
for version in $versions; do
    if [ "$first" = true ]; then
        first=false
    else
        json_array="$json_array,"
    fi

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