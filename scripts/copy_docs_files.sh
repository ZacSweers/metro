#!/bin/bash

# Common script for MkDocs documentation build tasks
# Used by both CI workflows and local development

set -e

# Copy documentation files to mkdocs directory
echo "Copying documentation files to mkdocs site..."

# Copy in special files that GitHub wants in the project root.
cp CHANGELOG.md docs/changelog.md
cp .github/CONTRIBUTING.md docs/contributing.md
cp samples/README.md docs/samples.md
cp .github/CODE_OF_CONDUCT.md docs/code-of-conduct.md
cp metrox-android/README.md docs/metrox-android.md

echo "Copying documentation files complete!"