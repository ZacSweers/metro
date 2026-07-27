---
name: test-metro-locally
description: Publish the current Metro checkout to Maven Local and verify it in an external consumer or reproducer. Use when testing an unreleased Metro compiler or Gradle plugin change downstream, validating a GitHub issue reproduction, comparing released and local behavior, or when a task mentions `metrow publish --local`, Maven Local, or local Metro artifacts.
---

# Test Metro Locally

Use the downstream project's existing assertion and Gradle task as the source of truth. Keep repro-only edits outside the Metro worktree when practical.

Run publication and test commands only when the user explicitly requests downstream or Maven Local verification; otherwise present the plan and wait for approval as required by Metro's repository instructions.

## Verify the baseline

1. Read `AGENTS.md` in Metro and the downstream project when present.
2. Record Metro's current commit and worktree state.
3. Locate the downstream project's Metro version source, plugin repositories, dependency repositories, relevant compiler options, and exact verification task.
4. Run that task against its current released Metro version before editing the consumer. Capture the exact failure. If the baseline does not reproduce, diagnose that discrepancy before publishing.

Use a disposable clone for a remote reproducer unless the user supplied a checkout they want edited.

## Publish a unique local version

Choose a new version for every publish. Derive it from `VERSION_NAME` in Metro's `gradle.properties` and add a task-specific suffix, for example `1.4.0-2587-20260725-LOCAL01`.

From the Metro repository root, run:

```bash
./metrow publish --local --version 1.4.0-2587-20260725-LOCAL01
```

Always use `metrow`; do not call `publishToMavenLocal` directly. The wrapper supplies Metro's required publishing options, disables release signing, excludes documentation generation, and reruns publication tasks.

Record the commit and worktree state immediately before publishing. If the publish is interrupted or Metro's source changes during it, publish again with another unique version. Do not trust or reuse potentially partial artifacts.

## Point the consumer at Maven Local

Make only the configuration changes required to resolve the unique version:

- Add `mavenLocal()` before remote repositories in `pluginManagement.repositories` so Gradle can resolve the Metro plugin marker.
- Add `mavenLocal()` before remote dependency repositories at the consumer's actual repository source of truth.
- Change the Metro plugin version at its existing source, such as a plugins block, version catalog, settings plugin management, or Gradle property.
- Preserve the reported Kotlin version, compiler options, and unrelated build configuration.

Do not assume adding `mavenLocal()` to ordinary dependency repositories also affects plugin resolution.

## Run the same downstream check

Rerun the original task from the consumer's wrapper root:

```bash
./gradlew <original-task>
```

Use `--rerun-tasks` to prevent stale compiled outputs or cached tests from hiding the result ONLY if necessary. Prefer another unique local version over reusing a snapshot; if reuse is unavoidable, also refresh dependencies. Note that if testing an incremental compilation issue, do not use this flag in subsequent runs.

When the issue concerns a Metro mode such as switching providers, contribution providers, FIR/IR class generation, or provider inlining, run the same downstream check once per relevant mode without changing the reproducer's assertions.

## Confirm artifact resolution

Do not infer Maven Local usage only from a passing test. Confirm that Gradle selected the exact local version:

```bash
./gradlew buildEnvironment --quiet
./gradlew dependencyInsight --dependency dev.zacsweers.metro --configuration compileClasspath --quiet
./gradlew dependencyInsight --dependency dev.zacsweers.metro:compiler --configuration kotlinCompilerPluginClasspathMain --quiet
```

Adjust the dependency configurations for the target project when `compileClasspath` or `kotlinCompilerPluginClasspathMain` is not applicable. Verify the plugin marker and expected Metro compiler, runtime, and interop artifacts all resolve to the unique version.

## Report the result

Report:

- the Metro commit and whether its worktree was dirty;
- the unique Maven Local version;
- the baseline version, task, and exact outcome;
- the local version's outcome for each tested mode;
- the artifact-resolution evidence;
- any platform or task not exercised.

Recheck Metro's commit and worktree state after the downstream run. Keep temporary consumer edits out of the Metro change set. Do not delete Maven Local or Gradle caches; the unique version isolates the verification without destructive cleanup.
