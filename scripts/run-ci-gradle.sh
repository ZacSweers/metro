#!/bin/bash

# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# CI invokes Gradle through this wrapper so every job uses the same worker and heap limits.
# This leaves memory for Gradle, Kotlin daemons, and test JVMs on GitHub-hosted runners.

read_meminfo_kb() {
  local key="$1"
  if [[ -r "/proc/meminfo" ]]; then
    awk -v key="$key" '$1 == key ":" { print $2 }' /proc/meminfo
  else
    echo "unavailable"
  fi
}

read_cgroup_value() {
  local file="$1"
  if [[ -r "$file" ]]; then
    tr -d '\n' < "$file"
  else
    echo "unavailable"
  fi
}

read_oom_kill_count() {
  local events_file="/sys/fs/cgroup/memory.events"
  if [[ -r "$events_file" ]]; then
    awk '$1 == "oom_kill" { print $2 }' "$events_file"
  else
    echo "unavailable"
  fi
}

log_resources() {
  local mem_available_kb
  local swap_free_kb
  local disk_available_kb
  local java_processes
  local java_rss_kb
  local cgroup_memory
  local cgroup_memory_max
  local cgroup_oom_kills

  mem_available_kb=$(read_meminfo_kb "MemAvailable")
  swap_free_kb=$(read_meminfo_kb "SwapFree")
  disk_available_kb=$(df -Pk "${GITHUB_WORKSPACE:-.}" | awk 'NR == 2 { print $4 }')
  read -r java_processes java_rss_kb < <(
    ps -eo comm=,rss= | awk '$1 == "java" { count += 1; rss += $2 } END { print count + 0, rss + 0 }'
  )
  cgroup_memory=$(read_cgroup_value "/sys/fs/cgroup/memory.current")
  cgroup_memory_max=$(read_cgroup_value "/sys/fs/cgroup/memory.max")
  cgroup_oom_kills=$(read_oom_kill_count)

  echo "CI resources: mem_available_kb=$mem_available_kb swap_free_kb=$swap_free_kb disk_available_kb=$disk_available_kb java_processes=$java_processes java_rss_kb=$java_rss_kb cgroup_memory_bytes=$cgroup_memory cgroup_memory_max_bytes=$cgroup_memory_max cgroup_oom_kills=$cgroup_oom_kills"
}

monitor_resources() {
  while true; do
    log_resources
    sleep 60
  done
}

# Resource samples help diagnose runner memory pressure without cluttering normal logs.
# GitHub sets RUNNER_DEBUG=1 when a run is rerun with debug logging enabled.
if [[ "${RUNNER_DEBUG:-0}" == "1" ]]; then
  monitor_resources &
  monitor_pid=$!

  cleanup() {
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
  }
  trap cleanup EXIT
fi

gradle_jvm_args="-Xmx4g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"

./gradlew \
  --max-workers=4 \
  "-Dorg.gradle.jvmargs=$gradle_jvm_args" \
  -Pkotlin.daemon.jvmargs=-Xmx2g \
  "$@"
