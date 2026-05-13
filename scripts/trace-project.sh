#!/usr/bin/env bash
#
# trace-project.sh
#
# Roundtrips a local Metro change through an arbitrary Gradle project to
# produce a fresh perfetto trace:
#   1. Publishes Metro to mavenLocal with a bumped LOCAL version.
#   2. Updates the target project's gradle/libs.versions.toml metro version.
#   3. Runs the given Gradle compile task with
#      -Pmetro.traceDestination=metro/trace and --rerun so the compile is
#      fresh and the plugin writes traces under build/metro/trace/<variant>/.
#   4. Locates the freshest .perfetto-trace produced during the run, extracts
#      its `<yyMMdd-HHmmss>` id prefix, and finds every sibling file sharing
#      that id. Metro emits one file per FIR session and one per IR fragment.
#   5. Copies all matching files into tmp/traces/ (gitignored). Filenames
#      preserve phase + module info (e.g. `__ir--app-scaffold.perfetto-trace`).
#      LATEST points at the IR file when one exists.
#
# Usage:
#   scripts/trace-project.sh [options] <project-dir> <gradle-task> [version]
#
# Options:
#   --open-in-browser   After producing the trace, open it in ui.perfetto.dev
#                       by starting a local HTTP server (via perfetto's
#                       open_trace_in_ui helper, downloaded on first use).
#                       The server keeps running in the background so the UI
#                       can keep pulling chunks — kill it when you're done.
#
# Examples:
#   scripts/trace-project.sh \
#     ~/dev/android/personal/CatchUp :app-scaffold:compileDebugKotlin
#
#   scripts/trace-project.sh --open-in-browser \
#     ~/dev/MyApp :app:compileKotlinJvm 1.0.0-LOCAL500
#
# Produced traces are copied into tmp/traces/ (and a `LATEST` pointer
# file is updated) so downstream analysis tooling can find them. tmp/ is
# gitignored so nothing leaks into VCS.
#
# Requirements on the target project:
#   - mavenLocal() in its repositories
#   - gradle/libs.versions.toml with a top-level `metro = "..."` entry
#   - gradlew wrapper
#
# Env vars:
#   METRO_DIR   Overrides the auto-detected metro repo root.

set -euo pipefail

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
}

# -- Parse flags -----------------------------------------------------------
OPEN_IN_BROWSER=false
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --open-in-browser|--open)
      OPEN_IN_BROWSER=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      POSITIONAL+=("$@")
      break
      ;;
    -*)
      echo "ERROR: unknown flag: $1" >&2
      usage
      exit 1
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done
set -- "${POSITIONAL[@]}"

