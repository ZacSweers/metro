#!/usr/bin/env bash
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

# Runs the chosen checkout's sandbox through Gradle with its IDE heap set before startup.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: scripts/run-local-ide.sh --ide-path PATH --project PATH [options] [-- GRADLE_ARGS...]

  --ide-path PATH  Installed IDE directory, including the .app directory on macOS.
  --project PATH   Project directory to open in the sandboxed IDE.
  --heap SIZE      IDE maximum heap, such as 8g or 8192m (default: 8g).
  --repo PATH      Metro checkout to build (default: this script's checkout).
  -h, --help       Show this help.

Arguments after -- are passed unchanged to Gradle. Python 3 is required.
The launcher runs ./idea-plugin/gradlew -p idea-plugin runLocalIde in --repo.
It prints the heap and sandbox config/log directories before IDE startup.
Complete first-run onboarding and project sync before measuring Metro refresh.
Existing sandbox settings and project files are preserved. Close the IDE to end the run.

Example:
  scripts/run-local-ide.sh \
    --ide-path "$HOME/Applications/Android Studio.app" \
    --project "$HOME/code/openai-android" --heap 8g -- --quiet
HELP
}

fail() {
  printf 'run-local-ide: %s\n' "$*" >&2
  exit 2
}

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ide_path=""
project_path=""
ide_heap="8g"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ide-path|--project|--heap|--repo)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || fail "$1 requires a value"
      case "$1" in
        --ide-path) ide_path="$2" ;;
        --project) project_path="$2" ;;
        --heap) ide_heap="$2" ;;
        --repo) repo_dir="$2" ;;
      esac
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    *) fail "unknown argument: $1 (use --help or place Gradle arguments after --)" ;;
  esac
done

[[ -n "$ide_path" ]] || fail "--ide-path is required"
[[ -n "$project_path" ]] || fail "--project is required"
[[ "$ide_heap" =~ ^[1-9][0-9]*[mMgG]$ ]] || fail "--heap must be a positive size such as 8g or 8192m"
[[ -d "$repo_dir" ]] || fail "Metro checkout does not exist: $repo_dir"
[[ -d "$ide_path" ]] || fail "IDE directory does not exist: $ide_path"
[[ -d "$project_path" ]] || fail "project directory does not exist: $project_path"
repo_dir="$(cd "$repo_dir" && pwd)"
ide_path="$(cd "$ide_path" && pwd)"
project_path="$(cd "$project_path" && pwd)"
[[ -x "$repo_dir/idea-plugin/gradlew" ]] || fail "missing executable wrapper: $repo_dir/idea-plugin/gradlew"
command -v python3 >/dev/null 2>&1 || fail "Python 3 is required"

# The supervisor owns cleanup and forwards terminal signals to the Gradle process group.
# Descriptor 3 preserves the caller's stdin while Python reads the embedded launcher.
exec python3 - "$repo_dir" "$ide_path" "$project_path" "$ide_heap" "$@" 3<&0 <<'PYTHON'
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile

repo, ide, project, heap, *gradle_args = sys.argv[1:]
runner = None
pending_signal = None


def forward_signal(signum, _frame):
    global pending_signal
    pending_signal = signum
    if runner is not None:
        try:
            os.killpg(runner.pid, signum)
        except ProcessLookupError:
            pass


for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
    signal.signal(signum, forward_signal)

with tempfile.TemporaryDirectory(prefix="metro-run-local-ide-") as temporary:
    init_script = Path(temporary) / "launch.gradle.kts"
    init_script.write_text('''
import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider

// Only the selected checkout's IDE task receives this launch configuration.
val metroRepo = File(System.getenv("METRO_LOCAL_IDE_REPO")).canonicalFile
val metroProject = System.getenv("METRO_LOCAL_IDE_PROJECT")
val metroHeap = System.getenv("METRO_LOCAL_IDE_HEAP")
allprojects {
    if (projectDir.canonicalFile == File(metroRepo, "idea-plugin")) {
        tasks.withType<JavaExec>().configureEach {
            if (name == "runLocalIde") {
                maxHeapSize = metroHeap
                argumentProviders.add(CommandLineArgumentProvider { listOf(metroProject) })
                doFirst {
                    val config = (property("sandboxConfigDirectory") as DirectoryProperty).get().asFile
                    val logs = (property("sandboxLogDirectory") as DirectoryProperty).get().asFile
                    println("IDE maximum heap: $maxHeapSize")
                    println("IDE project: $metroProject")
                    println("IDE sandbox config: $config")
                    println("IDE logs: $logs/idea.log")
                }
            }
        }
    }
}
''')
    environment = os.environ.copy()
    environment.update(METRO_LOCAL_IDE_REPO=repo,
                       METRO_LOCAL_IDE_PROJECT=project,
                       METRO_LOCAL_IDE_HEAP=heap)
    command = ["./idea-plugin/gradlew", "-p", "idea-plugin", "runLocalIde",
               f"-PintellijPlatformTesting.idePath={ide}",
               "--init-script", str(init_script), *gradle_args]
    print(f"Metro checkout: {repo}\nIDE: {ide}\nProject: {project}\nIDE heap: {heap}", flush=True)
    try:
        runner = subprocess.Popen(command, cwd=repo, env=environment, stdin=3,
                                  start_new_session=True)
        if pending_signal is not None:
            forward_signal(pending_signal, None)
        result = runner.wait()
    except OSError as error:
        print(f"run-local-ide: cannot launch Gradle: {error}", file=sys.stderr)
        result = 1

# Preserve signal termination after temporary launch files have been removed.
if result < 0:
    signum = -result
    signal.signal(signum, signal.SIG_DFL)
    os.kill(os.getpid(), signum)
sys.exit(result)
PYTHON
