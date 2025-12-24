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

unzip -p "$JAR" META-INF/compiler.version