#!/bin/bash

# Resolves the Kotlin dev build(s) that an IntelliJ ij-specific Kotlin version branched from.
#
# Given an IntelliJ version (e.g., 252.28238.7), this script walks the git history of
# the kotlinc_kotlin_compiler_common.xml file in the intellij-community repo to find
# the last Kotlin dev builds used before the ij-specific branch was cut.
#
# Usage:
#   ./resolve-ij-kotlin-version.sh <intellij-version> [fake-compiler-version]
#   ./resolve-ij-kotlin-version.sh 252.28238.7
#   ./resolve-ij-kotlin-version.sh 252.28238.7 2.3.255-dev-255
#
# Requires: gh (GitHub CLI), authenticated

set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: $0 <intellij-version> [fake-compiler-version]"
  echo ""
  echo "Examples:"
  echo "  $0 252.28238.7"
  echo "  $0 252.28238.7 2.3.255-dev-255"
  echo ""
  echo "The IntelliJ version can be found from Android Studio's About dialog:"
  echo "  Build #AI-252.28238.7.2523.14688667 -> 252.28238.7"
  exit 1
fi

INTELLIJ_VERSION="$1"
FAKE_VERSION="${2:-}"
TAG="idea/$INTELLIJ_VERSION"
FILE_PATH=".idea/libraries/kotlinc_kotlin_compiler_common.xml"

echo "Resolving Kotlin version history for IntelliJ $INTELLIJ_VERSION..."
echo ""

# Check that gh is available
if ! command -v gh &>/dev/null; then
  echo "Error: 'gh' (GitHub CLI) is required but not found."
  echo "Install it from https://cli.github.com/"
  exit 1
fi

# Fetch commit history for the kotlinc library file on this tag.
# gh api outputs error JSON to stdout on 404, so we check exit code explicitly.
fetch_history() {
  gh api \
    "repos/JetBrains/intellij-community/commits?sha=$1&path=$FILE_PATH&per_page=30" \
    -q '.[] | "\(.commit.committer.date)\t\(.commit.message | split("\n")[0])"' 2>/dev/null
}

HISTORY=""
if ! HISTORY=$(fetch_history "$TAG"); then
  HISTORY=""
fi

if [ -z "$HISTORY" ]; then
  # Exact tag not found — find the nearest tag for the same platform version
  PLATFORM_MAJOR=$(echo "$INTELLIJ_VERSION" | cut -d. -f1)
  echo "Tag '$TAG' not found, searching for nearest idea/$PLATFORM_MAJOR.* tag..."

  NEAREST_TAG=$(gh api \
    "repos/JetBrains/intellij-community/git/matching-refs/tags/idea/$PLATFORM_MAJOR." \
    -q '.[].ref' 2>/dev/null |
    sed 's|refs/tags/||' |
    sort -t. -k1,1n -k2,2n -k3,3n |
    tail -1 || true)

  if [ -n "$NEAREST_TAG" ]; then
    TAG="$NEAREST_TAG"
    echo "Using nearest tag: $TAG"
    echo ""
    if ! HISTORY=$(fetch_history "$TAG"); then
      HISTORY=""
    fi
  fi

  if [ -z "$HISTORY" ]; then
    echo "Error: Could not fetch history for any idea/$PLATFORM_MAJOR.* tag."
    echo "Check available tags: https://github.com/JetBrains/intellij-community/tags?q=idea%2F$PLATFORM_MAJOR"
    exit 1
  fi
fi

# Parse out the version transitions
echo "Kotlin version history on $TAG:"
echo "─────────────────────────────────────────────────────────────"
printf "%-22s  %s\n" "DATE" "VERSION"
echo "─────────────────────────────────────────────────────────────"

LAST_DEV_VERSION=""
LAST_DEV_DATE=""
LATEST_IJ_VERSION=""
LATEST_IJ_DATE=""
FIRST_IJ_VERSION=""
FIRST_IJ_DATE=""

