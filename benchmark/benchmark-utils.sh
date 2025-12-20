#!/usr/bin/env bash
# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0
#
# Common utility functions for benchmark scripts.
# Source this file from other scripts: source "$(dirname "$0")/benchmark-utils.sh"

# Prevent multiple sourcing (use ${VAR:-} syntax for set -u compatibility)
if [ -n "${BENCHMARK_UTILS_SOURCED:-}" ]; then
    return 0
fi
BENCHMARK_UTILS_SOURCED=true

# ============================================================================
# Colors for output
# ============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================================================
# Metro Version Detection
# ============================================================================

# Check if a string is a semantic version (Metro version) rather than a git ref
# Returns 0 (true) if it matches semver pattern, 1 (false) otherwise
# Matches: 1.0.0, 1.2.3, 0.1.0, 2.0.0-alpha01, 1.0.0-RC1, 1.0.0-SNAPSHOT, etc.
is_metro_version() {
    local ref="$1"
    if [[ "$ref" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9._-]+)?$ ]]; then
        return 0
    fi
    return 1
}

# Get ref type description for display
get_ref_type_description() {
    local ref="$1"
    if is_metro_version "$ref"; then
        echo "Metro version"
    else
        echo "git ref"
    fi
}

# ============================================================================
# Git State Management
# ============================================================================

# Variables to track git state (must be declared in sourcing script or will be global)
# ORIGINAL_GIT_REF=""
# ORIGINAL_GIT_IS_BRANCH=false

# Save current git state (branch or commit)
# Sets ORIGINAL_GIT_REF and ORIGINAL_GIT_IS_BRANCH
save_git_state() {
    # Check if we're on a branch or in detached HEAD state
    local current_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "")
    if [ -n "$current_branch" ]; then
        ORIGINAL_GIT_REF="$current_branch"
        ORIGINAL_GIT_IS_BRANCH=true
        echo -e "${BLUE}ℹ Saved current branch: $ORIGINAL_GIT_REF${NC}"
    else
        # Detached HEAD - save the commit hash
        ORIGINAL_GIT_REF=$(git rev-parse HEAD)
        ORIGINAL_GIT_IS_BRANCH=false
        echo -e "${BLUE}ℹ Saved current commit: ${ORIGINAL_GIT_REF:0:12}${NC}"
    fi
}

# Restore to original git state
# Requires ORIGINAL_GIT_REF and ORIGINAL_GIT_IS_BRANCH to be set
restore_git_state() {
    if [ -z "$ORIGINAL_GIT_REF" ]; then
        echo -e "${RED}✗ No git state saved to restore${NC}"
        return 1
    fi

    echo -e "${YELLOW}→ Restoring to original git state...${NC}"
    if [ "$ORIGINAL_GIT_IS_BRANCH" = true ]; then
        git checkout "$ORIGINAL_GIT_REF" 2>/dev/null || {
            echo -e "${RED}✗ Failed to restore to branch: $ORIGINAL_GIT_REF${NC}"
            return 1
        }
        echo -e "${GREEN}✓ Restored to branch: $ORIGINAL_GIT_REF${NC}"
    else
        git checkout "$ORIGINAL_GIT_REF" 2>/dev/null || {
            echo -e "${RED}✗ Failed to restore to commit: ${ORIGINAL_GIT_REF:0:12}${NC}"
            return 1
        }
        echo -e "${GREEN}✓ Restored to commit: ${ORIGINAL_GIT_REF:0:12}${NC}"
    fi
}

# Checkout a git ref (branch or commit)
checkout_ref() {
    local ref="$1"
    echo -e "${YELLOW}→ Checking out: $ref${NC}"
    git checkout "$ref" 2>/dev/null || {
        echo -e "${RED}✗ Failed to checkout: $ref${NC}"
        return 1
    }
    local short_ref=$(git rev-parse --short HEAD)
    echo -e "${GREEN}✓ Checked out: $ref ($short_ref)${NC}"
}

# Get a filesystem-safe name for a git ref or version
get_ref_safe_name() {
    local ref="$1"
    # Replace slashes and other special chars with underscores
    echo "$ref" | sed 's/[^a-zA-Z0-9._-]/_/g'
}

