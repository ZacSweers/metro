---
name: test-metro-ide-locally
description: Run Metro's IDE plugin from source in a sandboxed local IDE against a consumer project. Use for IDE behavior checks, refresh performance traces, or baseline/change comparisons with runLocalIde.
---

# Test Metro IDE Locally

Use the repository's `runLocalIde` runner for every IDE session in this workflow. It installs the
plugin built from the chosen Metro checkout into a sandbox. Keep the runner session alive while
using that IDE. The runner pre-installs the checkout's plugin. No Marketplace or disk installation
is needed.

## Match the runs

Record the Metro commit and dirty worktree state, consumer checkout, graph declaration, IDE build,
Java runtime, heap, and Metro settings. Use the same consumer source and installed IDE runtime for
baseline and comparison runs. Keep their sandbox directories separate.

Decide whether the measurement covers initial import/indexing, the first graph refresh after
startup, or repeated warm refreshes. Compare the same state on both sides. Record any previous
refreshes and source edits. Preserve existing caches unless the user requested a fresh sandbox.
A restarted IDE can still have warm persistent indexes.

For a large Android project, allocate an 8g IDE heap before its first import. Match the heap on both
runs. IDE heap and Gradle daemon heap are separate settings.

## Launch through the runner

From the Metro repository root:

```bash
scripts/run-local-ide.sh \
  --ide-path "/Applications/Android Studio.app" \
  --project /path/to/consumer \
  --heap 8g
```

The [runner script](../../../scripts/run-local-ide.sh) defaults to an 8g IDE heap. Use
`--repo /path/to/baseline/metro` to run another Metro checkout with the same helper. `--ide-path`
selects the runner's installed IDE runtime. The script passes the project to `runLocalIde` and prints
the effective heap and sandbox locations. Read its `--help` for optional Gradle arguments.

Verify the launched process's heap and log path. Use the runner's reported paths; sandbox layouts
vary by IDE and Gradle plugin version. An observed layout is
`idea-plugin/.intellijPlatform/sandbox/metro-idea-plugin/<IDE-build>/log_runLocalIde`.

Never launch the installed IDE directly during this workflow. This includes `open -a`, app bundle
executables, and using CUA `getApp` with an Android Studio name, bundle ID, or path to launch it.
Those routes can open the user's normal IDE profile without the sandbox plugin.

## Bind UI control and finish import

Inspect the available running surfaces before binding UI automation. Confirm that the selected
window belongs to the runner process and contains the intended project. `getApp` can launch an app,
so an installed IDE name or path alone does not establish the sandbox window's identity.

If native inventory reports a locked Mac, ask the user to unlock it and keep the existing runner.
A locked session can return zero accessibility windows for a live IDE.

If CUA cannot bind the runner, use process-targeted AppleScript when authorized. First identify the
live runner PID and verify its command line with `ps -p "$runner_pid" -o pid=,command=`. Confirm the
expected IDE Java executable and this run's sandbox arguments. Recheck after every runner restart.
A process name such as `java` or `MainWrapper` is insufficient to identify the sandbox.

Pass that verified PID to `System Events`. This reads the existing process and its windows:

```bash
osascript - "$runner_pid" <<'APPLESCRIPT'
on run argv
  set runnerPid to (item 1 of argv) as integer
  tell application "System Events"
    set runnerProcess to first application process whose unix id is runnerPid
    return {name of runnerProcess, name of every window of runnerProcess}
  end tell
end run
APPLESCRIPT
```

Wait for the expected onboarding or project window before acting. Scope direct accessibility button
clicks, value changes, and row selection to that same process selector. Avoid name-only
`getApp("Android Studio")`; it can launch the normal installed app. If the process exits, identify and
verify the new runner PID. If authorization or accessibility access is unavailable, report the
blocker and continue read-only log inspection.

Never use `System Events` `keystroke` or `key code`, even inside a process-targeted `tell` block.
These commands use global keyboard focus and can act on another app. Never use global `CGEventPost`
or any other focus-based keyboard fallback.

For a popup that needs Return, read the verified PID's current accessibility popup and set `selected`
of the observed target row to `true`. Recheck the runner PID and send Return directly to it with this
helper. Use key code 53 for Escape when dismissing an observed runner popup.

```bash
runner_keycode=36 # Return; use 53 for Escape.
osascript -l JavaScript - "$runner_pid" "$runner_keycode" <<'JXA'
ObjC.import('CoreGraphics');
function run(argv) {
  const runnerPid = Number(argv[0]);
  const keyCode = Number(argv[1]);
  if (!Number.isInteger(runnerPid) || runnerPid <= 0 || ![36, 53].includes(keyCode)) {
    throw new Error('Pass a verified runner PID and Return or Escape key code.');
  }
  const source = $.CGEventSourceCreate($.kCGEventSourceStatePrivate);
  for (const isDown of [true, false]) {
    const event = $.CGEventCreateKeyboardEvent(source, keyCode, isDown);
    $.CGEventSetFlags(event, 0);
    $.CGEventPostToPid(runnerPid, event);
  }
}
JXA
```

Keep the private event source and zero flags. Null-source events had no effect in this runner.
Verify the expected popup result through the same PID's accessibility state. If delivery fails,
stop keyboard attempts and inspect the runner. Do not switch to global keyboard delivery.

