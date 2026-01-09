#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <path-to-intellij.app>"
  exit 1
fi

BASE="$1/Contents/plugins/Kotlin"
JAR="$BASE/lib/kotlinc.kotlin-compiler-common.jar"

if [ ! -f "$JAR" ]; then
  echo "Error: kotlinc.kotlin-compiler-common.jar not found at $JAR"
  exit 1
fi

echo "lib/kotlinc.kotlin-compiler-common.jar/META-INF/compiler.version:"
unzip -p "$JAR" META-INF/compiler.version

BUILD_TXT="$BASE/kotlinc/build.txt"
if [ ! -f "$BUILD_TXT" ]; then
  echo "Error: build.txt not found at $BUILD_TXT"
  exit 1
fi

echo ""
echo ""
echo "build.txt:"
cat "$BUILD_TXT"