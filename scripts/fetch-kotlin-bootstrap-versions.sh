#!/bin/bash
#
# Fetches Kotlin compiler versions from a Maven repository
# and outputs the top N versions with their timestamps.
#
# Usage: fetch-kotlin-bootstrap-versions.sh <maven_base_url> [count]
#   maven_base_url: Base URL to the kotlin-compiler artifact directory
#   count: Number of versions to output (default: 10)
#
# Output: Tab-separated "version\ttimestamp" per line, oldest first (newest at bottom)
#

set -euo pipefail

MAVEN_BASE="${1:?Usage: $0 <maven_base_url> [count]}"
COUNT="${2:-10}"

# Get versions from maven-metadata.xml, get top N newest, then reverse for display (oldest first)
ALL_VERSIONS=$(curl -sSL "$MAVEN_BASE/maven-metadata.xml" \
  | grep -oE '<version>[^<]+</version>' \
  | sed 's/<version>//; s/<\/version>//' \
  | sort -V -r)
VERSIONS=$(echo "$ALL_VERSIONS" | head -n "$COUNT" | sort -V)

# For each version, fetch the timestamp from its directory page
for VERSION in $VERSIONS; do
  # Fetch the version directory and extract the timestamp from the .pom file line
  TIMESTAMP=$(curl -sSL "$MAVEN_BASE/$VERSION/" \
    | grep -oE "kotlin-compiler-[^\"]+\.pom</a>[^<]+" \
    | head -1 \
    | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}' \
    || echo "unknown")
  echo -e "$VERSION\t$TIMESTAMP"
done
