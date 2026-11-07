# JVM contribution hint experiment

This workload compares the same Metro snapshot with `generate-jvm-contribution-hints-in-ir` disabled and enabled. Kotlin stays at 2.4.21. It isolates the cost of many contribution files in one module. The existing general benchmark spreads contributions across many small modules.

## Reproduce

Use JDK 25 and Gradle Profiler 0.26.0. From the repository root:

```bash
./gradlew installForFunctionalTest
python3 benchmark/generate-jvm-hints-benchmark.py tmp/jvm-hints-500
gradle-profiler --benchmark \
  --project-dir tmp/jvm-hints-500 \
  --scenario-file tmp/jvm-hints-500/hints.scenarios \
  --output-dir tmp/jvm-hints-results/large \
  fir_unrelated_body ir_unrelated_body \
  fir_unrelated_abi ir_unrelated_abi \
  fir_contribution_body ir_contribution_body \
  fir_noop ir_noop fir_clean ir_clean
./gradlew -p tmp/jvm-hints-500 :consumer:run -PirHints=true
```

Generate a small-module control with `--count 10` and a no-contribution control with `--count 0`. Use `--same-module` to put the contributions in the graph's module. Run `fir_unrelated_body ir_unrelated_body` for each control. The generator refuses to overwrite an existing directory.

The `fir_graph_body ir_graph_body` scenarios edit the graph's source file. Run them with `--same-module` to exercise discovery when the graph itself needs recompilation.

Every scenario has five warm-ups and ten measured builds. Compilation runs in-process with four workers and a 4 GiB heap. Gradle's build cache and configuration cache are disabled. The no-op scenario measures up-to-date checks; clean removes both modules' outputs before each build. Both modes use the same generated sources, snapshot artifacts, and JVM settings. The manifest records a source fingerprint.

Preserve every sample. Evaluate each scenario using the thresholds in `check-benchmark-stability.py`: relative MAD at most 10%, half-run median drift at most 10%, and at most one sample more than 20% from the median. A failed gate limits conclusions; it must remain visible with the results.

Use Python 3.10 or later to check every column of the profiler's CSV:

```bash
python3 benchmark/check-jvm-hints-results.py tmp/jvm-hints-results/large/benchmark.csv \
  --output tmp/jvm-hints-results/large/stability
```

Recorded measurements and raw benchmark data are linked from [PR #3079](https://github.com/ZacSweers/metro/pull/3079).

## Correctness and historical regressions

The functional tests execute the downstream graph after mutations and compare the final state with a clean build. They inspect Kotlin build reports to distinguish actual incremental compilation from a full rebuild, as required by [discussion #3051](https://github.com/ZacSweers/metro/discussions/3051) and [#3062](https://github.com/ZacSweers/metro/pull/3062).

| Prior issue or discussion | Constraint and regression coverage |
|---|---|
| [#149](https://github.com/ZacSweers/metro/issues/149), [#331](https://github.com/ZacSweers/metro/pull/331), [discussion #2386](https://github.com/ZacSweers/metro/discussions/2386) | Keep compiler scope and class lookup tracking. No separate persistent index or classpath scan. A new contribution invalidates an existing downstream graph. |
| [#1348](https://github.com/ZacSweers/metro/issues/1348), [#1393](https://github.com/ZacSweers/metro/issues/1393), [#1637](https://github.com/ZacSweers/metro/pull/1637) | Delete and restore contributions, rename source files, move scopes, and restore cached outputs before further incremental edits. |
| [#1772](https://github.com/ZacSweers/metro/issues/1772) | A failed scope change followed by annotation removal must still report the missing binding. |
| [#2908](https://github.com/ZacSweers/metro/pull/2908) | Reuse JVM source ownership, unique facade names, and plugin-file marker cleanup. Exercise replacement removal and switching hint modes. |
| [#2614](https://github.com/ZacSweers/metro/issues/2614), [#2624](https://github.com/ZacSweers/metro/pull/2624), [#2934](https://github.com/ZacSweers/metro/pull/2934) | Keep the existing path for KMP and KLIB targets. Compile shared contributions for JVM, JS, Wasm, and the host Native target. Windows remains a CI coverage gap on a macOS host. |
| [#2890](https://github.com/ZacSweers/metro/issues/2890), [#2977](https://github.com/ZacSweers/metro/issues/2977) | Keep graph discovery and external extensions intact. Test nested generated graph factories and overlapping native/external hints. Same-module graph discovery retains its annotation queries. |

IR hint file construction uses compiler internals. [KT-91151](https://youtrack.jetbrains.com/issue/KT-91151) discusses this approach and the lack of a supported file-generation API. This experiment remains opt-in. Coverage is limited to the tested compilers and targets.

Runtime annotation APIs and external contribution extension interfaces are unchanged. Adding the option changes the public `MetroOptions` constructor descriptor and adds a `MetroOption` enum entry. Extensions that instantiate the configuration class directly need recompilation; exhaustive option switches need the new case. The repository's binary API validation excludes `compiler` and `metro-common`, so passing `apiCheck` doesn't establish compatibility for those classes.

## Validation

The following focused checks passed locally:

```bash
# Kotlin 2.4.21: 24 new incremental cases; 9 existing cases passed and 1 legacy-only case skipped.
./gradlew :gradle-plugin:functionalTest -Pmetro.testCompilerVersion=2.4.21 \
  -Pmetro.functionalTestMaxParallelForks=1 \
  --tests '*JvmContributionHintICTests' --tests '*ContributionHintICTests' \
  --tests '*GenerateContributionProvidersICTests'

# Kotlin 2.4.21: shared contributions compile on JVM, JS, Wasm JS, and macOS Arm64 Native.
./gradlew :gradle-plugin:functionalTest -Pmetro.testCompilerVersion=2.4.21 \
  -Pmetro.functionalTestMaxParallelForks=1 -Pmetro.functionalTestKmpTarget=all \
  --tests '*JvmContributionHintPlatformTests'

# Kotlin 2.4.20: compiler fixtures/reports, option/IDE-mode tests, and public API validation.
./gradlew :compiler-tests:generateTests :compiler-tests:test \
  --tests '*JvmIrHints*' --tests '*ReportsTestGenerated*' \
  :compiler:test --tests '*MetroCompilerOptionsTest' --tests '*MetroIdeModeTest' apiCheck
```

The new JVM compiler fixtures also passed on Kotlin 2.4.21. They cover external generated contributions and nested generated graph factories. The compiler fixture run on 2.4.20 had 55 passes and eight expected configuration skips; the option/IDE-mode tests had 26 passes. KMP checks establish compilation and up-to-date behavior, not incremental compilation of common sources. Full IDE integration, Windows, and end-to-end Android application builds were not run.

IDE-parity diagnostic fixtures also passed in applicable configurations (`--tests '*DiagnosticTestGenerated*IdeaParity*'`). All five benchmark fixture shapes successfully executed `:consumer:run` with both hint modes after measurement.
