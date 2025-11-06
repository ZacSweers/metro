#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Validates that version-aliases.txt is an equal or superset of available compiler-compat modules

ALIASES_FILE="compiler-compat/version-aliases.txt"

if [ ! -f "$ALIASES_FILE" ]; then
    echo "❌ Error: $ALIASES_FILE not found"
    exit 1
fi

echo "🔍 Validating version-aliases.txt..."

# Read versions from version-aliases.txt (skip comments and blank lines)
declared_versions=$(grep -v '^#' "$ALIASES_FILE" | grep -v '^[[:space:]]*$' | sort)

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

# Check that all module versions are declared in the file
echo ""
echo "📦 Checking all modules are declared in $ALIASES_FILE..."
missing_in_file=""
for version in $module_versions; do
    if ! echo "$declared_versions" | grep -Fxq "$version"; then
        missing_in_file="$missing_in_file$version"$'\n'
        echo "  ❌ Module version $version is not declared in $ALIASES_FILE"
    else
        echo "  ✅ $version"
    fi
done

if [ -n "$missing_in_file" ]; then
    echo ""
    echo "❌ Error: $ALIASES_FILE is missing versions that have modules:"
    echo "$missing_in_file"
    echo ""
    echo "The file must be an equal or superset of available modules."
    echo "Add the missing versions to $ALIASES_FILE"
    exit 1
fi

# Check that all declared versions have corresponding modules (informational only)
echo ""
echo "📋 Checking declared versions coverage..."
missing_modules=""
for version in $declared_versions; do
    if ! echo "$module_versions" | grep -Fxq "$version"; then
        missing_modules="$missing_modules$version"$'\n'
        echo "  ℹ️  Declared version $version has no corresponding module (will use nearest available)"
    else
        echo "  ✅ $version"
    fi
done

if [ -n "$missing_modules" ]; then
    echo ""
    echo "ℹ️  Note: $ALIASES_FILE declares versions without modules:"
    echo "$missing_modules"
    echo ""
    echo "These versions are supported for CI and will use the nearest available module implementation."
fi

echo ""
echo "✅ Validation passed! $ALIASES_FILE correctly declares all available modules."