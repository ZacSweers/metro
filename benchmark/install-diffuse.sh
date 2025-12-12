#!/bin/bash
# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

# Installs diffuse from GitHub releases to tmp/ directory (gitignored, persists across checkouts)
# Skips installation if diffuse is already available on PATH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Diffuse version to install
DIFFUSE_VERSION="0.3.0"

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

install_diffuse() {
    echo ""
    echo -e "${GREEN}════════════════════════════════════════${NC}"
    echo -e "${GREEN}  Installing diffuse from GitHub releases${NC}"
    echo -e "${GREEN}════════════════════════════════════════${NC}"
    echo ""

    # Check if diffuse is already on PATH
    if command -v diffuse &> /dev/null; then
        print_success "diffuse already available on PATH: $(command -v diffuse)"
        return 0
    fi

    # Install to tmp/ at repo root which is gitignored and persists across checkouts
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    local tmp_dir="$repo_root/tmp"
    local diffuse_dir="$tmp_dir/diffuse"
    local diffuse_bin="$diffuse_dir/diffuse-${DIFFUSE_VERSION}/bin/diffuse"
    local download_url="https://github.com/JakeWharton/diffuse/releases/download/${DIFFUSE_VERSION}/diffuse-${DIFFUSE_VERSION}.zip"

    # Check if diffuse is already installed locally
    if [ -x "$diffuse_bin" ]; then
        print_success "diffuse already installed at $diffuse_bin"
        return 0
    fi

    # Ensure required tools are available (for Ubuntu/CI)
    if ! command -v curl &> /dev/null; then
        print_error "curl is required but not installed"
        return 1
    fi
    if ! command -v unzip &> /dev/null; then
        print_error "unzip is required but not installed. On Ubuntu: sudo apt-get install unzip"
        return 1
    fi

    # Create tmp directory if needed
    mkdir -p "$tmp_dir"

    # Download and extract diffuse
    print_status "Downloading diffuse ${DIFFUSE_VERSION}..."
    local zip_file="$tmp_dir/diffuse-${DIFFUSE_VERSION}.zip"

    if curl -fsSL "$download_url" -o "$zip_file"; then
        print_status "Extracting diffuse..."
        rm -rf "$diffuse_dir"
        mkdir -p "$diffuse_dir"
        unzip -q "$zip_file" -d "$diffuse_dir"
        rm "$zip_file"

        if [ -f "$diffuse_bin" ]; then
            chmod +x "$diffuse_bin"
            # Also make all scripts in bin/ executable
            chmod +x "$diffuse_dir/diffuse-${DIFFUSE_VERSION}/bin/"* 2>/dev/null || true
            print_success "diffuse installed successfully"
            print_status "Installed to: $diffuse_bin"
            return 0
        else
            print_error "diffuse binary not found at expected location: $diffuse_bin"
            # List what was extracted for debugging
            print_status "Contents of $diffuse_dir:"
            ls -la "$diffuse_dir" 2>/dev/null || true
            return 1
        fi
    else
        print_error "Failed to download diffuse from $download_url"
        return 1
    fi
}

# Returns the path to the diffuse binary (for use by other scripts)
# Prefers system PATH version if available
get_diffuse_bin() {
    # Check if diffuse is on PATH first
    if command -v diffuse &> /dev/null; then
        command -v diffuse
        return 0
    fi

    # Fall back to local installation
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
    echo "$repo_root/tmp/diffuse/diffuse-${DIFFUSE_VERSION}/bin/diffuse"
}

# If run directly, install diffuse
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    install_diffuse
fi
