---
name: analyze-perfetto-trace
description: Query and analyze Metro's perfetto compiler traces to find real hot spots and untraced time. Use whenever the user asks about metro compile-time perf, where time is going in a trace, or shares a perfetto screenshot.
---

## When to use

- User shares a metro perfetto trace file (`.perfetto-trace`) or a perfetto UI screenshot.
- User asks "where is time going in the metro compiler" / "why is phase X slow".
- You need to **verify** a suspected hot spot instead of guessing from the code. The profile is almost always more informative than code reading.
- Before recommending optimizations so you're fixing the thing that actually matters.

## Where metro writes traces

Metro writes a perfetto trace per compilation into each module's build dir:

```
<module>/build/metro/trace/<variant>/perfetto-<timestamp>.perfetto-trace
```

e.g. `app-scaffold/build/metro/trace/debug/perfetto-2026-04-24-01-31-36-0.perfetto-trace`.

If there are multiple, they're one per compilation invocation — pick the most recent unless the user points at a specific file.

## Producing a fresh trace from a local Metro change

Use `./metrow trace` (or the underlying `scripts/trace-project.sh` directly). It publishes Metro to mavenLocal, bumps the target project's `metro` version in `gradle/libs.versions.toml`, runs the given compile task with `-Pmetro.traceDestination=metro/trace --rerun`, locates the freshest `.perfetto-trace` (variant subdir varies by task), and copies it into `tmp/traces/<timestamp>-<version>_<task>.perfetto-trace`. The path is also written to `tmp/traces/LATEST` so you can chain with `TRACE=$(cat tmp/traces/LATEST)` in analysis.

```
./metrow trace <project-dir> <gradle-task> [version]
./metrow trace ~/dev/android/personal/CatchUp :app-scaffold:compileDebugKotlin
```

Pass `--open-in-browser` (or `--open`) to additionally launch the trace in ui.perfetto.dev — the script fetches Google's `open_trace_in_ui` helper once (cached at `tmp/open_trace_in_ui`) and fires it in the background so the UI can keep streaming the file:

```
./metrow trace --open-in-browser ~/dev/android/personal/CatchUp :app-scaffold:compileDebugKotlin
```

Equivalent direct call:

```
scripts/trace-project.sh [--open-in-browser] <project-dir> <gradle-task> [version]
```

Use this when the user asks for a "fresh trace" / "re-profile" / has just made a Metro change they want profiled against a real-world project.

## Producing a fresh trace from the in-repo benchmark project

For raw-perf iteration on a large generated project (no external repo needed), use `benchmark/trace_compile.sh`. It runs gradle-profiler against a fresh `:app:component:compileKotlin --rerun` of the benchmark project, with Metro's perfetto tracing enabled, and picks the iteration whose duration is closest to the measured-mean — so a single representative trace lands in `tmp/traces/` (and `tmp/traces/LATEST` is updated).

```
benchmark/trace_compile.sh                  # run + pick + copy
benchmark/trace_compile.sh --open-in-browser  # also open in ui.perfetto.dev
TRACE=$(cat tmp/traces/LATEST)              # chain into analysis
```

Prereqs: the benchmark project must be generated for metro mode (`cd benchmark && kotlin generate-projects.main.kts --mode metro`). gradle-profiler is auto-installed on first run. Use this for "raw compile perf on a 500-module project" iteration loops where you don't need a real-world app like CatchUp.

## Tooling

Use the **`perfetto` python library**. It's available via pip but installed against a specific python — on this machine it's Python 3.13, not the default 3.14:

```bash
/opt/homebrew/opt/python@3.13/bin/python3.13 -c "from perfetto.trace_processor import TraceProcessor; print('ok')"
```

If `python3 -c "import perfetto.trace_processor"` fails with `ModuleNotFoundError`, switch to the 3.13 binary above. `pip install perfetto` installs against whatever `python3` on PATH points to, which may not be the one actually invoked.

Do **not** try `brew install perfetto` — there is no homebrew cask/formula for it.

## Running a query — the runner pattern

Always wrap the Python in a single `-c` invocation so it stays self-contained. The boilerplate:

```bash
/opt/homebrew/opt/python@3.13/bin/python3.13 -c "
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace='<ABSOLUTE_PATH>')
r = list(tp.query('''
  <SQL>
'''))
for row in r:
    print(f'{row.dur/1e6:6.2f}ms  {row.name}')
tp.close()
"
```

Notes:
- `trace=` must be an absolute path.
- `dur` is in nanoseconds — divide by `1e6` for ms.
- `tp.query()` returns a rows iterator; wrap in `list()` if you'll iterate more than once.
- Always `tp.close()` at the end.

## The `slice` table

That's the one you'll use 95% of the time. Relevant columns:

