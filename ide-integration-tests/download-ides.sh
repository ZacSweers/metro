#!/usr/bin/env bash
# Downloads IDE builds for integration testing.
# Supports parallel downloads, resume, and uses aria2c when available for speed.
#
# Usage: ./download-ides.sh [options]
#
# Options:
#   -j, --jobs N     Number of parallel downloads (default: 4)
#   -f, --force      Re-download even if file exists
#   -n, --dry-run    Show what would be downloaded without downloading
#   -q, --quiet      Minimal output
#   -h, --help       Show this help
#
# Reads ide-versions.txt and downloads IDEs to the appropriate cache locations.

set -euo pipefail

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Configuration
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSIONS_FILE="$SCRIPT_DIR/ide-versions.txt"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Cache directories (matching IDE Starter expectations)
AS_CACHE_DIR="$REPO_ROOT/out/ide-tests/cache/android-studio"
IU_CACHE_DIR="$REPO_ROOT/out/ide-tests/installers/IU"

# Default options
PARALLEL_JOBS=4
FORCE_DOWNLOAD=false
DRY_RUN=false
QUIET=false

# Colors (if terminal supports them)
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[0;33m'
  BLUE='\033[0;34m'
  CYAN='\033[0;36m'
  BOLD='\033[1m'
  DIM='\033[2m'
  RESET='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' DIM='' RESET=''
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Platform Detection
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

detect_platform() {
  case "$(uname -s)" in
    Darwin)
      if [[ "$(uname -m)" == "arm64" ]]; then
        AS_PATH_TYPE="install"
        AS_PLATFORM_SUFFIX="mac_arm.dmg"
        IU_PLATFORM_SUFFIX="-aarch64.dmg"
      else
        AS_PATH_TYPE="install"
        AS_PLATFORM_SUFFIX="mac.dmg"
        IU_PLATFORM_SUFFIX=".dmg"
      fi
      ;;
    Linux)
      AS_PATH_TYPE="ide-zips"
      AS_PLATFORM_SUFFIX="linux.tar.gz"
      IU_PLATFORM_SUFFIX=".tar.gz"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      AS_PATH_TYPE="install"
      AS_PLATFORM_SUFFIX="windows.exe"
      IU_PLATFORM_SUFFIX=".exe"
      ;;
    *)
      echo -e "${RED}Unsupported platform: $(uname -s)${RESET}"
      exit 1
      ;;
  esac
}

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Download Infrastructure
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

HAS_ARIA2=false
if command -v aria2c &>/dev/null; then
  HAS_ARIA2=true
fi

# Temp file for aria2 input
ARIA2_INPUT=""

cleanup() {
  [[ -n "$ARIA2_INPUT" && -f "$ARIA2_INPUT" ]] && rm -f "$ARIA2_INPUT"
  return 0
}
trap cleanup EXIT INT TERM

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# IDE-Specific Download Logic
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

# Get Android Studio download info
# Returns: url|target_dir|filename|display_name (or returns 1 if already exists)
get_as_download_info() {
  local version="$1"
  local filename_prefix="$2"

  local filename="android-studio-${version}-${AS_PLATFORM_SUFFIX}"
  local target_path="$AS_CACHE_DIR/$filename"

  if [[ -f "$target_path" ]] && ! $FORCE_DOWNLOAD; then
    # Touch to refresh mtime — IDE Starter's HttpClient.downloadIfMissing considers
    # files older than 24h stale and re-downloads (with a URL that 404s for preview builds).
    touch "$target_path"
    return 1
  fi

  local url
  if [[ -n "$filename_prefix" ]]; then
    url="https://dl.google.com/android/studio/${AS_PATH_TYPE}/${version}/${filename_prefix}-${AS_PLATFORM_SUFFIX}"
  else
    url="https://dl.google.com/android/studio/${AS_PATH_TYPE}/${version}/android-studio-${version}-${AS_PLATFORM_SUFFIX}"
  fi

  echo "${url}|${AS_CACHE_DIR}|${filename}|Android Studio ${version}"
}

# Get IntelliJ IDEA download info
# IDE Starter uses build numbers in filenames, so we query the JetBrains API
# to map marketing version (2025.3.2) -> build number (253.30387.90)
get_iu_download_info() {
  local version="$1"

  # Query JetBrains API to get build number for this version
  local api_url="https://data.services.jetbrains.com/products/releases?code=IU&type=release"
  local api_response
  api_response=$(curl -sL "$api_url") || return 1

  # Extract build number for this version (format: "build": "253.30387.90")
  # The API returns releases sorted by version, find our version
  local build_number
  build_number=$(echo "$api_response" | grep -o "\"version\":\"${version}\"[^}]*\"build\":\"[^\"]*\"" | grep -o '"build":"[^"]*"' | cut -d'"' -f4 | head -1)

  if [[ -z "$build_number" ]]; then
    echo "Warning: Could not find build number for IU $version" >&2
    return 1
  fi

  # IDE Starter expects: ideaIU-{buildNumber}-{platform}.dmg
  local filename="ideaIU-${build_number}${IU_PLATFORM_SUFFIX}"
  local target_path="$IU_CACHE_DIR/$filename"

  if [[ -f "$target_path" ]] && ! $FORCE_DOWNLOAD; then
    # Touch to refresh mtime — IDE Starter considers files older than 24h stale.
    touch "$target_path"
    return 1
  fi

  # Download URL uses marketing version: idea-{version}-{platform}.dmg
  local url="https://download.jetbrains.com/idea/idea-${version}${IU_PLATFORM_SUFFIX}"

  echo "${url}|${IU_CACHE_DIR}|${filename}|IntelliJ IDEA ${version}"
}

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Main
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

