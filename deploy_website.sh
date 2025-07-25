#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material mdx_truly_sane_lists
#
# To run the site locally with hot-reload support, use:
# ./deploy_website.sh --local

if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  set -ex

  export GIT_CLONE_PROTECTION_ACTIVE=false
  REPO="git@github.com:zacsweers/metro.git"
  DIR=temp-clone

  # Delete any existing temporary website clone
  rm -rf ${DIR}

  # Clone the current repo into temp folder
  git clone ${REPO} ${DIR}

  # Move working directory into temp folder
  cd ${DIR}

  # Generate the API docs
  # --rerun-tasks because Dokka has bugs :(
  ./gradlew :dokkaGenerate --rerun-tasks

  cd ..
  rm -rf ${DIR}
  rm -rf site
fi

# Copy in special files that GitHub wants in the project root.
cp CHANGELOG.md docs/changelog.md
cp .github/CONTRIBUTING.md docs/contributing.md
cp samples/README.md docs/samples.md
cp .github/CODE_OF_CONDUCT.md docs/code-of-conduct.md

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
