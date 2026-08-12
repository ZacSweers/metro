#!/usr/bin/env bash

# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <kotlinc-executable> [compiler-version]" >&2
  exit 2
fi

compiler=$1
fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
default_compiler_version=$(
  sed -n 's/^supported\.kotlin\.compiler\.version=//p' "$fixture_dir/fixture.properties"
)
compiler_version=${2:-$default_compiler_version}

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/metro-crema.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT

plugin_options="enabled=true,debug=true,compiler-version=$compiler_version,generate-contribution-hints-in-fir=true,generate-classes-in-ir=false,parallel-threads=0,diagnostics-render-mode=PLAIN"

"$compiler" \
  -classpath "$fixture_dir/metro-runtime.jar" \
  "-Xcompiler-plugin=$fixture_dir/metro-compiler.jar=$plugin_options" \
  "$fixture_dir/Smoke.kt" \
  -include-runtime \
  -d "$work_dir/smoke.jar"

output=$(java -cp "$work_dir/smoke.jar:$fixture_dir/metro-runtime.jar" SmokeKt)
if [[ "$output" != "OK" ]]; then
  echo "Expected smoke test output 'OK', but received '$output'." >&2
  exit 1
fi

echo "$output"
