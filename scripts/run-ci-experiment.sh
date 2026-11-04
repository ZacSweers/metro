#!/bin/bash
set -euo pipefail

sampler_pid=
if [[ "$RUNNER_OS" == 'macOS' ]]; then
  python3 scripts/ci-resource-sampler.py --interval 30 > "$RUNNER_TEMP/macos-resources.jsonl" &
  sampler_pid=$!
fi
cleanup() {
  if [[ -n "$sampler_pid" ]]; then
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

args=(--continue --quiet --profile -Dorg.gradle.configuration-cache.read-only=true
  -I scripts/ci-experiment.init.gradle
  "-Pmetro.testCompilerVersion=$EXPERIMENT_KOTLIN"
  "-Pmetro.testOmitRedundantMirrors=$EXPERIMENT_MIRRORS")
if [[ "$EXPERIMENT_SUITE" == 'compiler' ]]; then
  args+=(:compiler-tests:generateTests)
  if [[ "$EXPERIMENT_SHARD" != 'modes' ]]; then
    args+=(:compiler:test --rerun)
  fi
  args+=(:compiler-tests:test --rerun -Pmetro.excludeJsBoxTests
    -Pmetro.compilerTestMaxParallelForks=1
    "-Pmetro.ciCompilerTestsPreparedFile=$RUNNER_TEMP/compiler-tests-prepared")
  if [[ -n "$EXPERIMENT_THREADS" ]]; then
    args+=("-Pmetro.experiment.compilerJUnitThreads=$EXPERIMENT_THREADS")
  fi
  if [[ -n "$EXPERIMENT_LEGACY_FORKS" ]]; then
    args+=("-Pmetro.experiment.legacyForks=$EXPERIMENT_LEGACY_FORKS")
  fi
  if [[ -n "$EXPERIMENT_SHARD" ]]; then
    args+=("-Pmetro.experiment.compilerShard=$EXPERIMENT_SHARD")
  fi
  if [[ "$EXPERIMENT_JFR" == 'true' ]]; then
    args+=(-Pmetro.experiment.compilerJfr=true)
  fi
else
  args+=(:gradle-plugin:functionalTest --rerun
    "-Pmetro.functionalTestMaxParallelForks=$EXPERIMENT_FORKS"
    "-Pmetro.functionalTestKmpTarget=$EXPERIMENT_TARGET"
    -Pmetro.testkitConfigurationCache=true
    -Pmetro.testkitConfigurationCacheReadOnly=false)
  if [[ "$EXPERIMENT_LIGHTWEIGHT" == 'true' ]]; then
    args+=(-Pmetro.testkitLightweightPublications=true)
  fi
  if [[ -n "$EXPERIMENT_FILTER" ]]; then
    args+=(--tests "$EXPERIMENT_FILTER")
  fi
fi

start=$SECONDS
set +e
./scripts/run-ci-gradle.sh "${args[@]}" 2>&1 | tee "$RUNNER_TEMP/workload.log"
status=${PIPESTATUS[0]}
printf 'elapsed_seconds=%s\nexit_code=%s\n' "$((SECONDS - start))" "$status" | tee "$RUNNER_TEMP/timing.txt"
exit "$status"