# Get a short display name for a git ref
get_ref_display_name() {
    local ref="$1"
    # If it's a Metro version, return as-is
    if is_metro_version "$ref"; then
        echo "$ref"
        return
    fi
    # Try to resolve to a short commit hash
    local short_hash=$(git rev-parse --short "$ref" 2>/dev/null || echo "$ref")
    # If it's a branch name, use that; otherwise use the short hash
    if git show-ref --verify --quiet "refs/heads/$ref" 2>/dev/null; then
        echo "$ref"
    elif git show-ref --verify --quiet "refs/remotes/origin/$ref" 2>/dev/null; then
        echo "$ref"
    else
        echo "$short_hash"
    fi
}

# ============================================================================
# Percentage Calculations
# ============================================================================

# Calculate percentage difference: ((value - baseline) / baseline) * 100
# Args: value baseline [precision]
# Returns: percentage as a number (e.g., "3.7" or "-2.5")
# Returns empty string if calculation fails
calc_pct_diff() {
    local value="$1"
    local baseline="$2"
    local precision="${3:-1}"

    if [ -z "$value" ] || [ -z "$baseline" ] || [ "$baseline" = "0" ]; then
        echo ""
        return
    fi

    local pct_raw=$(echo "scale=6; (($value - $baseline) / $baseline) * 100" | bc 2>/dev/null || echo "")
    if [ -z "$pct_raw" ]; then
        echo ""
        return
    fi

    printf "%.${precision}f" "$pct_raw" 2>/dev/null | sed 's/\.0$//' || echo "$pct_raw"
}

# Calculate multiplier: value / baseline
# Args: value baseline [precision]
# Returns: multiplier as a number (e.g., "1.04" or "0.95")
# Returns empty string if calculation fails
calc_multiplier() {
    local value="$1"
    local baseline="$2"
    local precision="${3:-2}"

    if [ -z "$value" ] || [ -z "$baseline" ] || [ "$baseline" = "0" ]; then
        echo ""
        return
    fi

    printf "%.${precision}f" "$(echo "scale=6; $value / $baseline" | bc 2>/dev/null)" 2>/dev/null || echo ""
}

# Format percentage difference with sign and multiplier for display
# Args: value baseline
# Returns: formatted string like "+3.7% (1.04x)" or "-2.5% (0.97x)" or "—" if invalid
format_vs_baseline() {
    local value="$1"
    local baseline="$2"

    local pct=$(calc_pct_diff "$value" "$baseline" 1)
    local mult=$(calc_multiplier "$value" "$baseline" 2)

    if [ -z "$pct" ] || [ -z "$mult" ]; then
        echo "—"
        return
    fi

    # Add + sign for positive percentages
    if [[ "$pct" != -* ]]; then
        echo "+${pct}% (${mult}x)"
    else
        echo "${pct}% (${mult}x)"
    fi
}

# Format percentage difference with sign only (no multiplier)
# Args: value baseline [precision]
# Returns: formatted string like "+3.7%" or "-2.5%" or "—" if invalid
format_pct_diff() {
    local value="$1"
    local baseline="$2"
    local precision="${3:-1}"

    local pct=$(calc_pct_diff "$value" "$baseline" "$precision")

    if [ -z "$pct" ]; then
        echo "—"
        return
    fi

    # Add + sign for positive percentages
    if [[ "$pct" != -* ]] && [[ "$pct" != "0" ]] && [[ "$pct" != "0.0" ]] && [[ "$pct" != "0.00" ]]; then
        echo "+${pct}%"
    else
        echo "${pct}%"
    fi
}

# ============================================================================
# Duration Formatting
# ============================================================================

# Format duration in human-readable format
format_duration() {
    local seconds=$1
    local minutes=$((seconds / 60))
    local remaining_seconds=$((seconds % 60))
    if [ $minutes -gt 0 ]; then
        echo "${minutes}m ${remaining_seconds}s"
    else
        echo "${seconds}s"
    fi
}
