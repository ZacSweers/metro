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
  if [[ "$EXPERIMENT_SHARD" != 'modes' && "${EXPERIMENT_SKIP_LEGACY:-false}" != 'true' ]]; then
    args+=(:compiler:test --rerun)
  fi
  args+=(:compiler-tests:test --rerun -Pmetro.excludeJsBoxTests
    -Pmetro.compilerTestMaxParallelForks=1
    "-Pmetro.ciCompilerTestsPreparedFile=$RUNNER_TEMP/compiler-tests-prepared")
  if [[ -n "$EXPERIMENT_THREADS" ]]; then
    args+=("-Pmetro.experiment.compilerJUnitThreads=$EXPERIMENT_THREADS")
  fi
  if [[ -n "$EXPERIMENT_HEAP" ]]; then
    args+=("-Pmetro.compilerTestHeapSize=$EXPERIMENT_HEAP")
  fi
  if [[ -n "$EXPERIMENT_GC" ]]; then
    args+=("-Pmetro.experiment.compilerGc=$EXPERIMENT_GC")
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
    "-Pmetro.functionalTestKmpTarget=$EXPERIMENT_TARGET")
  if [[ "$EXPERIMENT_FIXTURE_CACHE_MODE" != 'unchanged' ]]; then
    args+=(-Pmetro.testkitConfigurationCache=true
      -Pmetro.testkitConfigurationCacheReadOnly=false)
  fi
  if [[ "$EXPERIMENT_LIGHTWEIGHT" == 'true' ]]; then
    args+=(-Pmetro.testkitLightweightPublications=true)
  fi
  if [[ "$EXPERIMENT_PREBUILT" == 'true' ]]; then
    args+=(-x installForFunctionalTest)
  fi
  if [[ "$EXPERIMENT_PREBUILT_COMPILER" == 'true' ]]; then
    args+=(-Pmetro.testkitPrebuiltCompiler=true)
  fi
  if [[ -n "$EXPERIMENT_FUNCTIONAL_SHARD" ]]; then
    args+=("-Pmetro.experiment.functionalShard=$EXPERIMENT_FUNCTIONAL_SHARD")
  fi
  if [[ -n "$EXPERIMENT_FILTER" ]]; then
    args+=(--tests "$EXPERIMENT_FILTER")
  fi
fi

run_experiment() {
  if [[ "${EXPERIMENT_PREPARE_STOP:-false}" == 'true' ]]; then
    if [[ "$EXPERIMENT_SUITE" == 'compiler' || "${EXPERIMENT_PREBUILT:-false}" == 'true' ]]; then
      echo 'prepare-stop requires a functional workload with local preparation'
      return 2
    fi
    local prepare_args=()
    local skip_filter_value=false
    local arg
    for arg in "${args[@]}"; do
      if [[ "$skip_filter_value" == 'true' ]]; then
        skip_filter_value=false
        continue
      fi
      case "$arg" in
        :gradle-plugin:functionalTest|--rerun) continue ;;
        --tests) skip_filter_value=true; continue ;;
      esac
      prepare_args+=("$arg")
    done
    prepare_args+=(:gradle-plugin:installForFunctionalTest :gradle-plugin:functionalTestClasses
      :gradle-plugin:pluginUnderTestMetadata)
    local phase_start=$SECONDS
    ./scripts/run-ci-gradle.sh "${prepare_args[@]}"
    local phase_status=$?
    printf 'preparation_elapsed_seconds=%s\npreparation_exit_code=%s\n' \
      "$((SECONDS - phase_start))" "$phase_status" | tee -a "$RUNNER_TEMP/timing.txt"
    if (( phase_status != 0 )); then
      return "$phase_status"
    fi
    phase_start=$SECONDS
    ./gradlew --stop
    phase_status=$?
    printf 'stop_elapsed_seconds=%s\nstop_exit_code=%s\n' \
      "$((SECONDS - phase_start))" "$phase_status" | tee -a "$RUNNER_TEMP/timing.txt"
    if (( phase_status != 0 )); then
      return "$phase_status"
    fi
  fi
  local phase_start=$SECONDS
  ./scripts/run-ci-gradle.sh "${args[@]}"
  local phase_status=$?
  printf 'test_build_elapsed_seconds=%s\ntest_build_exit_code=%s\n' \
    "$((SECONDS - phase_start))" "$phase_status" | tee -a "$RUNNER_TEMP/timing.txt"
  return "$phase_status"
}

start=$SECONDS
: > "$RUNNER_TEMP/timing.txt"
set +e
run_experiment 2>&1 | tee "$RUNNER_TEMP/workload.log"
status=${PIPESTATUS[0]}
printf 'elapsed_seconds=%s\nexit_code=%s\n' "$((SECONDS - start))" "$status" | tee -a "$RUNNER_TEMP/timing.txt"
exit "$status"
