#!/bin/bash

# Commits/deploys versioned docs site for a specific version using `mike`
# ⚠️ Version `0.6.1` and above is required. Support for `mike` is added from `0.6.1`.
# See https://github.com/ZacSweers/metro/pull/939
# 
# Usage: ./scripts/deploy_metro_docs_site.sh <version> [--latest]
# Example: ./scripts/deploy_metro_docs_site.sh 0.6.2 --latest
# Example: ./scripts/deploy_metro_docs_site.sh 0.6.2

set -e

# Check if version parameter is provided
if [ $# -eq 0 ]; then
    echo "Error: Version parameter is required"
    echo "Usage: $0 <version> [--latest]"
    echo "Example: $0 0.6.2 --latest    (marks as latest release)"
    echo "Example: $0 0.6.2             (does not mark as latest)"
    exit 1
fi

VERSION=$1
IS_LATEST=false

# Check if --latest flag is provided
if [ "$2" = "--latest" ]; then
    IS_LATEST=true
fi

# Check if mike is available
echo "Checking if mike is available..."
if ! command -v mike &> /dev/null; then
    echo "Error: 'mike' command not found. use pip to install required dependencies"
    echo "pip install -r .github/workflows/mkdocs-requirements.txt"
    exit 1
fi

echo "Deploying documentation for version: $VERSION"
if [ "$IS_LATEST" = true ]; then
    echo "This will be marked as the latest release"
else
    echo "This will NOT be marked as the latest release"
fi

# Check if the tag exists
echo "Checking if tag $VERSION exists..."
if ! git tag -l | grep -q "^$VERSION$"; then
    echo "Error: Tag '$VERSION' does not exist"
    echo "Available tags:"
    git tag -l --sort=-version:refname | head -10
    exit 1
fi

# Checkout the specified version/tag
echo "Checking out version $VERSION..."
git checkout "$VERSION"

# Generate API documentation using Dokka
echo "Generating API docs using dokka..."
./scripts/generate_docs_dokka.sh

# Copy documentation files
echo "Copying required docs files..."
./scripts/copy_docs_files.sh

# Deploy with mike, updating aliases and pushing
echo "Deploying with mike..."
if [ "$IS_LATEST" = true ]; then
    mike deploy --update-aliases --push "$VERSION" latest
else
    mike deploy --push "$VERSION"
fi

echo "Deployment for $VERSION complete! Changes should be already committed to gh-pages branch."
