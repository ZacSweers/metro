#!/bin/bash

# Resolves the Kotlin dev build that an IntelliJ ij-specific Kotlin version branched from.
#
# Given an IntelliJ version (e.g., 252.28238.7), this script:
# 1. Fetches the Kotlin version from intellij-community for that platform
# 2. Finds the corresponding build tag in JetBrains/kotlin
# 3. Uses git ancestry (merge-base) to find the dev build the ij branch diverged from
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

echo "Resolving Kotlin version for IntelliJ $INTELLIJ_VERSION..."
echo ""

# Check that gh is available
if ! command -v gh &>/dev/null; then
  echo "Error: 'gh' (GitHub CLI) is required but not found."
  echo "Install it from https://cli.github.com/"
  exit 1
fi

# Fetch the Kotlin version from a given intellij-community tag
fetch_kotlin_version() {
  local tag="$1"
  local url="https://raw.githubusercontent.com/JetBrains/intellij-community/$tag/$FILE_PATH"
  local content
  content=$(curl -sL "$url" 2>/dev/null) || return 1
  echo "$content" | grep -oE 'kotlin-compiler-common-for-ide:([^"]+)' | head -1 | sed 's/.*://'
}

# Try exact tag first
KOTLIN_VERSION=""
EXACT_TAG_MATCH=true
KOTLIN_VERSION=$(fetch_kotlin_version "$TAG") || true

if [ -z "$KOTLIN_VERSION" ]; then
  # Exact tag not found — try build series first (e.g., 253.30387.*)
  PLATFORM_MAJOR=$(echo "$INTELLIJ_VERSION" | cut -d. -f1)
  BUILD_MIDDLE=$(echo "$INTELLIJ_VERSION" | cut -d. -f2)
  BUILD_SERIES="$PLATFORM_MAJOR.$BUILD_MIDDLE"

  echo "Tag '$TAG' not found, searching for nearest idea/$BUILD_SERIES.* tag..."

  SERIES_TAG=$(gh api \
    "repos/JetBrains/intellij-community/git/matching-refs/tags/idea/$BUILD_SERIES." \
    -q '.[].ref' 2>/dev/null |
    sed 's|refs/tags/||' |
    sort -t. -k1,1n -k2,2n -k3,3n |
    tail -1 || true)

  if [ -n "$SERIES_TAG" ]; then
    TAG="$SERIES_TAG"
    echo "Using build series tag: $TAG"
    KOTLIN_VERSION=$(fetch_kotlin_version "$TAG") || true
  fi

  # If still not found, fall back to platform major (e.g., 253.*)
  if [ -z "$KOTLIN_VERSION" ]; then
    EXACT_TAG_MATCH=false
    echo "No build series tag found, searching for nearest idea/$PLATFORM_MAJOR.* tag..."

    NEAREST_TAG=$(gh api \
      "repos/JetBrains/intellij-community/git/matching-refs/tags/idea/$PLATFORM_MAJOR." \
      -q '.[].ref' 2>/dev/null |
      sed 's|refs/tags/||' |
      sort -t. -k1,1n -k2,2n -k3,3n |
      tail -1 || true)

    if [ -n "$NEAREST_TAG" ]; then
      TAG="$NEAREST_TAG"
      echo "Using nearest tag: $TAG"
      KOTLIN_VERSION=$(fetch_kotlin_version "$TAG") || true
    fi
  fi

  if [ -z "$KOTLIN_VERSION" ]; then
    echo "Error: Could not fetch Kotlin version for any idea/$PLATFORM_MAJOR.* tag."
    exit 1
  fi
fi

echo "Platform tag: $TAG"
echo "Kotlin version: $KOTLIN_VERSION"
if [ "$EXACT_TAG_MATCH" = false ]; then
  echo ""
  echo "WARNING: Used fallback tag. The Kotlin version above is from a different"
  echo "IntelliJ build. The actual IDE may report a different -ij build number."
  echo "Check the IDE's bundled Kotlin version using: ./extract-kotlin-compiler-txt.sh"
fi
echo ""

