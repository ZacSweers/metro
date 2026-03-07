#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Updates docs/compatibility.md with tested versions from compiler-compat/version-aliases.txt
# and IDE tested versions from ide-integration-tests/ide-versions.txt.
# Also updates the Kotlin version badge in README.md

ALIASES_FILE="compiler-compat/version-aliases.txt"
IDE_VERSIONS_FILE="ide-integration-tests/ide-versions.txt"
DOCS_FILE="docs/compatibility.md"
README_FILE="README.md"

if [ ! -f "$ALIASES_FILE" ]; then
    echo "❌ Error: $ALIASES_FILE not found"
    exit 1
fi

if [ ! -f "$DOCS_FILE" ]; then
    echo "❌ Error: $DOCS_FILE not found"
    exit 1
fi

if [ ! -f "$README_FILE" ]; then
    echo "❌ Error: $README_FILE not found"
    exit 1
fi

echo "🔄 Updating $DOCS_FILE with tested versions from $ALIASES_FILE..."

# Read versions from version-aliases.txt (skip comments and blank lines)
versions=$(grep -v '^#' "$ALIASES_FILE" | grep -v '^[[:space:]]*$' | sort -V -r)

if [ -z "$versions" ]; then
    echo "❌ Error: No versions found in $ALIASES_FILE"
    exit 1
fi

# Find the maximum width needed (at least as wide as "Kotlin Version")
header_width=14  # length of "Kotlin Version"
max_width=$header_width

