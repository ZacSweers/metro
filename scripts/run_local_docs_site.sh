#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install -r .github/workflows/mkdocs-requirements.txt
#
# To run the site locally with hot-reload support, use:
# ./scripts/run_local_docs_site.sh

# Check if mkdocs is installed
if ! command -v mkdocs &> /dev/null; then
    echo "mkdocs is not installed. Please run:"
    echo "pip install -r .github/workflows/mkdocs-requirements.txt"
    exit 1
fi

# Copy documentation files using shared script
./scripts/copy_docs_files.sh

# Serve the site locally with hot-reload
mkdocs serve
