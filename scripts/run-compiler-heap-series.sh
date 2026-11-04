#!/bin/bash
set -euo pipefail

export EXPERIMENT_SKIP_LEGACY=true
export EXPERIMENT_THREADS=4
export EXPERIMENT_SHARD=

for trial in baseline-start larger-heap parallel-collector baseline-end; do
  export EXPERIMENT_HEAP=2g
  export EXPERIMENT_GC=
  case "$trial" in
    larger-heap) export EXPERIMENT_HEAP=4g ;;
    parallel-collector)
      export EXPERIMENT_HEAP=4g
      export EXPERIMENT_GC=parallel
      ;;
  esac
  trial_dir="$RUNNER_TEMP/compiler-trials/$trial"
  mkdir -p "$trial_dir"
  export EXPERIMENT_TRIAL_DIR="$trial_dir"
  python3 - <<'PY'
import json
import os
from pathlib import Path
profiles = [str(path) for path in Path('build/reports/profile').glob('*')]
Path(os.environ['EXPERIMENT_TRIAL_DIR'], 'profiles-before.json').write_text(json.dumps(profiles))
PY
  rm -f compiler-tests/build/compiler-ci.jfr
  printf 'Starting compiler trial: %s\n' "$trial"
  set +e
  bash scripts/run-ci-experiment.sh
  trial_status=$?
  set -e
  cp "$RUNNER_TEMP/timing.txt" "$RUNNER_TEMP/workload.log" "$trial_dir/"
  python3 - <<'PY'
import json
import os
from pathlib import Path
import shutil
trial = Path(os.environ['EXPERIMENT_TRIAL_DIR'])
before = set(json.loads((trial / 'profiles-before.json').read_text()))
paths = list(Path('compiler-tests/build/test-results/test').glob('*.xml'))
paths += [Path('compiler-tests/build/compiler-ci.jfr')]
paths += [path for path in Path('build/reports/profile').rglob('*') if str(path) not in before]
for path in paths:
    if not path.is_file():
        continue
    destination = trial / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)
PY
  if (( trial_status != 0 )); then
    exit "$trial_status"
  fi
done
