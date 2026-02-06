#!/usr/bin/env bash
# Lists recent Android Studio versions from JetBrains releases XML.
# Useful for finding version numbers when adding new AS versions to test.
#
# Usage: ./list-android-studio-versions.sh

set -euo pipefail

RELEASES_URL="https://jb.gg/android-studio-releases-list.xml"

echo "Fetching Android Studio releases..."
echo ""

# Fetch XML (follow redirects)
xml_content=$(curl -sL "$RELEASES_URL")

# Parse items using awk - extract version, channel, name, and mac_arm filename
# Use tab as delimiter to avoid conflicts with | in name field
parse_all() {
  echo "$xml_content" | awk '
    /<item>/ { in_item=1; name=""; version=""; channel=""; filename="" }
    /<\/item>/ {
      if (in_item && version != "") {
        print channel "\t" version "\t" name "\t" filename
      }
      in_item=0
    }
    in_item && /<name>/ {
      gsub(/.*<name>/, ""); gsub(/<\/name>.*/, ""); name=$0
    }
    in_item && /<version>/ {
      gsub(/.*<version>/, ""); gsub(/<\/version>.*/, ""); version=$0
    }
    in_item && /<channel>/ {
      gsub(/.*<channel>/, ""); gsub(/<\/channel>.*/, ""); channel=$0
    }
    in_item && /mac_arm\.dmg/ {
      # Extract filename from link
      match($0, /[^\/]+mac_arm\.dmg/)
      if (RSTART > 0) {
        filename = substr($0, RSTART, RLENGTH)
        gsub(/-mac_arm\.dmg$/, "", filename)
      }
    }
  '
}

all_items=$(parse_all)

print_channel() {
  local channel="$1"
  local display_name="$2"
  local count="${3:-3}"

  echo "## $display_name"
  echo ""

  items=$(echo "$all_items" | grep "^${channel}	" | head -n "$count")

  if [[ -z "$items" ]]; then
    echo "  (none found)"
    echo ""
    return
  fi

  while IFS=$'\t' read -r ch version name filename; do
    # Extract short display name: "Android Studio Panda 1 | 2025.3.1 RC 1" -> "Panda 1 RC 1"
    short_name="${name#Android Studio }"  # "Panda 1 | 2025.3.1 RC 1"
    codename="${short_name%% |*}"          # "Panda 1"
    marketing="${short_name##* | }"        # "2025.3.1 RC 1"
    prerelease=$(echo "$marketing" | sed 's/^[0-9.]*[[:space:]]*//')  # "RC 1" or ""
    if [ -n "$prerelease" ]; then
      display="$codename $prerelease"
    else
      display="$codename"
    fi

    echo "  $name"
    echo "    Version: $version"
    if [[ "$channel" != "Release" && -n "$filename" ]]; then
      echo "    ide-versions.txt: AS:$version:$filename # $display"
    else
      echo "    ide-versions.txt: AS:$version # $display"
    fi
    echo ""
  done <<< "$items"
}

echo "Recent Android Studio Versions"
echo "==============================="
echo ""

print_channel "Release" "Stable" 3
print_channel "RC" "Release Candidate" 3
print_channel "Beta" "Beta" 3
print_channel "Canary" "Canary" 3
