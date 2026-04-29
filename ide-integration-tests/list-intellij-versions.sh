#!/usr/bin/env bash
# Lists recent IntelliJ IDEA Ultimate versions from the JetBrains API.
# Useful for finding version numbers when adding new IU versions to test.
#
# Usage: ./list-intellij-versions.sh [--all]

set -euo pipefail

SHOW_ALL=false
if [[ "${1:-}" == "--all" ]]; then
  SHOW_ALL=true
fi

API_BASE="https://data.services.jetbrains.com/products/releases?code=IU"

echo "Fetching IntelliJ IDEA Ultimate releases..."
echo ""

print_channel() {
  local type="$1"
  local display_name="$2"
  local count="${3:-3}"

  local api_response
  api_response=$(curl -sL "${API_BASE}&type=${type}") || {
    echo "  (failed to fetch $display_name releases)"
    echo ""
    return
  }

  echo "## $display_name"
  echo ""

  # Parse JSON: extract version and build for each release.
  # The API returns {"IU": [{...}, ...]}. We extract version+build pairs.
  local items
  items=$(echo "$api_response" | python3 -c "
import json, sys
data = json.load(sys.stdin)
releases = data.get('IIU', [])[:${count}]
for r in releases:
    print(f\"{r['version']}\t{r['build']}\t{r.get('date', 'unknown')}\")
" 2>/dev/null) || {
    echo "  (failed to parse response)"
    echo ""
    return
  }

  if [[ -z "$items" ]]; then
    echo "  (none found)"
    echo ""
    return
  fi

  while IFS=$'\t' read -r version build date; do
    echo "  IntelliJ IDEA Ultimate $version ($display_name)"
    echo "    Build: $build"
    echo "    Date:  $date"
    if [[ "$type" == "release" ]]; then
      echo "    ide-versions.txt: IU:$version"
    else
      # Prereleases use build number since they're not in the IntelliJ release repos
      echo "    ide-versions.txt: IU:$build:$type # $version $(echo "$type" | tr '[:lower:]' '[:upper:]')"
    fi
    echo ""
  done <<< "$items"
}

echo "Recent IntelliJ IDEA Ultimate Versions"
echo "======================================="
echo ""

if [[ "$SHOW_ALL" == true ]]; then
  count=3
else
  count=1
fi

print_channel "release" "Stable" "$count"
print_channel "rc" "Release Candidate" "$count"
print_channel "eap" "EAP" "$count"
