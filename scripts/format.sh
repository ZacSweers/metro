#!/bin/bash
set -eo pipefail

# Unified code formatter: license headers, ktfmt, gjf, trailing whitespace.
# Replaces Spotless for fast local formatting and CI checks.
#
# Usage:
#   scripts/format.sh              Format staged files
#   scripts/format.sh --check      Check mode (CI) — exits non-zero if changes needed
#   scripts/format.sh --all        Format all tracked files, not just staged
#
# Reads exclude lists from config/license-header-excludes-{kt,java}.txt

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CHECK_ONLY=false
ALL_FILES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=true; ALL_FILES=true; shift ;;
    --all) ALL_FILES=true; shift ;;
    --help|-h) echo "Usage: scripts/format.sh [--check] [--all]"; echo "  --check  Check mode (CI) — exits non-zero if changes needed"; echo "  --all    Format all tracked files, not just staged"; exit 0 ;;
    *) echo "Unknown flag: $1" >&2; exit 1 ;;
  esac
done

START_TIME=$(date +%s)
CURRENT_YEAR=$(date +%Y)
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
FAILURES=0

fail() {
  echo -e "${RED}$1${NC}" >&2
  FAILURES=$((FAILURES + 1))
}

ok() {
  echo -e "${GREEN}$1${NC}"
}

# --- Exclude list loading ---

load_excludes() {
  local file="$REPO_ROOT/config/$1"
  if [[ -f "$file" ]]; then
    grep -v '^#' "$file" | grep -v '^$' || true
  fi
}

KT_EXCLUDES=()
while IFS= read -r line; do [[ -n "$line" ]] && KT_EXCLUDES+=("$line"); done < <(load_excludes "license-header-excludes-kt.txt")
JAVA_EXCLUDES=()
while IFS= read -r line; do [[ -n "$line" ]] && JAVA_EXCLUDES+=("$line"); done < <(load_excludes "license-header-excludes-java.txt")

is_excluded() {
  local file="$1"
  local basename
  basename="$(basename "$file")"
  local patterns=()

  if [[ "$file" == *.kt || "$file" == *.kts ]]; then
    patterns=("${KT_EXCLUDES[@]}")
  elif [[ "$file" == *.java ]]; then
    patterns=("${JAVA_EXCLUDES[@]}")
  fi

  for pattern in "${patterns[@]}"; do
    local stripped="${pattern#\*\*/}"
    if [[ "$stripped" == "$pattern" ]]; then
      # shellcheck disable=SC2254
      case "$file" in $pattern) return 0 ;; esac
    else
      if [[ "$basename" == $stripped ]]; then
        return 0
      fi
      # shellcheck disable=SC2254
      case "$file" in */$stripped) return 0 ;; esac
      # shellcheck disable=SC2254
      case "$file" in $stripped) return 0 ;; esac
    fi
  done
  return 1
}

# --- File collection ---