show_help() {
  sed -n '2,14p' "$0" | sed 's/^# //' | sed 's/^#//'
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -j|--jobs)
        PARALLEL_JOBS="$2"
        shift 2
        ;;
      -f|--force)
        FORCE_DOWNLOAD=true
        shift
        ;;
      -n|--dry-run)
        DRY_RUN=true
        shift
        ;;
      -q|--quiet)
        QUIET=true
        shift
        ;;
      -h|--help)
        show_help
        exit 0
        ;;
      *)
        echo -e "${RED}Unknown option: $1${RESET}"
        show_help
        exit 1
        ;;
    esac
  done
}

main() {
  parse_args "$@"
  detect_platform

  # Header
  if ! $QUIET; then
    echo -e "${BOLD}IDE Download Manager${RESET}"
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    if $HAS_ARIA2; then
      echo -e "  ${GREEN}●${RESET} Using aria2c (16 connections per file)"
    else
      echo -e "  ${YELLOW}●${RESET} Using curl (install aria2 for faster downloads)"
    fi
    echo -e "  ${BLUE}●${RESET} Platform: ${BOLD}$(uname -s) $(uname -m)${RESET}"
    echo ""
  fi

  # Create cache directories
  mkdir -p "$AS_CACHE_DIR" "$IU_CACHE_DIR"

  # Collect downloads needed
  declare -a DOWNLOADS=()
  local already_cached=0

  while IFS= read -r line; do
    [[ -z "$line" || "$line" =~ ^# ]] && continue

    # Strip inline comments
    line="${line%%#*}"
    line="${line%"${line##*[![:space:]]}"}"

    IFS=':' read -r product version filename_prefix <<< "$line"

    local info=""
    case "$product" in
      AS)
        info=$(get_as_download_info "$version" "${filename_prefix:-}") || {
          already_cached=$((already_cached + 1))
          $QUIET || echo -e "  ${DIM}✓ Android Studio $version (cached)${RESET}"
          continue
        }
        ;;
      IU)
        info=$(get_iu_download_info "$version") || {
          already_cached=$((already_cached + 1))
          $QUIET || echo -e "  ${DIM}✓ IntelliJ IDEA $version (cached)${RESET}"
          continue
        }
        ;;
      *)
        echo -e "  ${YELLOW}⚠${RESET} Unknown product: $product"
        continue
        ;;
    esac

    DOWNLOADS+=("$info")
  done < "$VERSIONS_FILE"

  local downloads_needed=${#DOWNLOADS[@]}

  if [[ $downloads_needed -eq 0 ]]; then
    $QUIET || echo -e "\n${GREEN}All IDEs already cached.${RESET}"
    exit 0
  fi

  # Show what will be downloaded
  if ! $QUIET; then
    echo ""
    for info in "${DOWNLOADS[@]}"; do
      IFS='|' read -r url dir filename name <<< "$info"
      if $DRY_RUN; then
        echo -e "  ${CYAN}Would download:${RESET} $name"
        echo -e "    ${DIM}$url${RESET}"
      else
        echo -e "  ${BLUE}↓${RESET} $name"
      fi
    done
    echo ""
  fi

  if $DRY_RUN; then
    echo -e "${BOLD}Dry run:${RESET} $downloads_needed would be downloaded, $already_cached cached"
    exit 0
  fi

  # Download using aria2 or curl
  if $HAS_ARIA2; then
    # Create input file for aria2c with explicit gids for readable progress
    # gid must be 16 hex chars - we use a simple counter padded with zeros
    ARIA2_INPUT=$(mktemp)
    local gid_counter=1
    local -a gid_names=()

    for info in "${DOWNLOADS[@]}"; do
      IFS='|' read -r url dir filename name <<< "$info"
      # Create a 16-char hex gid from counter (e.g., 0000000000000001)
      local gid=$(printf "%016x" $gid_counter)
      echo "$url"
      echo "  dir=$dir"
      echo "  out=$filename"
      echo "  gid=$gid"
      gid_names+=("$gid:$name")
      ((gid_counter++))
    done > "$ARIA2_INPUT"

    # Print legend so users can identify downloads in progress
    echo -e "  ${DIM}Progress key:${RESET}"
    for entry in "${gid_names[@]}"; do
      IFS=':' read -r gid name <<< "$entry"
      # Show shortened gid (last 6 chars) which is what aria2 displays
      local short_gid="${gid: -6}"
      echo -e "    ${DIM}#$short_gid = $name${RESET}"
    done
    echo ""

    # Run aria2c with all downloads
    # --summary-interval=0 disables the periodic summary blocks
    # The default console readout shows a single updating progress line
    aria2c \
      --input-file="$ARIA2_INPUT" \
      --max-concurrent-downloads="$PARALLEL_JOBS" \
      --max-connection-per-server=16 \
      --min-split-size=1M \
      --split=16 \
      --continue=true \
      --auto-file-renaming=false \
      --console-log-level=warn \
      --summary-interval=0 \
      --human-readable=true

    rm -f "$ARIA2_INPUT"
    ARIA2_INPUT=""
  else
    # Fallback: download sequentially with curl
    for info in "${DOWNLOADS[@]}"; do
      IFS='|' read -r url dir filename name <<< "$info"
      local target="$dir/$filename"

      echo -e "  Downloading $name..."
      curl -L --continue-at - --progress-bar --fail -o "$target" "$url"
      echo -e "  ${GREEN}✓${RESET} $name"
    done
  fi

  # Summary
  if ! $QUIET; then
    echo ""
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo -e "${GREEN}${BOLD}Complete:${RESET} $downloads_needed downloaded, $already_cached cached"
  fi
}

main "$@"