Complete first-run onboarding in the sandbox, open the supplied project, and let Gradle sync finish.
Check the sync result and IDE indexing status. An open editor does not prove that project import
succeeded. Resolve or report sync errors before collecting performance results. Wait for smart mode
and idle indexing before measuring a graph refresh. Keep first-import timing separate.

## Load graph data and record a refresh

Read [Performance Tracing](../../../idea-plugin/README.md#performance-tracing) for the current capture
behavior. In `Settings > Tools > Metro`, open **Debugging/Experimental** and enable **Enable debugging
options**. Match these settings between runs:

- **Analysis pool size**; use 1 unless the comparison calls for another value.
- **Include thread activity**; leave it off for the basic timing comparison.
- **Resolve bindings from compiled dependencies**.
- **Automatically refresh graphs and bindings after code changes**.
- **Automatically validate the pinned graph after code changes**.

Automatic refresh and validation can add overlapping work. Set them consistently and record the
chosen values. Debugging must stay enabled for the configured analysis pool size to apply.

Open `View > Tool Windows > Metro`. Refresh loads graph contexts across the project. The graph
selector filters the displayed context and its extensions. Record the chosen context for validation
and navigation checks. For a first-refresh measurement, start with **Refresh with tracing** before
any untraced load. For a warm measurement, let the initial refresh finish before starting a capture.

For refresh timing, use **More > Refresh with tracing** in the Metro tool window. This menu provides
the preferred accessible route. The same action is available by right-clicking **Refresh** and
choosing **Refresh with tracing**. It starts recording, submits the refresh, awaits completion, saves
the trace, and opens the result. Let that flow finish. It needs no manual Start/Stop actions or
Refresh-button polling.

The capture creates an empty `metro-ide-*.perfetto-trace` in the runner's IDE log directory at startup.
File existence alone does not establish completion. Wait for the automatically opened trace or a
saved nonempty capture with the completion metadata below. If UI automation cannot invoke the
action, resolve that interaction before continuing the timing experiment. Preserve the intended
capture mode.

Run one capture at a time. Verify the previous capture's completion metadata before invoking the next
refresh. Java accessibility can report popup rows as enabled while their actions are disabled, so an
AX `enabled` value does not establish readiness. Re-read the current popup labels and confirm
**Refresh with tracing** before selecting its row.

Require the `refresh` operation's `outcome` to be `published` and `capture.finish` to have
`stop_reason=completed` and `partial=false` for a successful measured refresh.
A failed refresh can finish recording normally. The 10-minute capture deadline can produce a partial
trace; report it as partial. Check `dropped_events` too. The detail timeline holds 20,000 events.
Current builds retain up to 1,024 additional enclosing/completion records separately. Earlier builds
can lose those records when the detail budget fills, even when `partial=false`. Missing completion
metadata leaves publication and elapsed time unverified. `capture.overview` measures retained events
and cannot fill that gap.

Use **Start Metro Performance Trace** for operations outside an explicit refresh, such as editor
navigation. Begin recording before triggering the operation. Its recording status is **Tracing
enabled…**. It automatically ends admission after 60 seconds and saves once admitted work drains;
**Stop Metro Performance Trace** can end admission sooner. Account for graph work that happened
before recording and use the same capture mode on both sides. These captures require their own
completeness checks.

Refresh being enabled only establishes that no explicit refresh remains pending. Hidden status
panels retain their old text, so accessibility output can include stale progress messages. Check
visible graph and binding rows as UI evidence. Confirm publication through the trace outcome.

## Read refresh and indexing costs separately

In Perfetto, use `debug.operation`, `debug.operation_id`, and `debug.parent_operation_id` to follow
operations. For **Refresh with tracing**, measure the enclosing `refresh` duration. Manual captures
can contain `index.candidate` operations; check for `outcome=published` and report that narrower
measurement boundary. Inspect source scanning, class resolution, cache counts, and cancellation/retry
details to explain the change. Parent spans include their children. Concurrent worker durations can
overlap; summed stage or item durations can exceed elapsed refresh time.

IDEA also writes [indexing diagnostics](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html#performance-metrics)
under the runner log directory. Inspect `indexing-diagnostic/<project>/diagnostic-*.json` and the
adjacent HTML report. For `type == "DumbIndexing"`, inspect
`projectIndexingActivityHistory.totalStatsPerIndexer[]` and identify each measured index by its ID.

Record `totalNumberOfFiles`, `totalFilesSize`, and `partOfTotalIndexingTime.part / 1e6` in milliseconds.
That duration accumulates indexer wall time across worker threads. The JSON also uses the legacy
field name `totalCpuTime`; the HTML describes these measurements as wall time. Keep this accumulated
work separate from `times.totalWallTimeWithoutPauses` and user-visible refresh latency. Shared file
loading and index application overhead are separate totals. An absent entry in a warm session does
not establish zero maintenance cost.

## Report evidence

Provide the matched runtime/settings, source revisions and dirty state, graph, cache state, completed
or partial outcome, trace paths, and indexing-report paths. Compare elapsed refresh time and relevant
work counts. Verify that the expected graph and representative binding navigation still work.
Describe any uncompleted import, UI-binding, or capture step explicitly. Keep accumulated indexer
work and elapsed indexing duration separate when explaining a speedup.
