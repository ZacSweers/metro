#!/bin/bash

# This script cleans up old versioned mkdocs site by keeping:
# 1. Only the LATEST snapshot version (not one per major.minor series)
# 2. The latest patch version for each major.minor series (for stable releases)
# 3. The latest pre-release (RC/alpha/beta) per series, ONLY if no stable release exists yet
#
# Example cleanup given the following versioned sites from `mike list`:
# - "0.8.0-SNAPSHOT [snapshot]" <-- keep ✅ (latest snapshot)
# - "0.7.0 [latest]" <-- keep ✅ (latest 0.7.x release)
# - "0.7.0-SNAPSHOT" <-- deleted ❌ (older snapshot)
# - "0.6.2" <-- keep ✅ (latest 0.6.x release)
# - "0.6.1" <-- deleted ❌ (older 0.6.x release)
# - "0.6.0" <-- deleted ❌ (older 0.6.x release)
# - "0.6.0-SNAPSHOT" <-- deleted ❌ (older snapshot)
# - "0.5.5" <-- keep ✅ (latest 0.5.x release)
# - "0.5.4" <-- deleted ❌ (older 0.5.x release)
# - "1.0.0 [latest]" <-- keep ✅ (stable release)
# - "1.0.0-RC4" <-- deleted ❌ (pre-release superseded by stable 1.0.0)
#
# Note: This script is adapted from `https://github.com/chrisbanes/haze` repository.


# Check if mike is installed
if ! command -v mike &> /dev/null; then
    echo "Error: mike is not installed."
    echo "Please install mike using: pip install mike"
    exit 0
fi

# Get list of existing versioned mkdocs sites, filtering out labels like [snapshot], [latest]
versions=()
while read -r line; do
  # Extract just the version number (first field)
  version=$(echo "$line" | awk '{print $1}')
  if [[ -n "$version" ]]; then
    versions+=("$version")
  fi
done < <(mike list)

# Separate into three buckets:
#   snapshots   : *-SNAPSHOT
#   releases    : stable X.Y.Z (no suffix) — these "own" their X.Y series
#   prereleases : everything else (-RC*, -alpha, -beta, etc.)
snapshots=()
releases=()
prereleases=()
for v in "${versions[@]}"; do
  if [[ "$v" == *"-SNAPSHOT" ]]; then
    snapshots+=("$v")
  elif [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    releases+=("$v")
  else
    prereleases+=("$v")
  fi
done

# Find the single latest snapshot across all versions
latest_snapshot=""
if [[ ${#snapshots[@]} -gt 0 ]]; then
  latest_snapshot=$(printf "%s\n" "${snapshots[@]}" | sort -V | tail -n1)
fi

# Find latest stable release per X.Y series
declare -A major_latest
for v in "${releases[@]}"; do
  major="${v%.*}"
  if [[ -z "${major_latest[$major]}" ]]; then
    major_latest[$major]="$v"
  else
    latest="${major_latest[$major]}"
    # Use version sort (-V) to compare
    greater=$(printf "%s\n%s\n" "$latest" "$v" | sort -V | tail -n1)
    major_latest[$major]="$greater"
  fi
done

# Find latest pre-release per X.Y series, but only when no stable release exists for that series.
# Once a stable release ships, all pre-releases for the same series are deleted.
declare -A pre_latest
for v in "${prereleases[@]}"; do
  base="${v%-*}"     # e.g. 1.0.0-RC4 → 1.0.0
  major="${base%.*}" # e.g. 1.0.0 → 1.0
  if [[ -z "${major_latest[$major]}" ]]; then
    if [[ -z "${pre_latest[$major]}" ]]; then
      pre_latest[$major]="$v"
    else
      pre_latest[$major]=$(printf "%s\n%s\n" "${pre_latest[$major]}" "$v" | sort -V | tail -n1)
    fi
  fi
done

# Build list of versions to keep
keep=()
if [[ -n "$latest_snapshot" ]]; then
  keep+=("$latest_snapshot")
fi
for v in "${major_latest[@]}"; do
  keep+=("$v")
done
for v in "${pre_latest[@]}"; do
  keep+=("$v")
done

# Determine what to delete (everything not in keep list)
to_delete=()
for v in "${versions[@]}"; do
  should_keep=false
  for k in "${keep[@]}"; do
    if [[ "$v" == "$k" ]]; then
      should_keep=true
      break
    fi
  done
  if [[ "$should_keep" == "false" ]]; then
    to_delete+=("$v")
  fi
done

if [[ ${#to_delete[@]} -eq 0 ]]; then
  echo "No cleanup required - all versions are already the latest for their respective series"
else
  for v in "${to_delete[@]}"; do
    echo "Cleaning up old versioned site - deleting $v"
    mike delete "$v" --push
  done
fi