# Extract components from the Kotlin version
# e.g., "2.2.20-ij252-24" -> base="2.2.20", platform="252", build="24"
IJ_BASE=$(echo "$KOTLIN_VERSION" | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+')

ALIAS_FROM="${FAKE_VERSION:-<fake-ide-version>}"

# If it's already a dev build, no resolution needed
if echo "$KOTLIN_VERSION" | grep -qE '\-dev\-[0-9]+$'; then
  echo "Already a dev build, no aliasing needed."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$KOTLIN_VERSION\""
  exit 0
fi

# If it's not an ij build, just use the base version
if ! echo "$KOTLIN_VERSION" | grep -qE '\-ij[0-9]+-[0-9]+$'; then
  echo "Not an -ij build, using base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

# Extract platform and build number from ij version
# e.g., "2.2.20-ij252-24" -> platform="252", build="24"
IJ_PLATFORM=$(echo "$KOTLIN_VERSION" | grep -oE '\-ij([0-9]+)\-' | sed 's/-ij//' | sed 's/-//')
IJ_BUILD=$(echo "$KOTLIN_VERSION" | grep -oE '\-ij[0-9]+-([0-9]+)$' | grep -oE '[0-9]+$')

echo "Resolving via git ancestry in JetBrains/kotlin..."
echo "  Base version: $IJ_BASE"
echo "  Platform: $IJ_PLATFORM"
echo "  Build: $IJ_BUILD"
echo ""

# Find the matching build tag in JetBrains/kotlin
# Tags are like "build-2.2.20-ij252-24" or "build-2.2.20-ij252-25"
IJ_TAG_PREFIX="build-$IJ_BASE-ij$IJ_PLATFORM-"

echo "Looking for tags matching: ${IJ_TAG_PREFIX}*"

# Get all matching ij tags and find the best match
IJ_TAGS=$(gh api \
  "repos/JetBrains/kotlin/git/matching-refs/tags/$IJ_TAG_PREFIX" \
  -q '.[].ref' 2>/dev/null |
  sed 's|refs/tags/||' || true)

if [ -z "$IJ_TAGS" ]; then
  echo "No matching ij tags found in JetBrains/kotlin."
  echo "Falling back to base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

# Find the tag with the closest build number (exact match or next higher)
BEST_IJ_TAG=""
BEST_DIFF=999999

while read -r tag; do
  tag_build=$(echo "$tag" | grep -oE '[0-9]+$')
  if [ -n "$tag_build" ]; then
    diff=$((tag_build - IJ_BUILD))
    # Prefer exact match (diff=0), then closest higher (diff>0), then closest lower
    if [ $diff -eq 0 ]; then
      BEST_IJ_TAG="$tag"
      break
    elif [ $diff -gt 0 ] && [ $diff -lt $BEST_DIFF ]; then
      BEST_DIFF=$diff
      BEST_IJ_TAG="$tag"
    elif [ -z "$BEST_IJ_TAG" ]; then
      # Take any match if we haven't found one yet
      BEST_IJ_TAG="$tag"
      BEST_DIFF=${diff#-}  # absolute value
    fi
  fi
done <<<"$IJ_TAGS"

if [ -z "$BEST_IJ_TAG" ]; then
  echo "Could not find a suitable ij tag."
  echo "Falling back to base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

echo "Found ij tag: $BEST_IJ_TAG"

# Find the merge base between master and the ij tag
echo "Finding merge base with master..."

MERGE_BASE=$(gh api \
  "repos/JetBrains/kotlin/compare/master...$BEST_IJ_TAG" \
  -q '.merge_base_commit.sha' 2>/dev/null || true)

if [ -z "$MERGE_BASE" ]; then
  echo "Could not determine merge base."
  echo "Falling back to base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

echo "Merge base: ${MERGE_BASE:0:10}"

# Find dev tags with matching base version
DEV_TAG_PREFIX="build-$IJ_BASE-dev-"
echo "Looking for dev tags matching: ${DEV_TAG_PREFIX}*"

DEV_TAGS=$(gh api \
  "repos/JetBrains/kotlin/git/matching-refs/tags/$DEV_TAG_PREFIX" \
  -q '.[].ref' 2>/dev/null |
  sed 's|refs/tags/||' || true)

if [ -z "$DEV_TAGS" ]; then
  echo "No dev tags found for $IJ_BASE."
  echo "Falling back to base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

# Extract build numbers and sort them numerically
DEV_NUMS=$(echo "$DEV_TAGS" | while read -r tag; do
  echo "$tag" | grep -oE '[0-9]+$'
done | sort -n | uniq)

# Convert to array for binary search
DEV_NUMS_ARRAY=($DEV_NUMS)
NUM_TAGS=${#DEV_NUMS_ARRAY[@]}

echo "Found $NUM_TAGS dev tags, using binary search..."
echo ""

# Binary search to find the highest dev tag that is an ancestor of merge base
# A tag is "good" if it's identical to or an ancestor of the merge base
check_tag() {
  local num="$1"
  local tag="build-$IJ_BASE-dev-$num"
  local result
  result=$(gh api \
    "repos/JetBrains/kotlin/compare/$tag...$MERGE_BASE" \
    -q '.status' 2>/dev/null || echo "error")

  if [ "$result" = "identical" ] || [ "$result" = "ahead" ]; then
    # Tag is at or before merge base (good)
    echo "ancestor"
  elif [ "$result" = "behind" ]; then
    # Tag is ahead of merge base (too new)
    echo "too_new"
  else
    echo "error"
  fi
}

# Binary search: find the highest tag number where check_tag returns "ancestor"
low=0
high=$((NUM_TAGS - 1))
best_num=""

while [ $low -le $high ]; do
  mid=$(( (low + high) / 2 ))
  num="${DEV_NUMS_ARRAY[$mid]}"

  result=$(check_tag "$num")
  echo "  Checking dev-$num: $result"

  if [ "$result" = "ancestor" ]; then
    # This tag is good, but there might be a higher one
    best_num="$num"
    low=$((mid + 1))
  elif [ "$result" = "too_new" ]; then
    # This tag is too new, search lower
    high=$((mid - 1))
  else
    # Error - try to continue anyway
    high=$((mid - 1))
  fi
done

# If no match found with the ij label's base version, try related versions.
# The ij label (e.g., 2.3.20-ij253-105) might actually branch from a different
# version's dev track (e.g., 2.3.0-dev-9992).
if [ -z "$best_num" ]; then
  echo ""
  echo "No matching dev tag found for $IJ_BASE, trying related versions..."

  # Extract major.minor from the base (e.g., "2.3" from "2.3.20")
  MAJOR_MINOR=$(echo "$IJ_BASE" | grep -oE '^[0-9]+\.[0-9]+')

  # Try X.Y.0 if the base is X.Y.Z where Z > 0
  PATCH=$(echo "$IJ_BASE" | grep -oE '[0-9]+$')
  if [ "$PATCH" != "0" ]; then
    ALT_BASE="${MAJOR_MINOR}.0"
    ALT_DEV_TAG_PREFIX="build-$ALT_BASE-dev-"
    echo "Looking for dev tags matching: ${ALT_DEV_TAG_PREFIX}*"

    ALT_DEV_TAGS=$(gh api \
      "repos/JetBrains/kotlin/git/matching-refs/tags/$ALT_DEV_TAG_PREFIX" \
      -q '.[].ref' 2>/dev/null |
      sed 's|refs/tags/||' || true)

    if [ -n "$ALT_DEV_TAGS" ]; then
      # Extract build numbers and sort numerically for binary search
      ALT_DEV_NUMS=$(echo "$ALT_DEV_TAGS" | while read -r tag; do
        echo "$tag" | grep -oE '[0-9]+$'
      done | sort -n | uniq)

      ALT_DEV_NUMS_ARRAY=($ALT_DEV_NUMS)
      ALT_NUM_TAGS=${#ALT_DEV_NUMS_ARRAY[@]}
      echo "Found $ALT_NUM_TAGS dev tags for $ALT_BASE, using binary search..."

      # Binary search on alternative base
      alt_low=0
      alt_high=$((ALT_NUM_TAGS - 1))

      while [ $alt_low -le $alt_high ]; do
        alt_mid=$(( (alt_low + alt_high) / 2 ))
        alt_num="${ALT_DEV_NUMS_ARRAY[$alt_mid]}"
        alt_tag="build-$ALT_BASE-dev-$alt_num"

        alt_result=$(gh api \
          "repos/JetBrains/kotlin/compare/$alt_tag...$MERGE_BASE" \
          -q '.status' 2>/dev/null || echo "error")

        echo "  Checking $ALT_BASE-dev-$alt_num: $alt_result"

        if [ "$alt_result" = "identical" ] || [ "$alt_result" = "ahead" ]; then
          best_num="$alt_num"
          IJ_BASE="$ALT_BASE"
          alt_low=$((alt_mid + 1))
        elif [ "$alt_result" = "behind" ]; then
          alt_high=$((alt_mid - 1))
        else
          alt_high=$((alt_mid - 1))
        fi
      done
    fi
  fi
fi

if [ -z "$best_num" ]; then
  echo ""
  echo "Could not find a dev tag at or before the merge base."
  echo "Falling back to base version for aliasing."
  echo ""
  echo "Suggested alias:"
  echo "  \"$ALIAS_FROM\" to \"$IJ_BASE\""
  exit 0
fi

BEST_DEV_TAG="build-$IJ_BASE-dev-$best_num"
BEST_DEV_VERSION="$IJ_BASE-dev-$best_num"

# Verify the result
echo ""
echo "Verifying result..."
final_check=$(check_tag "$best_num")
echo "  $BEST_DEV_TAG: $final_check"

echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""
echo "Resolution summary:"
echo "  IntelliJ platform:  $TAG"
echo "  Kotlin ij version:  $KOTLIN_VERSION"
echo "  Kotlin ij tag:      $BEST_IJ_TAG"
echo "  Merge base:         ${MERGE_BASE:0:10}"
echo "  Dev tag:            $BEST_DEV_TAG"
echo "  Dev version:        $BEST_DEV_VERSION"
echo ""
if [ "$EXACT_TAG_MATCH" = true ]; then
  echo "Suggested alias (based on git ancestry):"
  echo "  \"$KOTLIN_VERSION\" to \"$BEST_DEV_VERSION\""
else
  echo "Dev build resolved, but exact Kotlin version unknown (used fallback tag)."
  echo "To create an alias, first find the actual Kotlin version in your IDE using:"
  echo "  ./extract-kotlin-compiler-txt.sh \"/path/to/IntelliJ IDEA.app\""
  echo ""
  echo "Then add an alias like:"
  echo "  \"<actual-ij-version>\" to \"$BEST_DEV_VERSION\""
fi
echo ""