for version in $versions; do
    version_len=${#version}
    if [ $version_len -gt $max_width ]; then
        max_width=$version_len
    fi
done

# Create the tested versions section
tested_section="## Tested Versions

[![CI](https://github.com/ZacSweers/metro/actions/workflows/ci.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ci.yml)

The following Kotlin versions are tested via CI:

| Kotlin Version$(printf '%*s' $((max_width - header_width)) '') |
|$(printf '%*s' $((max_width + 2)) '' | tr ' ' '-')|"

for version in $versions; do
    padding=$((max_width - ${#version}))
    tested_section="$tested_section
| $version$(printf '%*s' $padding '') |"
done

tested_section="$tested_section

!!! note
    Versions without dedicated compiler-compat modules will use the nearest available implementation _below_ that version. See [\`compiler-compat/version-aliases.txt\`](https://github.com/ZacSweers/metro/blob/main/compiler-compat/version-aliases.txt) for the full list.
"

# Add IDE tested versions if ide-versions.txt exists
if [ -f "$IDE_VERSIONS_FILE" ]; then
    echo "🔄 Adding IDE tested versions from $IDE_VERSIONS_FILE..."

    # Parse IDE versions into separate IJ and AS lists
    # For AS, trailing comment is the display name (e.g., "Panda 1 RC 1")
    ij_entries=()
    as_entries=()
    while IFS= read -r line; do
        display_name=""
        if [[ "$line" == *"# "* ]]; then
            display_name="${line##*# }"
            line="${line%%#*}"
            line="${line%% }"
        fi
        IFS=: read -r product version _prefix <<< "$line"
        case "$product" in
            IU) ij_entries+=("$version") ;;
            AS)
                if [ -n "$display_name" ]; then
                    as_entries+=("$version ($display_name)")
                else
                    as_entries+=("$version")
                fi
                ;;
        esac
    done < <(grep -v '^#' "$IDE_VERSIONS_FILE" | grep -v '^[[:space:]]*$')

    if [ ${#ij_entries[@]} -gt 0 ] || [ ${#as_entries[@]} -gt 0 ]; then
        # Find column widths
        ij_col=13  # length of "IntelliJ IDEA"
        as_col=14  # length of "Android Studio"
        for entry in "${ij_entries[@]}"; do
            if [ ${#entry} -gt $ij_col ]; then ij_col=${#entry}; fi
        done
        for entry in "${as_entries[@]}"; do
            if [ ${#entry} -gt $as_col ]; then as_col=${#entry}; fi
        done

        # Number of rows = max of both lists
        row_count=${#ij_entries[@]}
        if [ ${#as_entries[@]} -gt $row_count ]; then row_count=${#as_entries[@]}; fi

        ij_header="IntelliJ IDEA"
        as_header="Android Studio"
        tested_section="$tested_section
### IDE Tested Versions

[![IDE Integration Tests](https://github.com/ZacSweers/metro/actions/workflows/ide-integration.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ide-integration.yml)

The following IDE versions are tested via IDE integration tests:

| ${ij_header}$(printf '%*s' $((ij_col - ${#ij_header})) '') | ${as_header}$(printf '%*s' $((as_col - ${#as_header})) '') |
|$(printf '%*s' $((ij_col + 2)) '' | tr ' ' '-')|$(printf '%*s' $((as_col + 2)) '' | tr ' ' '-')|"

        for ((i = 0; i < row_count; i++)); do
            ij_val="${ij_entries[$i]:-}"
            as_val="${as_entries[$i]:-}"
            ij_pad=$((ij_col - ${#ij_val}))
            as_pad=$((as_col - ${#as_val}))
            tested_section="$tested_section
| ${ij_val}$(printf '%*s' $ij_pad '') | ${as_val}$(printf '%*s' $as_pad '') |"
        done

        tested_section="$tested_section
"
    fi
fi

# Create temporary files
tmpfile=$(mktemp)
tested_tmpfile=$(mktemp)

# Write the tested section to a temp file
echo "$tested_section" > "$tested_tmpfile"

# Check if "Tested Versions" section already exists
if grep -q "^## Tested Versions" "$DOCS_FILE"; then
    # Replace the existing section
    # Extract everything before "## Tested Versions"
    awk '/^## Tested Versions/ {exit} {print}' "$DOCS_FILE" > "$tmpfile"

    # Append the new tested versions section
    cat "$tested_tmpfile" >> "$tmpfile"

    # Append everything after the tested versions section (starting with the next ## header)
    awk '/^## Tested Versions/ {in_tested=1; seen=1; next} in_tested && /^## / {in_tested=0; print; next} in_tested {next} seen {print}' "$DOCS_FILE" >> "$tmpfile"
else
    # Append the new section at the end
    cat "$DOCS_FILE" > "$tmpfile"
    echo "" >> "$tmpfile"
    cat "$tested_tmpfile" >> "$tmpfile"
fi

mv "$tmpfile" "$DOCS_FILE"
rm "$tested_tmpfile"

echo "✅ Updated $DOCS_FILE with $(echo "$versions" | wc -l | tr -d ' ') tested versions"

# Update README.md Kotlin version badge
echo ""
echo "🔄 Updating $README_FILE Kotlin version badge..."

# Get min and max versions (sorted ascending for min, descending for max)
min_version=$(echo "$versions" | sort -V | head -n 1)
max_version=$(echo "$versions" | sort -V | tail -n 1)

# Escape dots and hyphens for the badge URL (shields.io uses -- for hyphen, . is ok)
# The badge format is: Kotlin-MIN--MAX where -- represents a hyphen in the version
badge_min=$(echo "$min_version" | sed 's/-/--/g')
badge_max=$(echo "$max_version" | sed 's/-/--/g')
badge_text="Kotlin-${badge_min}%20--%20${badge_max}"

# Update the Kotlin badge in README.md
# Match the pattern: [![Kotlin](...badge/Kotlin-...-blue.svg...)]
sed -i '' "s|\[!\[Kotlin\](https://img.shields.io/badge/Kotlin-[^]]*-blue.svg?logo=kotlin)\]|\[![Kotlin](https://img.shields.io/badge/${badge_text}-blue.svg?logo=kotlin)]|g" "$README_FILE"

echo "✅ Updated $README_FILE with Kotlin version range: $min_version - $max_version"
echo ""
echo "📝 Review the changes to ensure formatting is correct"
