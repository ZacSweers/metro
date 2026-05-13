#!/usr/bin/env bash
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0
#
# trace_compile.sh
#
# One-shot perfetto-trace iteration tool for the benchmark project.
#
# Runs gradle-profiler against the `trace_compile` scenario (a fresh
# :app:component:compileKotlin --rerun with Metro's perfetto tracing
# enabled), then picks the iteration whose duration is closest to the
# measured-mean and copies its .perfetto-trace into tmp/traces/ for
# analysis with the analyze-perfetto-trace skill.
#
# Metro accumulates traces in build/metro/trace/main/ rather than
# wiping per compile, so trace mtime order matches CSV row order
# (warm-ups first, then measured); we index into that list to find
# the trace for the chosen iteration.
#
# Usage:
#   benchmark/trace_compile.sh [--open-in-browser]
#
# Output:
#   - prints the path to the representative trace (last line, for $())
#   - writes the same path to <repo-root>/tmp/traces/LATEST
#
# Prereq: benchmark project generated for metro mode:
#   cd benchmark && kotlin generate-projects.main.kts --mode metro

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TRACES_DIR="$REPO_ROOT/tmp/traces"
LATEST_FILE="$TRACES_DIR/LATEST"
SCENARIO="trace_compile"

OPEN_IN_BROWSER=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --open-in-browser|--open) OPEN_IN_BROWSER=true; shift ;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
      exit 0
      ;;
    *)
      echo "ERROR: unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

# -- Validate prerequisites ------------------------------------------------
if [[ ! -d "$SCRIPT_DIR/app/component" ]]; then
  echo "ERROR: benchmark project not generated. Run:" >&2
  echo "    cd $SCRIPT_DIR && kotlin generate-projects.main.kts --mode metro" >&2
  exit 1
fi

# Source installer (provides install_gradle_profiler & get_gradle_profiler_bin)
# shellcheck source=install-gradle-profiler.sh
source "$SCRIPT_DIR/install-gradle-profiler.sh"
GP_BIN="$(get_gradle_profiler_bin)"
if [[ ! -x "$GP_BIN" ]]; then
  if command -v gradle-profiler >/dev/null 2>&1; then
    GP_BIN="$(command -v gradle-profiler)"
  else
    install_gradle_profiler
    GP_BIN="$(get_gradle_profiler_bin)"
  fi
fi
if [[ ! -x "$GP_BIN" ]]; then
  echo "ERROR: gradle-profiler not available" >&2
  exit 1
fi

# -- Wipe stale traces from prior runs (Metro now accumulates) -------------
TRACE_DEST="$SCRIPT_DIR/app/component/build/metro/trace"
rm -rf "$TRACE_DEST"

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
PROFILE_OUT="$REPO_ROOT/tmp/benchmark-trace/$TIMESTAMP"
mkdir -p "$PROFILE_OUT" "$TRACES_DIR"

# -- Run gradle-profiler ---------------------------------------------------
cd "$SCRIPT_DIR"
echo "==> Running gradle-profiler scenario: $SCENARIO"
"$GP_BIN" \
  --benchmark \
  --scenario-file benchmark.scenarios \
  --output-dir "$PROFILE_OUT" \
  --gradle-user-home "$HOME/.gradle" \
  "$SCENARIO"

# -- Locate the produced CSV (gradle-profiler writes it directly to
#    --output-dir, but tolerate a nested layout). --------------------------
CSV=$(find "$PROFILE_OUT" -maxdepth 3 -type f -name benchmark.csv 2>/dev/null | head -1)
if [[ -z "$CSV" ]]; then
  echo "ERROR: benchmark.csv not produced under $PROFILE_OUT" >&2
  exit 1
fi

# -- Pick the trace whose iteration time is closest to the mean ------------
PICK=$(python3 - "$CSV" "$TRACE_DEST" <<'PY'
import csv, glob, os, sys
csv_path, trace_root = sys.argv[1], sys.argv[2]

measured = []
warmups = 0
with open(csv_path) as fh:
    for row in csv.reader(fh):
        if not row:
            continue
        label = row[0].strip()
        if label.startswith("warm-up build"):
            warmups += 1
        elif label.startswith("measured build") and len(row) > 1:
            try:
                measured.append(float(row[1]))
            except ValueError:
                pass

if not measured:
    sys.exit("no measured iterations parsed from " + csv_path)

mean = sum(measured) / len(measured)
best = min(range(len(measured)), key=lambda i: abs(measured[i] - mean))

traces = sorted(
    glob.glob(os.path.join(trace_root, "**", "*.perfetto-trace"), recursive=True),
    key=os.path.getmtime,
)
needed = warmups + best
if len(traces) <= needed:
    sys.exit(f"expected at least {needed + 1} traces under {trace_root}, found {len(traces)}")

print(traces[needed])
print(f"{mean:.0f}")
print(f"{measured[best]:.0f}")
print(warmups)
print(len(measured))
PY
)

# Bash 3.2-compatible split (default macOS /bin/bash):
SRC_TRACE=""; MEAN_MS=""; PICKED_MS=""; WARMUPS_N=""; ITERS_N=""
{
  IFS= read -r SRC_TRACE
  IFS= read -r MEAN_MS
  IFS= read -r PICKED_MS
  IFS= read -r WARMUPS_N
  IFS= read -r ITERS_N
} <<EOF
$PICK
EOF

if [[ -z "$SRC_TRACE" || ! -f "$SRC_TRACE" ]]; then
  echo "ERROR: could not pick a trace" >&2
  exit 1
fi

# -- Copy into tmp/traces/ -------------------------------------------------
DST_TRACE="$TRACES_DIR/${TIMESTAMP}-benchmark-${SCENARIO}.perfetto-trace"
cp "$SRC_TRACE" "$DST_TRACE"
echo "$DST_TRACE" > "$LATEST_FILE"

echo
echo "==> Iterations: $WARMUPS_N warm-up + $ITERS_N measured"
echo "==> Mean:       ${MEAN_MS} ms"
echo "==> Closest:    ${PICKED_MS} ms"
echo "==> Source:     $SRC_TRACE"
echo "==> Copied:     $DST_TRACE"
echo "==> LATEST →    $LATEST_FILE"

# -- Optionally open in ui.perfetto.dev ------------------------------------
if [[ "$OPEN_IN_BROWSER" == true ]]; then
  PERFETTO_TOOL="$REPO_ROOT/tmp/open_trace_in_ui"
  if [[ ! -x "$PERFETTO_TOOL" ]]; then
    echo "==> Fetching open_trace_in_ui helper (one-time)"
    mkdir -p "$(dirname "$PERFETTO_TOOL")"
    curl -fsSL -o "$PERFETTO_TOOL" \
      https://raw.githubusercontent.com/google/perfetto/main/tools/open_trace_in_ui
    chmod +x "$PERFETTO_TOOL"
  fi
  echo "==> Opening trace in ui.perfetto.dev (background server)"
  nohup "$PERFETTO_TOOL" -i "$DST_TRACE" >/dev/null 2>&1 &
  OPENER_PID=$!
  disown "$OPENER_PID" 2>/dev/null || true
  echo "==> open_trace_in_ui pid: $OPENER_PID  (kill $OPENER_PID when done)"
fi

echo
echo "$DST_TRACE"