while IFS=$'\t' read -r date message; do
  # Extract version from commit message (|| true to avoid set -e exit)
  version=$(echo "$message" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+-[a-zA-Z0-9._-]+' | head -1 || true)
  if [ -z "$version" ]; then
    continue
  fi

  short_date=$(echo "$date" | cut -dT -f1)
  printf "%-22s  %s\n" "$short_date" "$version"

  # Track the transition from dev -> ij
  # Note: history is newest-first, so "first seen" = latest, "last seen" = earliest
  if echo "$version" | grep -qE '\-ij[0-9]'; then
    if [ -z "$LATEST_IJ_VERSION" ]; then
      LATEST_IJ_VERSION="$version"
      LATEST_IJ_DATE="$short_date"
    fi
    FIRST_IJ_VERSION="$version"
    FIRST_IJ_DATE="$short_date"
  elif echo "$version" | grep -qE '\-dev\-[0-9]'; then
    if [ -z "$LAST_DEV_VERSION" ]; then
      LAST_DEV_VERSION="$version"
      LAST_DEV_DATE="$short_date"
    fi
  fi
done <<<"$HISTORY"

echo "─────────────────────────────────────────────────────────────"
echo ""

ALIAS_FROM="${FAKE_VERSION:-<fake-ide-version>}"

# Determine the ij label's base version (e.g., 2.3.20 from 2.3.20-ij253-87)
IJ_BASE=""
if [ -n "$LATEST_IJ_VERSION" ]; then
  IJ_BASE=$(echo "$LATEST_IJ_VERSION" | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+')
fi

# Print version history summary
echo "Version history (intellij-community):"
if [ -n "$LAST_DEV_VERSION" ]; then
  echo "  Last dev build consumed:  $LAST_DEV_VERSION ($LAST_DEV_DATE)"
fi
if [ -n "$FIRST_IJ_VERSION" ]; then
  echo "  First ij build consumed:  $FIRST_IJ_VERSION ($FIRST_IJ_DATE)"
fi
if [ -n "$LATEST_IJ_VERSION" ]; then
  echo "  Latest ij build consumed: $LATEST_IJ_VERSION ($LATEST_IJ_DATE)"
fi
echo ""

# Find the best dev build to alias to.
# We want the nearest dev build whose base version matches the ij label.
# 1. Check the branch history for a dev build with matching base
# 2. If none found, search master for the nearest one
SUGGESTED_VERSION=""

if [ -n "$LAST_DEV_VERSION" ]; then
  DEV_BASE=$(echo "$LAST_DEV_VERSION" | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+')

  if [ -z "$IJ_BASE" ] || [ "$IJ_BASE" = "$DEV_BASE" ]; then
    # Dev builds in the branch match the ij label — use directly
    SUGGESTED_VERSION="$LAST_DEV_VERSION"
  else
    echo "Note: The ij builds label themselves as $IJ_BASE, but the branch"
    echo "diverged from $LAST_DEV_VERSION (base $DEV_BASE)."
    echo ""
  fi
fi

# If no matching dev build found in branch history, search master
if [ -z "$SUGGESTED_VERSION" ] && [ -n "$IJ_BASE" ]; then
  echo "Searching master for nearest $IJ_BASE-dev-* build..."

  MASTER_HISTORY=""
  if ! MASTER_HISTORY=$(fetch_history "master"); then
    MASTER_HISTORY=""
  fi

  if [ -n "$MASTER_HISTORY" ]; then
    # Find dev builds on master with matching base version, closest to the
    # first ij build date. History is newest-first, so we collect all matching
    # dev builds and pick the one nearest in time.
    BEST_DEV=""
    BEST_DATE=""

    while IFS=$'\t' read -r mdate mmessage; do
      mversion=$(echo "$mmessage" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+-[a-zA-Z0-9._-]+' | head -1 || true)
      if [ -z "$mversion" ]; then
        continue
      fi
      # Only consider dev builds with matching base
      if echo "$mversion" | grep -qE "^${IJ_BASE}-dev-[0-9]"; then
        mshort_date=$(echo "$mdate" | cut -dT -f1)
        # Take the last one we see that's <= first ij date (history is newest-first,
        # so we want the most recent dev build before the ij branch started)
        if [ -n "$FIRST_IJ_DATE" ] && [[ "$mshort_date" < "$FIRST_IJ_DATE" || "$mshort_date" = "$FIRST_IJ_DATE" ]]; then
          BEST_DEV="$mversion"
          BEST_DATE="$mshort_date"
          break
        fi
        # Track in case all are after the ij date (use the oldest/last one)
        BEST_DEV="$mversion"
        BEST_DATE="$mshort_date"
      fi
    done <<<"$MASTER_HISTORY"

    if [ -n "$BEST_DEV" ]; then
      echo "Found $BEST_DEV ($BEST_DATE) on master"
      echo ""
      SUGGESTED_VERSION="$BEST_DEV"
    fi
  fi
fi

if [ -n "$SUGGESTED_VERSION" ]; then
  echo "Suggested alias (based on nearest matching dev build):"
  echo "  \"$ALIAS_FROM\" to \"$SUGGESTED_VERSION\""
elif [ -n "$IJ_BASE" ]; then
  echo "Could not find a matching dev build for $IJ_BASE."
  echo "Falling back to ij version label for aliasing."
  echo ""
  echo "Suggested alias (based on ij version label):"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
else
  echo "No dev or ij builds found in history."
fi
