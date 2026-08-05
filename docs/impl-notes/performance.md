# Performance Implementation Notes

Notes on compiler performance, generated code, and (scaling) large graphs. The [user-facing performance guide](../performance.md) covers benchmarks and configuration.

## Performance model

Metro resolves dependency graphs during compilation and emits Kotlin IR directly. It does not generate another round of Kotlin source or resolve the graph through reflection at runtime.

Most performance decisions balance three costs:

- Compiler work affects build time and memory.
- Generated declarations affect binary size.
- Factories, providers, fields, and dispatch affect runtime speed and memory.

A smaller implementation is not always faster! For example:

- A shared provider can avoid duplicate work but add a field.
- A helper getter can prevent a compiler stack overflow but add a method.

Keep those tradeoffs explicit when changing graph generation.

## Compiler work

### Reachable bindings

Metro computes graph reachability from entry points and their dependencies. `shrink-unused-bindings` removes unreachable bindings from the generated graph by default.

```kotlin
interface AppGraph {
  val service: Service
}

@Inject class Database
@Inject class Service(val database: Database)
@Inject class Unused
```

The graph implementation constructs `Service` and `Database`. `Unused` does not become part of that graph, although its reusable declaration factory can still be generated.

Graph population uses a work queue instead of recursively resolving every dependency.

### Scatter collections and retained graph state

Metro uses AndroidX scatter collections for graph indexes and contribution data that remain live throughout graph analysis and generation. These collections store entries in compact arrays rather than allocating a separate node for every entry.

Each binding graph keeps its bindings and topological indexes in compact maps:

```kotlin
override val bindings = MutableScatterMap<TypeKey, Binding>(256)
private val bindingIndices = MutableObjectIntMap<TypeKey>()
```

Compilation-wide contribution data also uses scatter maps and sets:

```kotlin
private val contributions =
  MutableScatterMap<Scope, MutableScatterSet<IrType>>()
```

This matters most for cascading graph extension trees. A parent graph and its child graphs can all remain live while the tree is validated and generated. Each graph owns its own binding map, so per-entry overhead multiplies across the whole tree. Contribution indexes are shared across the compilation rather than copied into every child.

Primitive-value collections such as `MutableObjectIntMap` also avoid boxing integer indexes. Shard lookup and strongly connected component analysis use the same approach for graph and shard IDs.

Scatter collections do not provide sorted iteration or safe concurrent mutation. Keep deterministic ordering in the sorted graph structures, and use concurrent collections for caches that parallel graph validation can mutate.

Temporary analysis state does not need this. Ordinary `HashMap`, `HashSet`, etc. are fine when their contents are released after a short analysis pass.

### Stable graph ordering

`buildFullAdjacency` stores graph vertices in a sorted map and each vertex's dependencies in a sorted set. The same graph is therefore traversed in the same order even when bindings are discovered in a different order or come from hash-based collections.

```text
First discovery order:  A -> C, B
Second discovery order: A -> B, C
Stored order:           A -> B, C
```

Stable ordering keeps these outputs deterministic:

- Strongly connected component discovery and cycle diagnostics.
- Topological binding order and provider initialization.
- Shard partitioning and generated declaration order.

!!! note "Binding keys"
    `TypeKey` identifies a binding by its type and qualifier. `ContextualTypeKey` also describes how that binding is requested, such as through `Provider`, `Lazy`, or a suspend wrapper.

Several contextual requests can share one adjacency edge.

```kotlin
@Inject class A(
  val direct: B,
  val deferred: Lazy<B>,
)
```

Both requests point to `B`. The edge still counts as eager because one request needs `B` immediately. Collapsing adjacency must not discard that information.

### Diagnostic routes

Graph errors need a route from an accessor to the failing binding. When an error first needs a route, Metro runs a breadth-first search from every graph root over the existing sorted forward adjacency. It records each binding's parent and preserves the original entry for each root so later errors can reuse the same routes.