if [[ $# -lt 2 ]]; then
  usage
  exit 1
fi

PROJECT_DIR="$(cd "$1" && pwd)"
GRADLE_TASK="$2"
VERSION_ARG="${3:-}"

# Auto-detect metro repo dir from this script's location (scripts/trace-project.sh).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METRO_DIR="${METRO_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

VERSIONS_TOML="$PROJECT_DIR/gradle/libs.versions.toml"
# tmp/ is gitignored at the repo root, so trace output stays out of VCS.
TRACES_DIR="$METRO_DIR/tmp/traces"
LATEST_FILE="$TRACES_DIR/LATEST"
COUNTER_FILE="$TRACES_DIR/.counter"
PERFETTO_TOOL="$METRO_DIR/tmp/open_trace_in_ui"

# -- Validate inputs -------------------------------------------------------
if [[ ! -x "$METRO_DIR/metrow" ]]; then
  echo "ERROR: $METRO_DIR/metrow not found — is METRO_DIR correct?" >&2
  exit 1
fi
if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "ERROR: project dir not found: $PROJECT_DIR" >&2
  exit 1
fi
if [[ ! -f "$VERSIONS_TOML" ]]; then
  echo "ERROR: $VERSIONS_TOML not found (expected gradle/libs.versions.toml)" >&2
  exit 1
fi
if ! grep -qE '^metro = "[^"]*"$' "$VERSIONS_TOML"; then
  echo "ERROR: no 'metro = \"...\"' entry found in $VERSIONS_TOML" >&2
  exit 1
fi
if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
  echo "ERROR: $PROJECT_DIR/gradlew not found or not executable" >&2
  exit 1
fi

mkdir -p "$TRACES_DIR"

# -- Resolve version -------------------------------------------------------
if [[ -n "$VERSION_ARG" ]]; then
  VERSION="$VERSION_ARG"
else
  if [[ -f "$COUNTER_FILE" ]]; then
    PREV=$(<"$COUNTER_FILE")
  else
    # `|| true` so a no-match grep under set -e + pipefail doesn't abort the
    # script (target projects that pin a non-LOCAL metro version trip this).
    CURRENT=$(grep -E '^metro = "1\.0\.0-LOCAL[0-9]+"' "$VERSIONS_TOML" 2>/dev/null \
              | head -1 \
              | sed -E 's/.*LOCAL([0-9]+).*/\1/' || true)
    PREV=${CURRENT:-999}
  fi
  NEXT=$((PREV + 1))
  echo "$NEXT" > "$COUNTER_FILE"
  VERSION="1.0.0-LOCAL$NEXT"
fi

echo "==> Project:  $PROJECT_DIR"
echo "==> Task:     $GRADLE_TASK"
echo "==> Version:  $VERSION"

# -- Publish Metro to mavenLocal -------------------------------------------
echo "==> Publishing Metro to mavenLocal"
pushd "$METRO_DIR" > /dev/null
./metrow publish --local --version "$VERSION"
popd > /dev/null

# -- Update target project's metro version ---------------------------------
echo "==> Updating $VERSIONS_TOML → $VERSION"
python3 - "$VERSIONS_TOML" "$VERSION" <<'PY'
import re, sys, pathlib
path = pathlib.Path(sys.argv[1])
version = sys.argv[2]
text = path.read_text()
# Replace the top-level `metro = "..."` (versions section); do not touch
# plugin/module ref lines like `metro = { id = "...", version.ref = "metro" }`.
new = re.sub(r'(?m)^metro = "[^"]*"$', f'metro = "{version}"', text, count=1)
if new == text:
    sys.exit("Failed to update metro version line in " + str(path))
path.write_text(new)
PY

# -- Run the compile --------------------------------------------------------
# Create a sentinel file so we can find any trace file produced strictly
# after this point. This is resilient to the output path varying by
# compile-task variant (debug/main/jvmMain/etc.).
SENTINEL=$(mktemp -t metro-trace-sentinel.XXXXXX)
trap 'rm -f "$SENTINEL"' EXIT

echo "==> Running $GRADLE_TASK with metro.traceDestination=metro/trace (forced rerun)"
pushd "$PROJECT_DIR" > /dev/null
./gradlew "$GRADLE_TASK" \
  -Pmetro.traceDestination=metro/trace \
  --rerun \
  --quiet
popd > /dev/null

# -- Locate the freshest trace produced ------------------------------------
# Metro now emits one trace file per FIR session and one per IR fragment, all
# sharing a `<yyMMdd-HHmmss>` id prefix in the basename:
#   <id>-fir-<moduleName>.perfetto-trace
#   <id>-ir-<moduleName>.perfetto-trace
# The target dir lives at <projectOrSubmodule>/build/metro/trace/<variant>/
# but the submodule + variant segments vary with $GRADLE_TASK, so we find by
# mtime: take the freshest `.perfetto-trace` produced after the sentinel,
# extract its id prefix, then collect every sibling file with the same id.
echo "==> Locating fresh traces"
FRESHEST=$(
  find "$PROJECT_DIR" \
       -type f \
       -name '*.perfetto-trace' \
       -newer "$SENTINEL" \
       -print 2>/dev/null \
  | while IFS= read -r f; do
      stat -f '%m %N' "$f" 2>/dev/null
    done \
  | sort -rn \
  | head -1 \
  | cut -d' ' -f2-
)

if [[ -z "${FRESHEST:-}" ]]; then
  echo "ERROR: no .perfetto-trace file was produced under $PROJECT_DIR" >&2
  echo "  Did the compile actually run (not up-to-date)?" >&2
  echo "  Is the Metro plugin applied to the target module?" >&2
  exit 1
fi

SRC_DIR=$(dirname "$FRESHEST")
SRC_BASENAME=$(basename "$FRESHEST")
# Pull the `<yyMMdd-HHmmss>` id prefix out of the basename. Falls back to the
# whole basename if the format doesn't match (e.g. older traces with a
# different naming scheme), in which case we just copy that one file.
ID_PREFIX=$(echo "$SRC_BASENAME" | sed -E 's/^([0-9]{6}-[0-9]{6})-.*/\1/')
if [[ "$ID_PREFIX" == "$SRC_BASENAME" ]]; then
  echo "==> Warning: filename '$SRC_BASENAME' doesn't match <id>-<phase>-<module>.perfetto-trace; copying just the freshest" >&2
  SIBLINGS=("$FRESHEST")
else
  SIBLINGS=()
  while IFS= read -r f; do
    SIBLINGS+=("$f")
  done < <(find "$SRC_DIR" -maxdepth 1 -type f -name "${ID_PREFIX}-*.perfetto-trace" | sort)
fi

# -- Copy traces into tmp/traces/ ------------------------------------------
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
SAFE_TASK=$(echo "$GRADLE_TASK" | tr ':/' '__')
DST_BASE="${TIMESTAMP}-${VERSION}${SAFE_TASK}"

LATEST_DST=""
for src in "${SIBLINGS[@]}"; do
  src_basename=$(basename "$src")
  # Strip the id prefix so the destination name keeps phase + module info,
  # e.g. `__ir--app-scaffold.perfetto-trace`.
  if [[ "$src_basename" == "$ID_PREFIX-"* ]]; then
    suffix="${src_basename#${ID_PREFIX}-}"
    dst="$TRACES_DIR/${DST_BASE}__${suffix}"
  else
    dst="$TRACES_DIR/${DST_BASE}.perfetto-trace"
  fi
  cp "$src" "$dst"
  echo "==> Copied: $src -> $dst"
  # Prefer the IR file as the LATEST target; fall back to whatever's last.
  if [[ "$src_basename" == "${ID_PREFIX}-ir-"* || -z "$LATEST_DST" ]]; then
    LATEST_DST="$dst"
  fi
done

echo "$LATEST_DST" > "$LATEST_FILE"
echo "==> LATEST → $LATEST_FILE -> $LATEST_DST"

# Keep DST_TRACE in scope for downstream --open-in-browser logic.
DST_TRACE="$LATEST_DST"

# -- Optionally open in ui.perfetto.dev ------------------------------------
if [[ "$OPEN_IN_BROWSER" == "true" ]]; then
  # Cache perfetto's open_trace_in_ui helper under tmp/ so repeated runs are
  # fast and nothing pollutes the repo. Download on first use.
  if [[ ! -x "$PERFETTO_TOOL" ]]; then
    echo "==> Fetching open_trace_in_ui helper (one-time)"
    mkdir -p "$(dirname "$PERFETTO_TOOL")"
    curl -fsSL -o "$PERFETTO_TOOL" \
      https://raw.githubusercontent.com/google/perfetto/main/tools/open_trace_in_ui
    chmod +x "$PERFETTO_TOOL"
  fi
  echo "==> Opening in ui.perfetto.dev (local server will keep running in background)"
  # Fire-and-forget: the helper opens the UI then keeps a local HTTP server
  # alive so ui.perfetto.dev can keep streaming the file. User kills it when
  # done. Use nohup so it survives script exit / terminal close.
  nohup "$PERFETTO_TOOL" -i "$DST_TRACE" > /dev/null 2>&1 &
  OPENER_PID=$!
  disown "$OPENER_PID" 2>/dev/null || true
  echo "==> open_trace_in_ui server pid: $OPENER_PID  (kill $OPENER_PID when done)"
fi

echo "$DST_TRACE"
