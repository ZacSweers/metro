#!/bin/bash
#
# Fetches Kotlin compiler versions from the JetBrains bootstrap Maven repository
# and outputs the top N versions sorted by version number (newest first).
#
# Usage: fetch-kotlin-bootstrap-versions.sh [count]
#   count: Number of versions to output (default: 10)
#
# Output: One version per line, sorted newest first
#

set -euo pipefail

MAVEN_URL="https://packages.jetbrains.team/maven/p/kt/bootstrap/org/jetbrains/kotlin/kotlin-compiler/"
COUNT="${1:-10}"

# Fetch the directory listing and extract version numbers
# Versions appear as title="VERSION/" in the HTML anchor tags
curl -sSL "$MAVEN_URL" \
  | grep -oE 'title="[0-9]+\.[0-9]+\.[0-9]+[^"]*/"' \
  | sed 's/title="//; s/\/"//' \
  | sort -V -r \
  | head -n "$COUNT"
