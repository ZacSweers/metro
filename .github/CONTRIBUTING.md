# Contributing to Metro

Metro welcomes contributions! Small contributions like documentation improvements, small obvious fixes, etc are always good and don't need prior discussion. I liberally leave TODO comments in code that don't quite meet the standard of an issue but are still things worth improving :). For larger functionality changes or features, please raise a discussion or issue first before starting work.

## Development

Local development with Metro is fairly straightforward. You should be able to clone the repo and open it in IntelliJ as a standard Gradle project.

If you get an issue with the Android gradle plugin version being too new, you can follow the instructions [here](https://youtrack.jetbrains.com/issue/IDEA-348937/2024.1-Beta-missing-option-to-enable-sync-with-future-AGP-versions#focus=Comments-27-11721710.0-0).

> [!TIP]
> - This project uses a specific JDK (see the `jdk` version in `libs.versions.toml`). If you don't have that JDK installed, you can likely change it to whatever JDK suits your needs as long as it's compatible with the `jvmTarget` version defined in `libs.versions.toml`.
> - This project uses up-to-date versions of the Android Gradle Plugin that may not be compatible with IntelliJ stable out of the box. To enable it, add the following properties to Help > Edit Custom Properties and restart ([ref](https://youtrack.jetbrains.com/issue/IDEA-348937)).
>     ```properties
>     idea.is.internal=true
>     gradle.ide.support.future.agp.versions=true
>     ```

There are a few primary subprojects to consider.

1. `:compiler` — Metro's compiler plugin implementation lives. This includes compiler-supported interop features too.
2. `:compiler-tests` — Compiler tests using JetBrains' official compiler testing infrastructure.
3. `:gradle-plugin` — Metro's companion Gradle plugin implementation. Mostly just an extension API and compiler plugin wiring with KGP.
    - This is also where incremental compilation integration tests live!
4. `:runtime` — Metro's core multiplatform runtime API. This is mostly annotations plus some small runtime APIs.
5. `:interop-dagger` — An ancillary set of JVM-only Dagger-specific runtime APIs for interop with Dagger.
6. `samples/` — A separate Gradle project that contains several sample projects. This _includes_ the core artifacts as an included build. You can add this project in IntelliJ as another Gradle project to support developing both. There are also some integration tests in here.
  - `:integration-tests` — self-explanatory.
  - `:compose-viewmodels` — A multi-module integration test.

To include the `samples` project in IntelliJ, open the Gradle tab and just add it as another project. It depends on the regular artifacts as included build dependencies.

There is a useful `./metrow` helper CLI that can perform a few common commands across the various subprojects. See its `--help` usage for more details.

There are some standard IntelliJ run configurations checked in to `.run` that should be automatically picked up, covering a few common test scenarios.

> [!TIP]
> Before submitting a PR, it is useful to run `regen` and `check`.

* `./metrow format` — Runs all code formatters.
* `./metrow regen` — Regenerates `.api` files and runs all code formatters.
* `./metrow check` — Runs checks across all included Gradle projects (including samples and the Gradle plugin).
* `./metrow publish --local --version x.y.z` — Publishes to maven local with the specified `x.y.z` version (replace this with whatever you want, like `1.0.0-LOCAL01`.)

## Testing

Tests are spread across a few areas.

* `compiler-tests/` — New compiler tests using JetBrains' official compiler testing infrastructure. If possible, write new compiler tests in here! See its README for more details on how they work.
* `compiler/src/test/` — Core (but legacy) compiler tests. **While many tests are here, new tests should ideally use `compiler-tests`**. These should be focused primarily on _error_ testing but can also perform limited functional testing.
* `gradle-plugin/src/functionalTest` — Integration Gradle tests, primarily focused on exercising different incremental compilation scenarios.
* `samples/` — Some samples have tests! This is useful to assert that these samples work as expected.
    * `integration-tests/` — Integration tests. These should only be functional in nature and not test error cases (error cases won't compile!). Note that new integration tests should usually be written in `compiler-tests`. Some scenarios, such as IC tests, may make more sense to write here.

### Versions

To test different versions of Kotlin (backed by the `:compiler-compat` system, see its README for more details), set the `metro.testCompilerVersion` property to the Kotlin version you want to test. This is automatically used by all the tests in `:compiler`, `:compiler-tests`, and `:gradle-plugin` functional tests when specified.

### Local Publishing

To publish to a local maven repo, run this:

```bash
./metrow publish --local --version 1.0.0-LOCAL01 # whatever version you want
```

## Debugging

There are a few different scenarios for debugging.

### 1. Direct compiler debugging

This is the ideal setup, meaning you're connecting a debugger directly to a compilation. You can do this easily in `:compiler` and `:compiler-test` tests.

Some common places to breakpoint are

- If you're seeing graph transformation fail, breakpoint the `catch` here: https://github.com/ZacSweers/metro/blob/bc565b3daffda81c11826dc39330d61d9385923c/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/transformers/DependencyGraphTransformer.kt#L689. Note that just catches an underlying exception, and you'll then need to inspect its stacktrace to see where it's happening.
- `DependencyGraphTransformer` is the primary entry point for all transformations
- `IrBindingGraph` is the IR-specific implementation of a binding graph that performs validation
- `BindingGraphGenerator` is the implementation that looks up all available bindings from IR and builds an `IrBindingGraph`
- `BindingLookup` is a lookup that holds all available bindings that `IrBindingGraph` validations request bindings from. Namely, this is important for ensuring we don't generate code for unused bindings.
- `ParentContext` handles managing a view of all parent keys used by child graph extensions
- `BindingPropertyCollector` handles picking which bindings get properties and what kind (field, getter, etc.)

You can also connect a debugger to a remote compilation, for example a repro project. There is a built-in remote debug run configuration that should be picked up automatically in this project (just called `Debug`) in run configurations. Then, in the reproducing project, invoke its kotlin compilation task with these flags:

```
./gradlew :path:to:compileKotlin --no-daemon -Dorg.gradle.debug=true -Pkotlin.compiler.execution.strategy=in-process --rerun
```

Note the `--rerun` is only really necessary if the compilation you're debugging succeeds. It's also recommended to run the compilation once without these flags to hydrate the cache before you debug, as the debugger can slow things down.

Once you start the task with the above flags, the process will wait for you to connect a debugger to start. That's the part where the Debug run configuration comes in. Run it like you would any other debugger (control+D, or click the bug icon), and it'll connect and go. Note this does not work for debugging IC builds (see below).

### 2. IC debugging

Incremental compilation debugging is unpleasant and a lot of trial-and-error at times. It's not currently possible to (reliably) connect a debugger to this compiler process (only the gradle process), so it's mostly about breadcrumb-ing and rerunning tests.

For these, you'll want to look closely at reports produced by the compiler, which are enabled by default in IC tests. See the `BaseIncrementalCompilationTest.Reports` class, which models many of these, and you can look at _those_ in a debugger. Namely, `lookup` and `expectActualReport` are the files that indicate linking of declarations in compilation, but it can be helpful also to look at reported keys/graph metadata to get a view of what each graph sees.

### 3. Reports debugging

This is mostly for debugging graph config problems. Enable `reportsDestination` in Metro's Gradle DSL and poke around its output files.

## Compiler Plugin Design

The compiler plugin is implemented primarily in two parts.

### 1. FIR

The FIR frontend generates declarations, generates supertypes, and performs diagnostic checks for Metro types. _Any_ class or callable declaration generated by Metro should be done _here_ as this is required for them to be visible in Kotlin metadata later.

Generators go in the `dev.zacsweers.metro.compiler.fir.generators` package.

Checkers go in the `dev.zacsweers.metro.compiler.fir.checkers` package.

New checker contributions are generally welcome. New generators almost always warrant prior discussion first!

### 2. IR

The IR backend performs two main functions:

1. _Implements_ declarations generated in FIR. This includes generated graphs, factories, member injectors, etc.
2. Performs dependency graph construction and validation. This is primarily spread across `DependencyGraphTransformer`, `BindingGraph`, and `Binding`.

Most of this is implemented as _transformers_ in the `dev.zacsweers.metro.compiler.ir.transformers` package. Note that _all_ transformers are run from the `DependencyGraphTransformer`, which is the only _true_ `IrTransformer` of the bunch and just delegates out to the other transformers as needed.

Aggregation hint properties are also implemented in IR as a workaround to support incremental compilation. See `ContributionHintIrTransformer` for more details.

### `TypeKey` and `ContextualTypeKey`

`TypeKey` and `ContextualTypeKey` (and their FIR counterparts) deserve special mention. Most of the compiler's dependency graph analysis thinks in terms of these two types.

A `TypeKey` is the canonical representations of specific binding, composed of a _type_ and optional _qualifier_.

A `ContextualTypeKey` can be thought up as a `TypeKey` _with context_ of how it's used. This is useful for a few reasons:

* Allows Metro's compiler plugin to generate code accordingly for how the given `TypeKey` is used at runtime, for example wrapping in `Provider`, `Lazy`, etc.
* Allows dependency graph resolution to understand if the type is _deferrable_, which is useful in breaking dependency cycles.

## Misc Notes

* IR code should cache eagerly.
* FIR code should cache carefully (remember it runs in the IDE!).
* FIR code should be defensive. It may run continuously in the IDE and not all information may be available to the compiler as the user has written it. If you've ever written a custom lint check, your methodology should be similar.
* Inversely, IR code should be offensive. Assert expectations with clear error messages, report errors with useful error messages.
* FIR-generated declaration should use descriptive _keys_ to declarations that can be referenced later in FIR and IR (as `origins`). See `Keys.kt` for FIR declarations and `Origins.kt` for their IR analogs.
