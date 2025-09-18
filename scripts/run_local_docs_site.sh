#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install -r .github/workflows/mkdocs-requirements.txt
#
# To run the site locally with hot-reload support, use:
# ./run_local_docs_site.sh

# Copy documentation files using shared script
./scripts/copy_docs_files.sh

# Serve the site locally with hot-reload
mkdocs serve