Breadth-first traversal picks the shortest dependency path, and stable root and dependency ordering keeps equally short routes consistent. Keep the original contextual root entry so provider, lazy, and suspend accessors appear correctly in diagnostics.

The index is populated only when an error needs a route. Successful graphs do not perform the extra traversal, and route reconstruction does not rebuild reverse adjacency or recurse through the graph. When we're gonna fail, we're ok with failing slowly in service of more actionable information to the user.

### Strongly connected components

`MetroSort` identifies strongly connected components (via Tarjan's algo) before ordering bindings. It maps vertices to dense integer IDs and uses primitive arrays for adjacency, discovery indexes, and low-link values.

The strongly connected component traversal uses explicit work stacks. This avoids allocating a traversal object for every edge and prevents that traversal from depending on the JVM call stack.

A valid cycle needs a deferred request or an intrinsically deferrable binding. `Provider` and `Lazy` requests allow a binding to be wired before its value is requested.

```kotlin
@Inject class A(val b: B)
@Inject class B(val a: () -> A)
```

Metro orders the cycle, creates a delegate provider, and connects that provider after the required fields exist.

```kotlin
val bProvider = DelegateFactory<B>()
val aProvider = A.MetroFactory.create(bProvider)
DelegateFactory.setDelegate(bProvider, B.MetroFactory.create(aProvider))
```

Sorted adjacency and repeated cycle checks add work beyond the component traversal itself. Avoid describing the entire graph pipeline as a single linear-time algorithm.

`ReusableCycleChecker` also uses heap-backed traversal frames instead of recursive calls. It reuses its frame stack and visited sets when testing different deferred bindings.

### Binding property analysis

`BindingPropertyCollector` visits bindings in reverse topological order. By the time it processes a binding, its consumers have already contributed their scalar and provider reference counts.

A simple binding can usually stay inline.

```kotlin
val service: Service
  get() = Service(Database())
```

Repeated scalar access to a nontrivial unscoped binding can use a private getter.

```kotlin
private val database: Database
  get() = Database(connection())

val first: Service
  get() = Service(database)

val second: Worker
  get() = Worker(database)
```

That getter does not cache `Database`. Each read still creates a fresh unscoped instance.

Provider fields can be needed for:

- Repeated provider access.
- Mixed scalar and provider access.
- Scoped bindings.
- Deferred cycles.

Bound instances are already stored on the graph, so a single provider request can wrap the existing instance without adding another field.

Keep scalar and provider reference counts separate. Treating every repeated binding as a cached value changes unscoped behavior.

### Graph and contribution caches

Graph construction reuses several kinds of state:

- Binding lookups.
- Constructor factories.
- Assisted factories.
- Inherited parent graph data.

Contribution merging also caches work by scope, caller visibility, exclusions, and replacements.

Cache keys must preserve:

- Contextual wrapper types.
- Qualifiers.
- Caller visibility.
- Graph ownership.

A cache keyed only by the underlying type can incorrectly merge provider and scalar requests.

Some caches are concurrent because sibling graphs can be validated in parallel. Other IR caches are intentionally thread-unsafe. Do not assume a cache is bounded or thread-safe without checking its implementation.

Completed graphs release lookup state that is no longer needed. Avoid retaining graph analysis structures after sealing or code generation has finished.

### Parallel graph validation

`parallel-threads` defaults to `0`, which keeps validation sequential. When enabled, sibling graph extensions can be validated on a `ForkJoinPool`.

The parent graph is represented by an immutable snapshot. Each child records its own used keys, and results are merged in a stable order after validation.

```kotlin
val parentSnapshot = parent.snapshot()

val first = validate(firstChild, parentSnapshot)
val second = validate(secondChild, parentSnapshot)

merge(first)
merge(second)
```

(The example shows the ownership model rather than the exact scheduling code. Child validation can overlap, but graph declaration creation and result merging must not race.)

Parallel processing is extremely experimental as the underlying compiler doesn't really support it. Metro's support tries to gate information gathering first and then only parallelize graph processing.

### Incremental compilation tracking

`buffered-ic-tracking` is enabled by default. Metro buffers and deduplicates lookup records and expect-actual links during graph work, then flushes them after validation.

```kotlin
// Before
recordLookup(binding)
recordLookup(binding)
recordLookup(binding)

// After
bufferLookup(binding)
flushBufferedLookups()
```

Cache hits still need the relevant lookup records. Omitting them can make incremental builds fast but incorrect.

## Generated runtime code

### Direct construction

When a constructor or provider declaration can be called directly, Metro emits the direct call.

```kotlin
// Before
Foo.MetroFactory.create(barProvider)()

// After
Foo(bar)
```

Visibility rules still apply. If a direct constructor call is inaccessible, Metro can call a generated factory entry point instead.

```kotlin
Foo.MetroFactory.newInstance(bar)
```

Factories are associated with the binding declaration and can be reused across graphs and modules. Do not replace a valid direct call with a factory just to make generated shapes uniform.

### Factory reuse

A factory with no dependencies can be generated as an object rather than a new instance for every request.

```kotlin
// Before
class Foo {
  class MetroFactory : Factory<Foo> {
    override fun invoke() = Foo()
  }
}
```

```kotlin
// After
class Foo {
  object MetroFactory : Factory<Foo> {
    override fun invoke() = Foo()
  }
}
```

Factory construction also reuses equivalent dependency parameters where their provider keys allow it. Assisted parameters and defaulted parameters must keep their original behavior and cannot be merged blindly.

Direct suspend providers have reusable source-level suspend factories. Graph-local suspend factories for transitively suspend bindings are generated only when a graph needs them. They store only their required dependencies and callable receivers. See the [suspend provider notes](suspend-providers.md) for details.

### Static factory entry points

`generate-static-annotations` is enabled by default. Generated factory and member-injection entry points can receive `@JvmStatic` or `@JsStatic` so platform code can call them directly.

```kotlin
Foo.MetroFactory.create(barProvider)
Foo.MetroFactory.newInstance(bar)
Foo.MetroMembersInjector.injectBar(instance, bar)
```

Static entry points avoid an extra object dispatch at the call site. They can also add generated bridges, so preserve the existing platform-specific rules when changing factory APIs.

### Constant provider inlining

`enable-provider-inlining` is enabled by default. Unscoped providers with no parameters can be inlined when their values are safe to materialize directly.

```kotlin
@BindingContainer
object Bindings {
  @Provides
  fun answer(): Int = 42
}
```

```kotlin
// Before
Bindings.AnswerMetroFactory.create()()

// After
42
```

Supported values include:

- Primitive literals and strings.
- `null`.
- Public object instances and enum entries.
- Class literals and compatible constant values.

Provider metadata carries the inlined value across module boundaries.

Object instances, enum entries, and class literals must not be moved across provider or lazy boundaries when doing so would initialize a class too early. Scoped bindings must not be inlined in a way that bypasses scope identity.

Primitive provider wrappers avoid general-purpose factory objects where possible. Boolean factories reuse shared true and false instances. `InstanceFactory` is a value class and reuses a shared factory for `null`.

The [inline provider notes](inline-providers.md) describe eligibility, metadata, visibility, and lazy initialization in more detail.

### Scoped values

Scoped providers use `DoubleCheck` to initialize a value once and return the cached value on later calls.

```kotlin
private val databaseProvider =
  DoubleCheck.provider(Database.MetroFactory.create(connectionProvider))
```

The fast path avoids repeating the guarded initialization. The delegate is released after initialization, and an existing memoizing provider or lazy wrapper is reused when possible.

Suspend bindings use `SuspendDoubleCheck` when scoped. Its initialization path coordinates concurrent requests and preserves cancellation behavior without turning an ordinary property getter into a suspend function.

Do not add caching to unscoped bindings as a general performance shortcut. It changes object identity and side effect timing.

### Collection bindings

`MultibindingExpressionGenerator` uses smaller generated shapes for empty and singleton collections.

```kotlin
emptySet()
setOf(singleValue)

buildSet(expectedSize) {
  add(first)
  add(second)
}
```

Maps use pre-sized builders where possible.

```kotlin
buildMap(expectedSize) {
  put(firstKey, firstValue)
  put(secondKey, secondValue)
}
```

Provider-backed collections use matching empty and singleton factory paths. Collection generation also preserves these properties:

- Collection-valued contributions are evaluated once per collection construction and reused for capacity and elements.
- Provider-backed contributions stay deferred until the factory is invoked.
- Map iteration order remains deterministic.

Keep provider-valued maps distinct from instance-valued maps. They have different laziness and wrapper requirements.

### Binding expression depth

`BindingExpressionDepthLimiter` inserts uncached helper getters when an eligible binding chain reaches 64 bindings. Graphs with fewer than 64 reachable bindings skip the analysis before allocating any limiter state. The threshold is internal and has no compiler option.

Only ordinary constructor bindings and provided bindings can receive these helper getters. Assisted bindings and other special binding shapes are excluded.

```kotlin
// Before
val root: A
  get() = A(B(C(D(E()))))
```

```kotlin
// After
private val d: D
  get() = D(E())

val root: A
  get() = A(B(C(d)))
```

The example uses a short chain to show the shape. Real getters are inserted only when a chain reaches the internal depth limit.

Each getter creates a new value when read. It must not become a backing field because unscoped bindings need to remain unscoped.

The getter type depends on the binding:

- Provider-only paths use provider-valued getters.
- Suspend paths use `SuspendProvider` getters because Kotlin property getters cannot suspend.

```kotlin
private val dependencySuspendProvider: SuspendProvider<Dependency>
  get() = DependencySuspendFactory(nestedSuspendProvider)
```

The provider is invoked later from the existing suspend context. The getter itself does not call suspend code.

### Initializer chunking

Large graph constructors can exceed JVM method limits when every provider field is initialized in one body. Metro splits initialization into private helper methods when a graph or shard has more than 25 provider-property initializers.

```kotlin
// Before
init {
  firstProvider = First.MetroFactory.create()
  secondProvider = Second.MetroFactory.create(firstProvider)
  thirdProvider = Third.MetroFactory.create(secondProvider)
}
```

```kotlin
// After
init {
  init1()
  init2()
}

private fun init1() {
  firstProvider = First.MetroFactory.create()
  secondProvider = Second.MetroFactory.create(firstProvider)
}

private fun init2() {
  thirdProvider = Third.MetroFactory.create(secondProvider)
}
```

The short example represents the real 25-initializer limit. Other constructor setup and delegate connections are not included in that count. Cycle delegates are connected only after their required provider fields exist.

`statements-per-init-fun` controls this limit. The same limit also bounds switching-provider dispatch helpers.

### Graph sharding

Graph sharding is enabled by default. The default target is 2,000 binding keys per shard.

Small graphs act as their own shard and do not receive extra nested classes. Larger graphs place binding properties on generated shard classes, while the main graph stores references to those shards.

```kotlin
// Before
interface AppGraph {
  class Impl : AppGraph {
    private val firstProvider = First.MetroFactory.create()
    private val secondProvider = Second.MetroFactory.create(firstProvider)
    private val thirdProvider = Third.MetroFactory.create(secondProvider)
  }
}
```

```kotlin
// After
interface AppGraph {
  class Impl : AppGraph {
    internal val shard1 = Shard1()
    internal val shard2 = Shard2(this)

    private class Shard1 {
      internal val firstProvider = First.MetroFactory.create()
      internal val secondProvider = Second.MetroFactory.create(firstProvider)
    }

    private class Shard2(internal val graph: Impl) {
      internal val thirdProvider = Third.MetroFactory.create(graph.shard1.secondProvider)
    }
  }
}
```

`GraphPartitioner` keeps strongly connected components together. A cycle can therefore make one shard larger than the target. The 2,000-key setting is a target rather than a hard maximum.

Nested shard properties use the visibility needed for cross-shard access. Their backing fields remain private so generated JVM and JS code stays valid.

`enable-graph-sharding` controls the feature. `keys-per-graph-shard` controls the target size.

### Switching providers

`enable-switching-providers` is disabled by default. When enabled, eligible provider fields can share a generated switching-provider class instead of eagerly creating separate binding-specific factory objects.

```kotlin
interface AppGraph {
  class Impl : AppGraph {
    private val firstProvider = First.MetroFactory.create()

    private class SwitchingProvider<T>(
      private val graph: Impl,
      private val id: Int,
    ) : Provider<T> {
      override fun invoke(): T = when (id) {
        0 -> First() as T
        1 -> Second(graph.firstProvider()) as T
        else -> error("Unexpected SwitchingProvider id: " + id)
      }
    }
  }
}
```

Large switches are split into helper methods using the same initialization chunk size. Stable integer IDs allow the backend to generate switch dispatch, although individual shards can contain gaps.

Switching providers have several tradeoffs:

- They can reduce eager class loading and graph initialization work.
- They add provider dispatch.
- They retain a graph or shard reference.

Suspend bindings do not use switching providers yet.

## Generated binary size

### Supertype chunking

Large contribution graphs can exceed the JVM class-signature limit when thousands of interfaces appear in one generated supertype list.

```kotlin
// Before
interface AppGraph {
  class Impl : AppGraph, Contribution1, Contribution2, Contribution3, Contribution4
}
```

```kotlin
// After
interface AppGraph {
  class Impl : AppGraph, ContributionChunk_0, ContributionChunk_1 {
    interface ContributionChunk_0 : Contribution1, Contribution2
    interface ContributionChunk_1 : Contribution3, Contribution4
  }
}
```

`merged-supertype-chunk-size` controls how many contribution markers are grouped into each synthetic interface. Its default is `0`, which disables chunking. Values below `2` are also disabled.

Promoted parent interfaces can add more supertypes to a chunk. The configured size counts contribution markers rather than every resulting interface.

### IR contribution merging

Normally, contributions are visible during FIR processing and appear in graph metadata. `@MergeContributionsInIr` postpones contribution merging until the graph implementation is generated in IR.

This avoids building a large public graph supertype list in FIR. It can also combine well with supertype chunking for very large graphs.

Contributions merged only in IR are not available through:

- The source graph's Kotlin metadata.
- IDE completion.
- Graph includes.
- Kotlin/Native framework export.

Binding contributions can also use binding containers instead of adding one graph supertype per contributed implementation. `generate-contribution-providers` is disabled by default and can generate providers that expose only the bound type. This lets the implementation remain internal and can improve incremental compilation. The implementation type is no longer available directly from the graph unless it is exposed separately.

Preserve scope, replacement, and visibility rules when changing contribution representation.

### Generated member names

The default `member-naming-strategy` is `DESCRIPTIVE`. It keeps generated graphs readable.

```kotlin
private val accountRepositoryProvider = AccountRepository.MetroFactory.create()
private val authenticationServiceProvider =
  AuthenticationService.MetroFactory.create(accountRepositoryProvider)
```

`TYPED` and `MINIMAL` shorten generated names.

```kotlin
private val provider = AccountRepository.MetroFactory.create()
private val provider2 = AuthenticationService.MetroFactory.create(provider)
```

Short names reduce string-table size in unminified JVM artifacts. Nested shards restart their name allocator and can reuse the same short names across classes.

R8 and similar shrinkers can already rename private members. Do not assume the naming strategy changes the size of a fully minified artifact.

### Compile-time-only declarations

Some factory or mirror declarations exist for compiler metadata and cross-module discovery even when generated graph code does not need them at runtime. Eligible declarations can be marked `@ComptimeOnly` so JVM shrinkers can remove them.

Constant provider inlining must not remove a factory that is still needed to preserve lazy class initialization. Declaration mirrors must also remain available when compiler metadata does not provide the same information.

`omit-redundant-mirrors` is disabled by default. Changes to mirrors need both binary-size measurements and cross-module coverage.

## Measuring changes

Measure each of these separately:

- Build time and allocations.
- Generated declarations and artifact size.
- Runtime startup.

An improvement in one area can regress another.

The benchmark project generates repeatable workloads and records compiler work, runtime work, and garbage collection. Its README describes stable seeds and allocation profiling.

```bash
cd benchmark
./run_benchmarks.sh metro
./run_benchmarks.sh metro --profile async-profiler-heap
./run_startup_benchmarks.sh jvm --modes metro
```

Compiler tracing measures work performed by FIR and IR extensions. Runtime tracing measures work performed by generated graphs. They answer different questions.

`enable-runtime-tracing` is disabled by default. Its additional spans and provider wrappers change startup costs, so traced runs should not be compared directly with uninstrumented benchmarks.

Graph metadata reports expose binding counts, provider properties, shard counts, and generated initialization methods. Reports are intended for debugging and add enough work that they should not be treated as free instrumentation.

Use ordinary box tests for small generated shapes and IR dump tests when generated structure matters. Large generated graphs belong in tests named `StressTest` and run with the existing `metro.enableLargeTests` property.

```bash
./gradlew :compiler-tests:test -Pmetro.enableLargeTests
```

## Defaults that affect performance

| Setting                           | Default       | Effect                                              |
|-----------------------------------|---------------|-----------------------------------------------------|
| `shrink-unused-bindings`          | `true`        | Removes unreachable bindings                        |
| Binding expression depth          | `64`          | Splits deep generated expressions with getters      |
| `statements-per-init-fun`         | `25`          | Splits large initialization methods                 |
| `enable-graph-sharding`           | `true`        | Allows large graphs to use nested shards            |
| `keys-per-graph-shard`            | `2000`        | Sets the target number of bindings per shard        |
| `enable-switching-providers`      | `false`       | Keeps ordinary binding-specific provider generation |
| `merged-supertype-chunk-size`     | `0`           | Leaves contribution supertypes unchunked            |
| `generate-static-annotations`     | `true`        | Adds platform static factory entry points           |
| `enable-provider-inlining`        | `true`        | Inlines eligible constant providers                 |
| `generate-contribution-providers` | `false`       | Keeps the default contribution representation       |
| `enable-runtime-tracing`          | `false`       | Leaves generated graphs uninstrumented              |
| `parallel-threads`                | `0`           | Keeps graph validation sequential                   |
| `buffered-ic-tracking`            | `true`        | Deduplicates incremental compilation records        |
| `member-naming-strategy`          | `DESCRIPTIVE` | Keeps generated member names readable               |

## Contributor guidelines

When changing graph generation:

- Preserve deterministic graph ordering, binding identity, visibility, initialization order, and lazy behavior.
- Keep cycle members together when partitioning graphs.
- Preserve contextual provider and suspend wrappers when reusing binding keys or adding generated properties.
- Record incremental compilation lookups even when a cache answers the request.
- Keep shared graph snapshots immutable when validating siblings in parallel.
- Prefer existing graph representations and runtime helpers over duplicate infrastructure.
- Use memory-conscious collections for retained graph state and ordinary collections for short-lived analysis when appropriate.

Check the [inline provider notes](inline-providers.md), [suspend provider notes](suspend-providers.md), and [runtime tracing notes](runtime-tracing.md) before changing their respective paths.
