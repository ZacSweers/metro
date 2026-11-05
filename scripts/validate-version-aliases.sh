#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Validates that listed versions cover all compiler-compat modules, including prerelease mappings.

ALIASES_FILE="compiler-compat/version-aliases.txt"
MAPPINGS_FILE="compiler-compat/prerelease-mappings.txt"

if [ ! -f "$ALIASES_FILE" ]; then
    echo "❌ Error: $ALIASES_FILE not found"
    exit 1
fi

echo "🔍 Validating version-aliases.txt..."

# Read versions from version-aliases.txt (skip comments and blank lines)
declared_versions=$(grep -v '^#' "$ALIASES_FILE" | grep -v '^[[:space:]]*$' | sort)

# Only listed prereleases provide CI coverage for their mapped modules.
covered_versions="$declared_versions"
while IFS='=' read -r prerelease version; do
    if [[ -z "$prerelease" || "$prerelease" == \#* ]]; then
        continue
    fi
    if grep -Fxq "$prerelease" <<< "$declared_versions"; then
        covered_versions="$covered_versions"$'\n'"$version"
    fi
done < "$MAPPINGS_FILE"

# Find all k-prefixed directories with version.txt files
module_dirs=$(find compiler-compat -maxdepth 1 -type d -name 'k*' -exec test -f {}/version.txt \; -print | sort)

if [ -z "$module_dirs" ]; then
    echo "❌ Error: No compiler-compat modules found with version.txt files"
    exit 1
fi

# Read versions from modules
module_versions=""
for module_dir in $module_dirs; do
    version=$(cat "$module_dir/version.txt" | tr -d '\n')
    module_versions="$module_versions$version"$'\n'
done
module_versions=$(echo "$module_versions" | sort)

# Check that all module versions have coverage.
echo ""
echo "📦 Checking all modules are covered by $ALIASES_FILE..."
missing_in_file=""
for version in $module_versions; do
    if ! grep -Fxq "$version" <<< "$covered_versions"; then
        missing_in_file="$missing_in_file$version"$'\n'
        echo "  ❌ Module version $version is not covered by $ALIASES_FILE"
    else
        echo "  ✅ $version"
    fi
done

if [ -n "$missing_in_file" ]; then
    echo ""
    echo "❌ Error: $ALIASES_FILE leaves these module versions uncovered:"
    echo "$missing_in_file"
    echo ""
    echo "Add each missing version or a prerelease mapped to it to $ALIASES_FILE"
    exit 1
fi

# Check that all declared versions have corresponding modules (informational only)
echo ""
echo "📋 Checking declared versions coverage..."
missing_modules=""
for version in $declared_versions; do
    if ! grep -Fxq "$version" <<< "$module_versions"; then
        missing_modules="$missing_modules$version"$'\n'
        echo "  ℹ️  Declared version $version has no corresponding module (will use a compatible module)"
    else
        echo "  ✅ $version"
    fi
done

if [ -n "$missing_modules" ]; then
    echo ""
    echo "ℹ️  Note: $ALIASES_FILE declares versions without modules:"
    echo "$missing_modules"
    echo ""
    echo "These versions are supported for CI and will use a compatible module implementation."
fi

echo ""
echo "✅ Validation passed! $ALIASES_FILE covers all available modules."
