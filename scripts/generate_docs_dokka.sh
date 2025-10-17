#!/bin/bash

# Generate API documentation using Dokka
# This script handles the API docs generation that's shared between CI and local builds

set -e

echo "Generating API docs using dokka..."

# Generate the API docs
# --rerun-tasks because Dokka has bugs :(
./gradlew :dokkaGenerate --rerun-tasks --no-build-cache --no-configuration-cache

echo "API docs generation complete!"