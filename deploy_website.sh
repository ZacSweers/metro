#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material mdx_truly_sane_lists "mkdocs-material[imaging]"
#
# To run the site locally with hot-reload support, use:
# ./deploy_website.sh --local

if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  set -ex

  export GIT_CLONE_PROTECTION_ACTIVE=false
  REPO="https://github.com/ZacSweers/metro.git"
  DIR=temp-clone

  # Delete any existing temporary website clone
  rm -rf ${DIR}

  # Clone the current repo into temp folder
  git clone ${REPO} ${DIR}

  # Move working directory into temp folder
  cd ${DIR}

  # Generate API docs using shared script
  ./scripts/generate_docs_dokka.sh

  cd ..
  rm -rf ${DIR}
  rm -rf site
fi

# Copy documentation files using shared script
./scripts/copy_docs_files.sh

# Build the site and push the new files up to GitHub
if ! [[ ${local} ]]; then
  mkdocs gh-deploy
else
  mkdocs serve
fi

# Delete our temp folder
if ! [[ ${local} ]]; then
  cd ..
  rm -rf ${DIR}
  rm -rf site
fi
