# Performance

Metro strives to be a performant solution with minimal overhead at build-time and generating fast, efficient code at runtime.

## Benchmarks

The [benchmark project](https://github.com/ZacSweers/metro/tree/main/benchmark) generates a deterministic multi-module workload for every framework. Its README documents the workload, generator, and runner commands.

Every ratio below compares frameworks within one run. The dated tabs preserve older measurements, but absolute values across tabs are not controlled comparisons because the tools, host environments, and Android versions changed.

**Modes**

- `Metro`: Metro's compiler plugin and runtime.
- `Dagger (KSP)`: Dagger KSP with Anvil KSP contribution merging.
- `Dagger (KAPT)`: Dagger KAPT with Anvil KSP contribution merging.
- `kotlin-inject`: kotlin-inject with kotlin-inject-anvil contribution merging.
- `Koin`: Koin's compiler plugin and runtime container.

!!! note "Koin caveats"
    The Koin benchmarks deserve a couple notes because the work is not exactly like-for-like:

    - Koin's compiler plugin does less work. It aggregates definitions, generates module and factory wiring, and checks for missing dependencies and cycles, but leaves final graph resolution to runtime. Metro, Dagger, and kotlin-inject resolve and validate graphs from their roots and generate static implementations at compile time.
    - Koin's runtime does more work as a result. The graph work deferred during compilation happens during startup.

### Build Performance

Metro's compiler plugin is designed to be _fast_. Running as a compiler plugin allows it to:

- Avoid generating new sources that need to be compiled.
- Avoid running KSP or KAPT.
- Generate IR that lowers directly into target platforms.
- Hook directly into kotlinc's incremental compilation APIs.

#### Methodology

The build benchmarks use [Gradle Profiler](https://github.com/gradle/gradle-profiler) and report the median of ten measured builds without clean builds.

Each scenario targets a different kind of work:

- **ABI and non-ABI** scenarios change a lower-level module file that does use DI.
- **Plain Kotlin** variants change code that does not participate in DI.
- **Graph Processing** reruns the top-level graph or container processing task.

The benchmark README documents warm-ups, Kotlin compiler execution, cache settings, and stability checks.

!!! note "Build report legend"
    - **GC** is time spent in garbage collection.
    - **MAD** shows variation around the median.
    - **Half-run drift** compares the first and second halves of the run.
    - **Outliers** are samples more than 20% from the median. All ten samples remain included.

#### Published Build Results

=== "Current (2026-07-30)"

    Exact median times are in milliseconds. The value in parentheses is the ratio to Metro within this run, and lower is better.

    | Scenario | Metro | Dagger (KSP) | Dagger (KAPT) | kotlin-inject | Koin |
    |---|---:|---:|---:|---:|---:|
    | ABI Change | 5,541.985 (1.00×) | 23,237.710 (4.19×) | 25,186.340 (4.54×) | 11,612.840 (2.10×) | 5,751.730 (1.04×) |
    | Non-ABI Change | 3,334.620 (1.00×) | 4,242.790 (1.27×) | 8,552.660 (2.56×) | 3,642.120 (1.09×) | 3,793.640 (1.14×) |
    | Plain Kotlin ABI | 5,552.485 (1.00×) | 22,796.950 (4.11×) | 25,003.370 (4.50×) | 11,869.535 (2.14×) | 5,711.295 (1.03×) |
    | Plain Kotlin Non-ABI | 3,069.720 (1.00×) | 4,175.165 (1.36×) | 9,360.640 (3.05×) | 3,635.170 (1.18×) | 3,732.875 (1.22×) |
    | Graph Processing* | 6,198.085 (1.00×) | 12,311.125 (1.99×) | 11,732.670 (1.89×) | 23,699.260 (3.82×) | 3,643.340 (0.59×) |

    \* Graph Processing reruns each framework's top-level graph or container processing task. Dagger and kotlin-inject also run the processors that generate their graph implementations, so this scenario does not use identical Gradle tasks across frameworks.

    [Open the full interactive build report](benchmark_assets/runs/2026-07-30/build-benchmark-report.html).

    ![Current build-time chart for the ABI change benchmark.](benchmark_assets/runs/2026-07-30/benchmark_abi.png)

    ![Current build-time chart for the non-ABI change benchmark.](benchmark_assets/runs/2026-07-30/benchmark_noabi.png)

    ![Current build-time chart for the Graph Processing benchmark.](benchmark_assets/runs/2026-07-30/benchmark_graph_processing.png)

    **Run metadata**

    - Measurement commit [`cf8e67783`](https://github.com/ZacSweers/metro/commit/cf8e677835e3eb084e8d933e8b0568fb74505cb2) on `z/updateBenchmarks`, with a clean working tree. The matrix completed on 2026-07-30 at 07:14 EDT.
    - Seed `0` and workload fingerprint `sha256:7b20f05437d03f4742fc1bfa884d66091e025d86651c5f519f4a3f97e28ebdf4`.
    - Metro 1.4.0, Kotlin 2.4.0, Dagger 2.60.1, KSP 2.3.10, Anvil 0.5.4, kotlin-inject 0.9.0, kotlin-inject-anvil 0.1.7, Koin 4.2.2, and Koin compiler 1.1.0.
    - Gradle 9.6.1, Gradle Profiler 0.25.2, JDK 25.0.4, and JVM target 11 on macOS with an Apple M4 Pro and 48 GB RAM. Kotlin compilation ran in-process against an isolated Gradle user home.
    - Every scenario passed the stability gate with ten measured samples and zero outliers. The maximum relative median absolute deviation was 3.86%. The maximum first-half versus second-half median drift was 9.13%.
    - The full report records the workload counts, framework options, and repository metadata.

=== "2025-12-08"

    Koin was not included in this run. Exact median times are in milliseconds. The value in parentheses is the ratio to Metro within this run, and lower is better.

    | Scenario | Metro | Dagger (KSP) | Dagger (KAPT) | kotlin-inject |
    |---|---:|---:|---:|---:|
    | ABI Change | 17,483.18 (1.00×) | 119,632.99 (6.84×) | 93,195.04 (5.33×) | 32,297.22 (1.85×) |
    | Non-ABI Change | 11,552.18 (1.00×) | 13,819.26 (1.20×) | 23,177.53 (2.01×) | 11,336.05 (0.98×) |
    | Plain Kotlin ABI | 17,501.49 (1.00×) | 121,273.14 (6.93×) | 92,521.81 (5.29×) | 31,702.09 (1.81×) |
    | Plain Kotlin Non-ABI | 10,083.62 (1.00×) | 12,232.19 (1.21×) | 26,049.74 (2.58×) | 11,460.08 (1.14×) |
    | Graph Processing | 22,612.15 (1.00×) | 88,085.83 (3.90×) | 25,986.25 (1.15×) | 28,250.25 (1.25×) |

    [Open the archived interactive build report](benchmark_assets/runs/2025-12-08/build-benchmark-report.html).

    ![Archived build-time chart for the 2025 ABI change benchmark.](benchmark_assets/runs/2025-12-08/benchmark_abi.png)

    ![Archived build-time chart for the 2025 non-ABI change benchmark.](benchmark_assets/runs/2025-12-08/benchmark_noabi.png)

    ![Archived build-time chart for the 2025 Graph Processing benchmark.](benchmark_assets/runs/2025-12-08/benchmark_graph_processing.png)

    **Run metadata**

    - Metro 0.8.3 at commit [`a1f9a939`](https://github.com/ZacSweers/metro/commit/a1f9a939). The run used Kotlin 2.2.20, Dagger 2.57.2, KSP 2.3.3, Anvil 0.5.1, kotlin-inject 0.8.0, and kotlin-inject-anvil 0.1.6.
    - Gradle 9.2.1, Gradle Profiler commit [`a27c6559`](https://github.com/gradle/gradle-profiler/commit/a27c6559), JDK 24.0.2, and JVM target 11 on Linux with an AMD EPYC 7763 and 15 GiB RAM.
    - The seed and workload fingerprint were not recorded.

### Runtime Performance

Metro's compiler generates Dagger-style factory classes for every injection site. The same factory classes are reused across modules and downstream builds, so there's no duplicated glue code or runtime discovery cost.

Because the full dependency graph is wired at compile-time, each binding is accessed through a direct provider field reference or direct invocation in the generated code. There are no reflection calls, hashmap lookups, or runtime service locator hops.

kotlin-inject generates an inner `LazyMap` in its component impl that is populated at component init.

Koin does not generate a graph impl at compile-time and instead resolves all dependencies at runtime.

#### Methodology

The JVM and R8 measurements use JMH average-time mode. Android uses an AndroidX microbenchmark and consumes each returned component through `BlackHole.consume`. The benchmark README documents the full runner configuration.

#### Published Runtime Results

=== "Current (2026-07-30)"

    Exact values are milliseconds per graph creation and initialization. The value in parentheses is the ratio to Metro within the same environment.

    | Framework | JVM JMH | JVM R8 | Android microbenchmark |
    |---|---:|---:|---:|
    | Metro | 0.16807883977409765 (1.00×) | 0.16645391862207762 (1.00×) | 0.10138713576449912 (1.00×) |
    | Dagger (KSP) | 0.1778419859546272 (1.06×) | 0.1710602057899141 (1.03×) | 0.13074065954922892 (1.29×) |
    | kotlin-inject | 0.2380900961010331 (1.42×) | 0.22299428354115663 (1.34×) | 17.6878621 (174.46×) |
    | Koin | 6.490582016559728 (38.62×) | 4.968557821007341 (29.85×) | 23.686009625 (233.62×) |

    Dagger KAPT is omitted from the current runtime matrix because its generated graph implementation is equivalent to Dagger KSP's for this workload.

    [Open the full interactive runtime report](benchmark_assets/runs/2026-07-30/startup-benchmark-report.html).

    ![Linear JVM runtime chart with Metro at 0.16807883977409765 ms, Dagger KSP at 0.1778419859546272 ms, kotlin-inject at 0.2380900961010331 ms, and Koin at 6.490582016559728 ms.](benchmark_assets/runs/2026-07-30/runtime_jvm.png)

    ![Linear R8-minified JVM runtime chart with Metro at 0.16645391862207762 ms, Dagger KSP at 0.1710602057899141 ms, kotlin-inject at 0.22299428354115663 ms, and Koin at 4.968557821007341 ms.](benchmark_assets/runs/2026-07-30/runtime_jvm_r8.png)

    Android uses two linear scales. A detail chart shows Metro and Dagger. A full-scale chart shows all four frameworks and accommodates the longer kotlin-inject and Koin times.

    ![Linear Android detail chart with Metro at 0.10138713576449912 ms and Dagger KSP at 0.13074065954922892 ms.](benchmark_assets/runs/2026-07-30/runtime_android_compiled.png)

    ![Full-scale linear Android chart with Metro at 0.10138713576449912 ms, Dagger KSP at 0.13074065954922892 ms, kotlin-inject at 17.6878621 ms, and Koin at 23.686009625 ms.](benchmark_assets/runs/2026-07-30/runtime_android_runtime.png)

    The complete Android matrix was repeated after a cooldown. No framework exceeded both stabilization thresholds of 10% and 0.02 ms.

    | Framework | First pass (ms) | Second pass (ms) | Absolute difference (ms) | Difference |
    |---|---:|---:|---:|---:|
    | Metro | 0.10138713576449912 | 0.0962546281938326 | 0.005132507570666522 | 5.06% |
    | Dagger (KSP) | 0.13074065954922892 | 0.12853238038548753 | 0.00220827916374139 | 1.69% |
    | kotlin-inject | 17.6878621 | 17.5348552 | 0.15300690000000117 | 0.87% |
    | Koin | 23.686009625 | 24.227620375 | 0.5416107500000003 | 2.29% |

    **Run metadata**

    - Measurement commit [`f4a6fc34c`](https://github.com/ZacSweers/metro/commit/f4a6fc34c865a4bc9ae0a397e428c03f32239152) on `z/updateBenchmarks`, with a clean working tree. The full matrix completed on 2026-07-30 at 09:30 EDT, and the Android repeat completed at 09:41 EDT.
    - Seed `0` and workload fingerprint `sha256:7b20f05437d03f4742fc1bfa884d66091e025d86651c5f519f4a3f97e28ebdf4`.
    - Metro 1.4.0, Kotlin 2.4.0, Dagger 2.60.1, KSP 2.3.10, Anvil 0.5.4, kotlin-inject 0.9.0, kotlin-inject-anvil 0.1.7, Koin 4.2.2, and Koin compiler 1.1.0.
    - JDK 25.0.4, JVM target 11, JMH plugin 0.7.3, JMH runtime 1.36, and AndroidX Benchmark 1.4.1 on macOS with an Apple M4 Pro and 48 GB RAM.
    - Pixel 9 running Android 17/API 37 on arm64-v8a with build `CP31.260618.005`.
    - The full report records the workload counts, framework options, and complete device environment.

=== "2025-12-08"

    Koin was not included in this run. Exact values are milliseconds per operation. The value in parentheses is the ratio to Metro within this run.

    | Framework | JVM JMH | JVM R8 | Android microbenchmark |
    |---|---:|---:|---:|
    | Metro | 0.16802429521997675 (1.00×) | 0.15640682329788375 (1.00×) | 0.102 (1.00×) |
    | Dagger (KSP) | 0.16716651975694252 (0.99×) | 0.16155119157781478 (1.03×) | 0.125 (1.23×) |
    | Dagger (KAPT) | 0.16800631929906623 (1.00×) | 0.16164561019243706 (1.03×) | 0.123 (1.21×) |
    | kotlin-inject | 0.21527532967935503 (1.28×) | 0.20484185885872247 (1.31×) | 12.051 (118.15×) |

    [Open the archived interactive runtime report](benchmark_assets/runs/2025-12-08/startup-benchmark-report.html).

    ![Archived JVM runtime chart for the December 2025 run.](benchmark_assets/runs/2025-12-08/runtime_jvm.png)

    ![Archived R8-minified JVM runtime chart for the December 2025 run.](benchmark_assets/runs/2025-12-08/runtime_jvm_r8.png)

    ![Archived Android runtime chart for the December 2025 run.](benchmark_assets/runs/2025-12-08/runtime_android.png)

    **Run metadata**

    - Kotlin 2.2.20, Dagger 2.57.2, KSP 2.3.3, Anvil 0.5.1, kotlin-inject 0.8.0, and kotlin-inject-anvil 0.1.6.
    - JMH plugin 0.7.3, AndroidX Benchmark 1.4.1, JDK 24.0.2, and JVM target 11 on an Apple M4 Pro with 48 GB RAM.
    - Pixel 9 running Android 16.
    - The commit, seed, and workload fingerprint were not recorded.

## Real-World Results

Below are some results from real-world projects, shared with the developers' permission.

!!! note "Square"
    Square wrote a blog post about their migration to Metro: [Metro Migration at Square Android](https://engineering.block.xyz/blog/metro-migration-at-square-android)

    > How Square Android migrated its monorepo from Dagger 2 and Anvil to Metro over nine months and saved thousands of hours of build time.

!!! note "Cash App"
    Cash App wrote a blog post about their migration to Metro: [Cash App Moves to Metro](https://code.cash.app/cash-android-moves-to-metro)

    > According to our benchmarks, by migrating to Metro and K2 we managed to improve clean build speeds by over 16% and incremental build speeds by almost 60%!

!!! note "Gabriel Ittner from Freeletics"
    I've got Metro working on our code base now using the Kotlin 2.2.0 preview
    
    Background numbers
    
    - 551 modules total
    - 105 modules using Anvil KSP ➡️ migrated to pure Metro
    - 154 modules using Anvil KSP + other KSP processor ➡️ Metro + other KSP processor
    - 1 module using Dagger KAPT ➡️ migrated to pure Metro
    
    Build performance
    
    - Clean builds without build cache are 12 percentage points faster
    - Any app module change ~50% faster (this is the one place that had kapt and it's mostly empty other than generating graphs/components)
    - ABI changes in other modules ~ 40% - 55% faster
    - non ABI changes in other modules unchanged or minimally faster

!!! note "Madis Pink from emulator.wtf"
    I got our monorepo migrated over from anvil, it sliced off one third of our Gradle tasks and `./gradlew classes` from clean is ~4x faster

!!! note "Kevin Chiu from BandLab"
    We migrated our main project at BandLab to metro, finally!
    
    Some context about our project:

    - We use Dagger + Anvil KSP
    - 929 modules, 89 of them are running Dagger compiler (KAPT) to process components
    - 7 KSP processors

    | Build                             | Dagger + Anvil KSP | Metro (Δ)              |
    |-----------------------------------|--------------------|------------------------|
    | UiKit ABI change (Incremental)    | 59.7 s             | 26.9 s (55% faster)   |
    | Root ABI change (Incremental)     | 95.7 s             | 48.1 s (49.8% faster) |
    | Root non-ABI change (Incremental) | 70.9 s             | 38.9 s (45.2% faster) |
    | Clean build                       | 327 s              | 288 s (11.7% faster)  |

!!! note "Cyril Mottier from Amo"
    [Ref](https://x.com/cyrilmottier/status/1971562605899546936)

    > We already had incremental compilation in the single-digit seconds range, but I’m still blown away by how much faster it is now that the entire codebase is fully on Metro. 🤯

!!! note "Vinted"
    Vinted adopted metro and reaped significant build time and developer experience improvements: [From Dagger to Metro](https://vinted.engineering/2026/02/12/from-dagger-to-metro/)

    > Metro consolidated all the best practices from other popular frameworks, while leaving out the not-so-best practices on the side, allowed us to enable K2 and immediately experience significant build time improvements, while also unlocking incremental compilation, which means that the builds will be getting even faster

## Scaling to Very Large Graphs

For graphs aggregating thousands of contributions, two opt-in knobs help work around JVM and Kotlin metadata size limits. Both are power-user features and unnecessary for typical graphs.

### `@MergeContributionsInIr`

Annotating a graph with `@MergeContributionsInIr` opts it out of FIR-side contribution-supertype merging. Contributions are still merged into the graph during IR, so runtime behavior is unchanged. The trade-off is that contributions become invisible in the graph's Kotlin metadata:

- Code consuming the graph as an `@Includes` dependency will not see contributed members.
- IDE support will not surface contributed members on the graph type.
- Kotlin/Native ObjC framework export will not include contributed interfaces in the graph's supertype list.

This annotation is `@DelicateMetroApi` and requires explicit opt-in. You should only use this if you have a very specific reason to.

### `merged-supertype-chunk-size`

The `merged-supertype-chunk-size` Metro compiler option groups merged contribution supertypes into synthetic intermediate interfaces of at most N contributions each. This is useful for graphs whose merged supertype list would otherwise exceed the JVM's 65535-byte class signature limit, which the JVM emits whenever at least one supertype is generic.

```kotlin
metro {
  compilerOptions.put("merged-supertype-chunk-size", "200")
}
```

Default `0` disables chunking. Each chunk holds up to N contributions plus their promoted parent interfaces, so the chunk count tracks the contribution count rather than the raw supertype count. Most useful paired with `@MergeContributionsInIr` for the largest graphs.

### Shortening generated member names

For very large graphs, the descriptive declaration names Metro generates on can contribute a measurable amount of bytecode/string-table size. The `member-naming-strategy` compiler option swaps them for a smaller vocabulary.

```kotlin
metro {
  compilerOptions.put("member-naming-strategy", "TYPED")
}
```

Three values are accepted:

- **`DESCRIPTIVE`** (default): names derived from binding types and parameters (e.g. `httpClientProvider`, `databaseProvider`).
- **`TYPED`**: short kinded prefixes for graph supplemental and graph-as-shard binding properties (`provider`, `provider2`, ...; `instance`, `instance2`, ...; `factory`, `factory2`, ...).
- **`MINIMAL`**: single short vocabulary; every kind collapses to `provider` (e.g. `provider`, `provider2`, `provider3`, ...).

When sharding is active and a graph splits into multiple shard classes, each shard's binding properties always collapse to `MINIMAL` regardless of the chosen strategy (so long as it is not `DESCRIPTIVE`), and each shard uses its own name allocator. The same name string (`provider`, `provider2`, ...) therefore recurs across shard classes.

#### When this matters

- Graph classes with thousands of fields shrink because each field name in the constant pool's UTF-8 strings is shorter.
- Multi-shard graphs additionally benefit from cross-class deduplication since the same short strings recur across shard classes.
- Factory (and similar) classes benefit similarly via cross-class dedup in DEX, where the small fixed vocabulary recurs across thousands of generated factory classes.

#### When this does not matter

**Builds shrunk by R8/ProGuard:** Private generated field names are renamed/inlined/etc by the shrinker regardless of what Metro emits. There is effectively no artifact-size difference between `DESCRIPTIVE`, `TYPED`, and `MINIMAL` once R8 has run. The option is most useful for pipelines that ship un-minified bytecode (pure JVM server apps, distributed library AARs at author dev time, debug Android builds).

Default is `DESCRIPTIVE` so generated code stays readable. Opt in only if you have a specific size constraint that benefits.

## Tracing

### Compiler tracing

If you want to investigate the performance of Metro's compiler pipeline, you can enable tracing in the Gradle DSL.

```kotlin
metro {
  traceDestination.set(layout.buildDirectory.dir("metro/trace"))
}
```

This will output one or more Perfetto trace files after the compilation that you can then load into https://ui.perfetto.dev.

Filenames follow the pattern `<id>-<phase>-<moduleName>.perfetto-trace`, where `<id>` is a `yyMMdd-HHmmss` timestamp shared across every file produced by the same compilation, `<phase>` is `fir` or `ir`, and `<moduleName>` identifies the FIR session or IR module fragment. KMP source-set hierarchies and multi-fragment IR each produce their own files. Load whichever file corresponds to the phase you want to inspect.

Note that these traces probably do require a bit of familiarity with the Metro compiler internals.

!!! warning

    Note that file option inputs like `traceDestination` are _not_ tracked as inputs to the kotlin compilation, so you should run your target kotlin compilation task with `--rerun` (not `--rerun-tasks`!) to ensure it it's not cached.

### Runtime tracing

Metro can also emit traces from generated graph code. For example, this is useful when you want to see which bindings are created or invoked during app startup or another measured runtime path.

!!! warning "Experimental"

    Runtime tracing is experimental. It currently targets JVM/Android graph code and integrates with AndroidX Tracing 2.x, which is still actively being developed. Expect the generated metadata and runtime helper APIs to change as AndroidX Tracing 2.x evolves.

Enable it in the Gradle DSL:

```kotlin
metro {
  enableRuntimeTracing.set(true)
}
```

When automatic runtime dependencies are enabled, Metro adds the JVM-only `metro-trace` helper artifact to JVM/Android JVM compilations.

Each root graph should take an AndroidX `Tracer` as a graph input. Metro uses this input while initializing the graph's trace context, before ordinary binding traces can be emitted:

```kotlin
@DependencyGraph
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}
```

On Android, prefer owning a single app-level `TraceDriver` and passing its tracer into the root graph from `Application.onCreate()`:

```kotlin
class MyApplication : Application(), AbstractTraceDriver.Factory {
  private val sink = TraceSink(context = this)
  // isCategoryEnabled = { true } here means that Tracing is unconditionally enabled.
  // This makes local iteration fast and easy. In production, you might want to use another explicit signal
  // (or UI affordance) to turn on in-process tracing.
  private val driver = TraceDriver(context = this, sink = sink, isCategoryEnabled = { true })

  lateinit var appGraph: AppGraph
    private set

  @OptIn(DelicateTracingApi::class)
  override fun onCreate() {
    super.onCreate()
    Tracer.setGlobalTracer(driver.tracer)
    appGraph = createGraphFactory<AppGraph.Factory>().create(driver.tracer)
  }

  override fun create(): AbstractTraceDriver = driver
}
```

`AbstractTraceDriver.Factory` lets AndroidX's profiler tooling discover the same driver that Metro uses. `Tracer.setGlobalTracer(...)` also makes the tracer available to other libraries using AndroidX's global tracer discovery. Also, disable the default `TraceDriver` initialization hook (`androidx.tracing.profiler.ConnectedProfilerTracingInitializer`) so it does not eagerly set `Tracer.setGlobalTracer(...)`.

```xml
<!-- Use MyApplications's TraceDriver so sample traces and profiler broadcasts share one sink and is always enabled. -->
<meta-data
    android:name="androidx.tracing.profiler.ConnectedProfilerTracingInitializer"
    android:value="androidx.startup"
    tools:node="remove" />
```

With AndroidX Tracing 2.0.0-alpha09 and newer, `TraceSink` defers file setup. Graph creation no longer needs to be delayed with `lazy` just to avoid early trace output initialization.

!!! tip "Tracing inside bindings"

    Metro traces the generated binding boundary. If a binding does meaningful work inside that boundary and you want more granular events, inject or depend on `Tracer` like any other binding and use AndroidX Tracing directly from that code.

    ```kotlin
    @Provides
    fun provideDatabase(driver: SqlDriver, tracer: Tracer): AppDatabase =
      tracer.trace(category = "app.database", name = "Open database") {
        AppDatabase(driver)
      }
    ```

Suspend bindings use coroutine-aware trace sections, so their spans remain connected across suspension points and thread changes. Suspend accessors emit the same instant events as other accessors. A scoped suspend binding emits a span only when it computes the value; cache hits emit none. See [Coroutines Support](coroutines.md).

Generated binding spans use the short rendered binding name, including the qualifier when present. Entry-point markers, such as accessors and member injectors, are emitted as instant events named after the implemented graph callable. Requested `MembersInjector<T>` values also emit instant events named like `MembersInjector<T>` when `injectMembers(...)` is called. Metro also attaches string metadata for filtering and grouping:

- `metro.graph`: the graph that owns the binding.
- `metro.graph_path`: the root-to-current graph path, useful for graph extensions.

Binding span metadata:

- `metro.type`: the canonical unqualified type.
- `metro.binding_kind`: the generated binding implementation kind, such as `Provided`, `ConstructorInjected`, or `Multibinding`.

Entry-point instant metadata:

- `metro.callable`: the callable name without the graph prefix, such as `foo` for `AppGraph.foo`.
- `metro.type`: the canonical unqualified requested type.
- `metro.entry_point_kind`: the generated graph entry-point kind, such as `Accessor` or `Member Injector`.

Both binding spans and entry-point instants may also include:

- `metro.contextual_type`: the requested unqualified type, when it differs from `metro.type`, such as `Provider<T>` or `Lazy<T>`.
- `metro.qualifier`: the binding qualifier, when present.

Here is what a trace looks like.

![Runtime Tracing of the Metro Sample app](performance_assets/runtime_tracing.png)

[Here is the link to the sample app](https://github.com/ZacSweers/metro/tree/main/samples/android-app) with the right setup for runtime tracing.

!!! note "Flushing traces"

    The sample app has UI affordance to flush traces manually. However you can also flush traces programmatically by doing something like:
    ```bash
    adb shell am broadcast -a androidx.tracing.profiler.action.FLUSH_TRACES_GET_PATH <targetPackage>/androidx.tracing.profiler.ConnectedProfilerTracingReceiver
    ```
