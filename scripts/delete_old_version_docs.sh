#!/bin/bash

# This script cleans up old versioned mkdocs site by keeping only the latest
# patch version for each major.minor series. 
#
# Example cleanup given the following versioned sites from `mike list`:
# - "0.7.0-SNAPSHOT [snapshot]" <-- keep ✅
# - "0.6.2 [latest]" <-- keep ✅
# - "0.6.1" <-- deleted ❌
# - "0.6.0" <-- deleted ❌
# - "0.6.0-SNAPSHOT" <-- deleted ❌
# - "0.5.5" <-- keep ✅
# - "0.5.4" <-- deleted ❌
#
# Note: This script is adapted from `https://github.com/chrisbanes/haze` repository.


# Check if mike is installed
if ! command -v mike &> /dev/null; then
    echo "Error: mike is not installed."
    echo "Please install mike using: pip install mike"
    exit 0
fi

# Get list of existing versioned mkdocs sites
versions=($(mike list | tr ' ' '\n'))

declare -A major_latest

# Find latest per X.Y
for v in "${versions[@]}"; do
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

to_delete=()
for v in "${versions[@]}"; do
  major="${v%.*}"
  if [[ "$v" != "${major_latest[$major]}" ]]; then
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
