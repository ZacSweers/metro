#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Updates docs/compatibility.md with tested versions from compiler-compat/version-aliases.txt

ALIASES_FILE="compiler-compat/version-aliases.txt"
DOCS_FILE="docs/compatibility.md"

if [ ! -f "$ALIASES_FILE" ]; then
    echo "❌ Error: $ALIASES_FILE not found"
    exit 1
fi

if [ ! -f "$DOCS_FILE" ]; then
    echo "❌ Error: $DOCS_FILE not found"
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
    awk '/^## Tested Versions/ {in_tested=1; next} in_tested && /^## / {in_tested=0} !in_tested && /^## / {print; flag=1; next} flag {print}' "$DOCS_FILE" >> "$tmpfile"
else
    # Append the new section at the end
    cat "$DOCS_FILE" > "$tmpfile"
    echo "" >> "$tmpfile"
    cat "$tested_tmpfile" >> "$tmpfile"
fi

mv "$tmpfile" "$DOCS_FILE"
rm "$tested_tmpfile"

echo "✅ Updated $DOCS_FILE with $(echo "$versions" | wc -l | tr -d ' ') tested versions"
echo ""
echo "📝 Review the changes to ensure formatting is correct"