| column      | meaning                                     |
|-------------|---------------------------------------------|
| `id`        | slice id (primary key)                      |
| `name`      | trace span name (e.g. `"Build GraphNode"`)  |
| `dur`       | duration in nanoseconds                     |
| `parent_id` | parent slice id (or NULL at top level)      |
| `ts`        | start timestamp (nanoseconds since trace start) |

## Canonical queries

### Top-level slice durations

```sql
SELECT name, dur FROM slice ORDER BY dur DESC LIMIT 30
```

Gives an ordered view of the biggest spans. Start here.

### Sum by name (when the same span fires many times)

```sql
SELECT name, SUM(dur) AS total_dur, COUNT(*) AS cnt
FROM slice GROUP BY name ORDER BY total_dur DESC LIMIT 30
```

Useful for per-class spans like `"Visit X"` that fire hundreds of times.

### Children of a named parent

```sql
WITH parent AS (SELECT id FROM slice WHERE name = 'Build binding graph' LIMIT 1)
SELECT name, dur FROM slice
WHERE parent_id = (SELECT id FROM parent)
ORDER BY dur DESC
```

This is the **most important query for finding gaps**. Compare the summed children to the parent duration:

```python
total_child = sum(row.dur for row in r)
print(f'children sum: {total_child/1e6:.2f}ms  parent: <parent dur>ms  gap: <diff>ms')
```

A large gap means there's **untraced work** inside the parent. That's where to add instrumentation or investigate.

### Only children above a threshold

```sql
SELECT name, dur FROM slice
WHERE parent_id = (SELECT id FROM slice WHERE name = 'Core transformers' LIMIT 1)
  AND dur > 1e6  -- >1ms
ORDER BY dur DESC
```

### Find the biggest single slice across the whole trace

```sql
SELECT name, dur FROM slice ORDER BY dur DESC LIMIT 1
```

### Time spent inside a category (recursive descendants)

If you want "all time spent under parent X including grandchildren":

```sql
WITH RECURSIVE descendants(id) AS (
  SELECT id FROM slice WHERE name = 'Core transformers'
  UNION
  SELECT s.id FROM slice s JOIN descendants d ON s.parent_id = d.id
)
SELECT SUM(dur) FROM slice WHERE id IN (SELECT id FROM descendants)
```

Note: summing descendants **double-counts** time (parent dur already includes children). Use this for "total CPU in this subtree" questions, not for gap math.

## The "find the gap" recipe

This is the workflow you'll use most often when someone says "X looks slow but it's a black box":

1. **Get the parent's dur** — `SELECT dur FROM slice WHERE name = '<phase>' LIMIT 1`.
2. **Sum direct children** — the "Children of a named parent" query above, sum the `dur`s.
3. **gap = parent - sum(children)**.
4. If gap is significant (>10% or >1ms), that time is spent in **untraced code** inside the phase.
5. **Open the source**, find calls that happen between/before/after the existing `trace("...")` blocks in that phase, and add `trace("...")` around them.
6. Re-profile to confirm — the gap should now be filled by the new spans.

Worked example: on a 161ms catchup build, "Build binding graph" was 11.3ms but direct children summed to 4.2ms. The 7ms gap was construction of `BindingLookup`/`IrBindingGraph`/`IrBindingStack` at the top of `generate()`, which had no trace. Wrapping that in `trace("Construct lookup & graph")` surfaced it.

## Interpreting the numbers

- **Total compile time** = the top-level span (usually `"Metro compiler"`).
- If one phase is >30% of total, that's your first target.
- "Gap percentage" inside a phase (untraced / total) tells you whether to instrument (big gap) or dig into a specific child (small gap).
- `cnt` in the sum-by-name query tells you whether to optimize per-call work (high cnt, small per-call) or one-shot cost (cnt=1, big dur).

## Don't

- **Don't recommend optimizations without looking at the trace first** when a trace is available. The profile almost always surprises you. E.g. you might guess "Process declarations" is slow; the trace might say it's 1.2ms while "Collect supertypes" is 7.5ms.
- **Don't confuse sum(descendants) with parent dur** — parent already includes all descendants. Use direct children for gap math.
- **Don't assume the whole timeline is work** — long top-level spans often contain significant I/O or lazy-init time. Instrument to confirm.

## Helpful follow-up queries after finding a hot spot

### What's the biggest single call of `name = X`?

```sql
SELECT dur FROM slice WHERE name = '<name>' ORDER BY dur DESC LIMIT 5
```

### What's nested inside a specific long instance of a span?

Get the id first (`SELECT id FROM slice WHERE name = '<name>' ORDER BY dur DESC LIMIT 1`), then:

```sql
SELECT name, dur FROM slice WHERE parent_id = <id> ORDER BY dur DESC
```

### Group by thread / process (rare, usually metro is single-threaded)

```sql
SELECT thread.name, SUM(slice.dur) AS total
FROM slice JOIN thread_track ON slice.track_id = thread_track.id
          JOIN thread ON thread_track.utid = thread.utid
GROUP BY thread.name ORDER BY total DESC
```
