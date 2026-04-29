#!/bin/bash

# The website is built using Zensical (successor to MkDocs Material).
# https://zensical.org/
# It requires Python to run.
# Install the packages with the following command:
# pip install -r .github/workflows/mkdocs-requirements.txt
#
# To run the site locally with hot-reload support, use:
# ./scripts/run_local_docs_site.sh

# Check if zensical is installed
if ! command -v zensical &> /dev/null; then
    echo "zensical is not installed. Please run:"
    echo "pip install -r .github/workflows/mkdocs-requirements.txt"
    exit 1
fi

# Copy documentation files using shared script
./scripts/copy_docs_files.sh

# Serve the site locally with hot-reload
zensical serve
