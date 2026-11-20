# Compiler tests

Metro uses Kotlin's compiler test framework. Each fixture is a Kotlin source file under `compiler-tests/src/test/data/`. The fixture's directory determines which JUnit suites run it.

## Creating a new test

1. Add a `.kt` file under the appropriate directory below.
2. From the repository root, generate the suites and run your fixture. For `MyTest.kt`, use the following command.

   ```shell
   ./gradlew :compiler-tests:generateTests :compiler-tests:test --tests '*MyTest*' --quiet
   ```

3. Review the expected output and commit it with the fixture and the generated changes under `compiler-tests/src/test/java/`.

Run `:compiler-tests:generateTests` whenever you add, move, or remove a fixture. For edits to an existing fixture, run `:compiler-tests:test` with the same filter. To update expected files, add `-PupdateTestData` and review the diff.

Generate the checked-in Java with the default Kotlin version. To test another version, generate first, then run `./gradlew :compiler-tests:test -Pmetro.testCompilerVersion=2.3.0 --tests '*MyTest*'`. Older Kotlin generators can rewrite every suite. A full `./metrow regen` also generates the suites with the default version.

The Compiler DevKit plugin lets you run or debug a fixture from its source file in IntelliJ IDEA and apply the resulting diff. [Compiler DevKit](https://github.com/JetBrains/kotlin-compiler-devkit)

## Directives

Directives are line comments that configure a fixture or a module within it. For example, `// METRO_JVM_ONLY` limits a fixture to the JVM, and `// MIN_COMPILER_VERSION: 2.4.20` sets its minimum Kotlin version. Each suite also provides defaults. [Metro directives](src/test/kotlin/dev/zacsweers/metro/compiler/MetroDirectives.kt), [Kotlin test infrastructure](https://github.com/JetBrains/kotlin/tree/master/compiler/test-infrastructure#directives)

## Categories

### Diagnostic

Use `src/test/data/diagnostic/` to check which diagnostics the compiler reports and where they appear. The framework compares the reported diagnostics with markers in the source. Leave a declaration unmarked to check that it compiles cleanly.

For a frontend (FIR) diagnostic, add `// RENDER_DIAGNOSTICS_FULL_TEXT` to check the message in a sibling `.diag.txt` file.

For an IR diagnostic such as a graph validation error, use these directives to check the message in a sibling `.ir.diag.txt` file.

```kotlin
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
```

Use `FIR2IR` when the fixture expects an error during IR generation. Use `BACKEND` when compilation should finish, including fixtures that expect warnings. If Metro reports an error through its message collector, the test fails with the module and message. To check the diagnostic with source markers, report it on a declaration in the module under test.

To check terminal styling, use `// DIAGNOSTICS_RENDER_MODE: RICH` in a JVM diagnostic fixture. The `.rich.ir.diag.txt` file uses readable tags for ANSI codes. [Rich diagnostic fixture](src/test/data/diagnostic/rich/MissingBindingRich.kt)

### Dump

Use a dump test when the shape of a generated declaration matters. Fixtures under `src/test/data/dump/fir/` check the frontend tree in a sibling `.fir.txt` file. Fixtures under `src/test/data/dump/ir/` check a Kotlin rendering of the transformed IR in a sibling `.kt.txt` file. Add `// DUMP_IR` to check the raw tree in `.ir.txt` as well.

To inspect generated code, paste a reproducer into one of the scratch fixtures, run its generated test, and inspect the diff. Restore the scratch files afterward. [FIR scratch fixture](src/test/data/dump/fir/scratch.kt), [IR scratch fixture](src/test/data/dump/ir/scratch.kt)

### Box

Use `src/test/data/box/` to exercise generated code. The runner compiles the fixture, calls `fun box(): String`, and expects `"OK"`. Fixtures can use Metro types and `kotlin.test` assertions without explicit imports. [Example box fixture](src/test/data/box/inject/assisted/BasicAssistedInject.kt)

The JVM and JS suites share box fixtures. The fast-init suites enable switching providers. The contribution-provider suites enable generated contribution providers. The standard JS suite follows the Gradle defaults for IR class generation and redundant mirrors. The JVM has separate suites for IR-generated classes. To run the JVM variant that omits redundant mirrors, add `-Pmetro.testOmitRedundantMirrors=true`. For cases that require a full multiplatform build, use the [integration tests](../samples/integration-tests/).

Fixtures under `box/suspend/` can call `runBlocking` for suspend blocks that complete synchronously. To use the helper in another box directory, add `// WITH_COROUTINES`.

If a fixture exercises the sample Metro compiler extensions, add `// WITH_SAMPLE_EXTENSIONS`. [Sample extension fixture](src/test/data/box/api/MetroFirExtensionSample.kt)

#### Large/stress tests

Use the `StressTest` suffix for expensive fixtures such as `LargeGraphStressTest.kt`. Run them explicitly with `metro.enableLargeTests`. This selects only stress tests and sets the default test heap to 5 GB.

```shell
./gradlew :compiler-tests:test -Pmetro.enableLargeTests --quiet
```

#### More heap for focused tests

Some Compose-heavy JS tests need more than the ordinary 2 GB test heap. Use `metro.compilerTestHeapSize` to adjust the heap independently of test selection. For example, run a fixture in the standard JS box suite with a 5 GB heap.

```shell
./gradlew :compiler-tests:test -Pmetro.compilerTestHeapSize=5g --tests '*JsBoxTestGenerated*MyTest*' --quiet
```

### Reports

Create a `.kt` fixture anywhere under `src/test/data/dump/reports/`. Add one `// CHECK_REPORTS` directive per report. For example, `// CHECK_REPORTS: merging-unmatched-replacements-fir/AppGraph` in `UnmatchedReplacement.kt` checks `_reports/UnmatchedReplacement/merging-unmatched-replacements-fir/AppGraph.txt`. [Unmatched replacement fixture](src/test/data/dump/reports/UnmatchedReplacement.kt)

Generate the suite and run the fixture. The first run writes missing goldens and fails. Review the files under `_reports/<fixture-name>/`, then rerun the test. The generator skips `_reports` directories.

In a multi-module fixture, put `// CHECK_REPORTS` between `// MODULE` and `// FILE` to check that module's output. A check before the first `// MODULE` reads the final module's output. [Multi-module report fixture](src/test/data/dump/reports/MultiModuleReports.kt)

## Multiple files and modules

Use `// FILE: Name.kt` to start a source file within a fixture. JVM fixtures can include Java files. Use `// MODULE: name(deps)(friends)(dependsOn)` to start a module. In this example, `main` depends on `lib` and contains the box function.

```kotlin
// MODULE: lib
// FILE: Example.kt
@Inject class Example {
  val message = "OK"
}

// MODULE: main(lib)
// FILE: AppGraph.kt
@DependencyGraph
interface AppGraph {
  val example: Example
}

// FILE: Main.kt
fun box(): String = createGraph<AppGraph>().example.message
```

For incremental compilation across successive builds, use the Gradle plugin's functional tests. [Incremental compilation tests](../gradle-plugin/src/functionalTest/kotlin/dev/zacsweers/metro/gradle/incremental/)

## Interop

Use `// ENABLE_DAGGER_INTEROP` (or its alias `// WITH_DAGGER`) to use Dagger annotations and runtime types. If the fixture needs Dagger-generated code, use `// ENABLE_DAGGER_KSP`. KSP can resolve types from the module's dependencies. [KSP fixture](src/test/data/box/interop/dagger/KspUsesModuleClasspath.kt)

Other interop targets have their own directives. [Interop directives](src/test/kotlin/dev/zacsweers/metro/compiler/MetroDirectives.kt)
