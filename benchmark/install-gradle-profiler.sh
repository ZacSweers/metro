#!/bin/bash
# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

# Installs gradle-profiler from source to tmp/ directory (gitignored, persists across checkouts)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

install_gradle_profiler() {
    echo ""
    echo -e "${GREEN}════════════════════════════════════════${NC}"
    echo -e "${GREEN}  Installing gradle-profiler from source${NC}"
    echo -e "${GREEN}════════════════════════════════════════${NC}"
    echo ""

    # Install to tmp/ at repo root which is gitignored and persists across checkouts
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local tmp_dir="$repo_root/tmp"
    local profiler_dir="$tmp_dir/gradle-profiler-source"
    local profiler_bin="$profiler_dir/build/install/gradle-profiler/bin/gradle-profiler"
    local profiler_repo="https://github.com/gradle/gradle-profiler"

    # Check if gradle-profiler is already built
    if [ -x "$profiler_bin" ]; then
        print_success "gradle-profiler already installed at $profiler_bin"
        return 0
    fi

    # Create tmp directory if needed
    mkdir -p "$tmp_dir"

    # Clone or update the repository
    if [ -d "$profiler_dir" ]; then
        print_status "Updating existing gradle-profiler repository"
        cd "$profiler_dir"
        git pull origin master
        cd "$SCRIPT_DIR"
    else
        print_status "Cloning gradle-profiler repository"
        git clone "$profiler_repo" "$profiler_dir"
    fi

    # Build gradle-profiler
    print_status "Building gradle-profiler (this may take a few minutes)"
    cd "$profiler_dir"
    if ./gradlew installDist; then
        cd "$SCRIPT_DIR"

        if [ -f "$profiler_bin" ]; then
            print_success "gradle-profiler installed successfully"
            print_status "Installed to: $profiler_bin"
            return 0
        else
            print_error "gradle-profiler binary not found at expected location"
            return 1
        fi
    else
        cd "$SCRIPT_DIR"
        print_error "Failed to build gradle-profiler"
        return 1
    fi
}

# Returns the path to the gradle-profiler binary (for use by other scripts)
get_gradle_profiler_bin() {
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    echo "$repo_root/tmp/gradle-profiler-source/build/install/gradle-profiler/bin/gradle-profiler"
}

# If run directly, install gradle-profiler
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    install_gradle_profiler
fi