# Only collect files matching expected source patterns, excluding build dirs.
is_valid_file() {
  local file="$1"
  # Exclude build directories
  case "$file" in build/* | */build/*) return 1 ;; esac

  # Exclude test data directories (test fixtures, not real source)
  case "$file" in */src/test/data/* | */src/*/test/data/*) return 1 ;; esac

  case "$file" in
    # Kotlin/Java source files must be in a src/ directory
    */src/*.kt | */src/*.java | src/*.kt | src/*.java) return 0 ;;
    # Gradle kts files at any level
    *.gradle.kts | *.main.kts) return 0 ;;
    *) return 1 ;;
  esac
}

collect_files() {
  local raw_files
  if $ALL_FILES; then
    raw_files=$(git -C "$REPO_ROOT" ls-files)
  else
    raw_files=$(git -C "$REPO_ROOT" diff --cached --name-only --diff-filter=ACMR)
  fi
  while IFS= read -r f; do
    [[ -n "$f" ]] && is_valid_file "$f" && echo "$f"
  done <<< "$raw_files"
}

src_kt_files=()
src_java_files=()
kts_files=()
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  is_excluded "$f" && continue
  case "$f" in
    *.kts) kts_files+=("$f") ;;
    *.kt) src_kt_files+=("$f") ;;
    *.java) src_java_files+=("$f") ;;
  esac
done < <(collect_files)

# --- License headers ---

has_header() {
  head -5 "$1" | grep -q "SPDX-License-Identifier: Apache-2.0"
}

header_delimiter() {
  local file="$1"
  case "$file" in
    *.kts) echo '^(@file:|import |plugins |buildscript |dependencies |pluginManagement|dependencyResolutionManagement)' ;;
    *.kt)  echo '^(package |@file:)' ;;
    *.java) echo '^package ' ;;
  esac
}

add_header() {
  local file="$1"
  local delimiter
  delimiter=$(header_delimiter "$file")
  local tmp
  tmp="$(mktemp)"
  local line_num
  line_num=$(grep -n -m1 -E "$delimiter" "$file" | cut -d: -f1) || true

  printf '// Copyright (C) %s Zac Sweers\n// SPDX-License-Identifier: Apache-2.0\n' "$CURRENT_YEAR" > "$tmp"
  if [[ -n "$line_num" ]]; then
    tail -n +"$line_num" "$file" >> "$tmp"
  else
    cat "$file" >> "$tmp"
  fi
  mv "$tmp" "$file"
}

echo "==> License headers"
license_files=("${src_kt_files[@]}" "${kts_files[@]}" "${src_java_files[@]}")
header_count=0
for file in "${license_files[@]}"; do
  [[ -f "$REPO_ROOT/$file" ]] || continue

  if ! has_header "$REPO_ROOT/$file"; then
    if $CHECK_ONLY; then
      fail "Missing license header: $file"
    else
      add_header "$REPO_ROOT/$file"
      echo "  Added: $file"
      header_count=$((header_count + 1))
    fi
  fi
done
if ! $CHECK_ONLY; then
  [[ $header_count -eq 0 ]] && ok "  All files have headers" || ok "  Added $header_count headers"
fi

# --- ktfmt ---

all_kt_files=("${src_kt_files[@]}" "${kts_files[@]}")
if [[ ${#all_kt_files[@]} -gt 0 ]]; then
  echo "==> ktfmt (${#all_kt_files[@]} files)"
  ktfmt_args=(--google-style)
  if $CHECK_ONLY; then
    ktfmt_args+=(--set-exit-if-changed --dry-run)
  fi

  # Build absolute paths
  abs_kt_files=()
  for f in "${all_kt_files[@]}"; do
    [[ -f "$REPO_ROOT/$f" ]] && abs_kt_files+=("$REPO_ROOT/$f")
  done

  if [[ ${#abs_kt_files[@]} -gt 0 ]]; then
    if ! "$REPO_ROOT/config/bin/ktfmt" "${ktfmt_args[@]}" "${abs_kt_files[@]}" 2>/dev/null; then
      if $CHECK_ONLY; then
        fail "ktfmt: some files need formatting"
      else
        # Re-run verbosely on failure
        fail "ktfmt formatting failed, re-running verbosely:"
        "$REPO_ROOT/config/bin/ktfmt" "${ktfmt_args[@]}" "${abs_kt_files[@]}" || true
      fi
    else
      ok "  Done"
    fi
  fi
else
  echo "==> ktfmt (no files)"
fi

# --- gjf ---

if [[ ${#src_java_files[@]} -gt 0 ]]; then
  echo "==> gjf (${#src_java_files[@]} files)"
  gjf_args=()
  if $CHECK_ONLY; then
    gjf_args+=(--set-exit-if-changed --dry-run)
  else
    gjf_args+=(--replace)
  fi

  abs_java_files=()
  for f in "${src_java_files[@]}"; do
    [[ -f "$REPO_ROOT/$f" ]] && abs_java_files+=("$REPO_ROOT/$f")
  done

  if [[ ${#abs_java_files[@]} -gt 0 ]]; then
    if ! "$REPO_ROOT/config/bin/gjf" "${gjf_args[@]}" "${abs_java_files[@]}" 2>/dev/null; then
      if $CHECK_ONLY; then
        fail "gjf: some files need formatting"
      else
        fail "gjf formatting failed, re-running verbosely:"
        "$REPO_ROOT/config/bin/gjf" "${gjf_args[@]}" "${abs_java_files[@]}" || true
      fi
    else
      ok "  Done"
    fi
  fi
else
  echo "==> gjf (no files)"
fi

# --- Trailing whitespace + end with newline ---

all_source_files=("${src_kt_files[@]}" "${kts_files[@]}" "${src_java_files[@]}")
if [[ ${#all_source_files[@]} -gt 0 ]]; then
  echo "==> Trailing whitespace"
  ws_count=0
  for file in "${all_source_files[@]}"; do
    local_file="$REPO_ROOT/$file"
    [[ -f "$local_file" ]] || continue

    if grep -qE '[[:space:]]+$' "$local_file"; then
      if $CHECK_ONLY; then
        fail "Trailing whitespace: $file"
      else
        # Remove trailing whitespace (cross-platform sed -i)
        local tmp
        tmp="$(mktemp)"
        sed 's/[[:space:]]*$//' "$local_file" > "$tmp" && mv "$tmp" "$local_file"
        ws_count=$((ws_count + 1))
      fi
    fi

    # Check end with newline
    if [[ -s "$local_file" ]] && [[ "$(tail -c 1 "$local_file" | xxd -p)" != "0a" ]]; then
      if $CHECK_ONLY; then
        fail "Missing trailing newline: $file"
      else
        printf '\n' >> "$local_file"
        ws_count=$((ws_count + 1))
      fi
    fi
  done
  if ! $CHECK_ONLY; then
    [[ $ws_count -eq 0 ]] && ok "  Clean" || ok "  Fixed $ws_count files"
  fi
fi

# --- Summary ---

ELAPSED=$(( $(date +%s) - START_TIME ))

if [[ $FAILURES -gt 0 ]]; then
  echo ""
  fail "Found $FAILURES issue(s) (${ELAPSED}s)"
  exit 1
else
  echo ""
  ok "All clean! (${ELAPSED}s)"
fi