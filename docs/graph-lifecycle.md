# Graph Lifecycle

Graphs can run code when they're created and when they're closed. Annotate a function with `@OnGraphInit` or `@OnGraphClose`, and make the graph implement `CloseableGraph`. Metro injects the function's parameters from the graph and generates `close()`.

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val database: Database

  @OnGraphInit
  fun migrate(database: Database) {
    database.migrate()
  }

  @OnGraphClose
  fun closeDatabase(database: Database) {
    database.close()
  }
}

val graph = createGraph<AppGraph>() // Runs migrate()
graph.close() // Runs closeDatabase()
```

!!! warning "Experimental"

    `CloseableGraph`, `SuspendCloseableGraph`, `@OnGraphInit`, and `@OnGraphClose` are annotated with `@ExperimentalMetroApi`. Using them requires `@OptIn(ExperimentalMetroApi::class)` or `-opt-in=dev.zacsweers.metro.ExperimentalMetroApi`.

## Closeable graphs

A graph with lifecycle callbacks must implement `CloseableGraph` or `SuspendCloseableGraph`. `CloseableGraph` extends `AutoCloseable`, so `use {}` works with it.

```kotlin
createGraph<AppGraph>().use { graph ->
  graph.database.query()
}
```

Metro generates `close()`. Declaring your own implementation is an error. A graph can implement either interface without any callbacks. Closing it then closes its graph extensions.

## Callbacks

Lifecycle callbacks are ordinary functions with a body that return `Unit`. They can be declared on the graph, on one of its supertypes, or on a `@ContributesTo` interface that's merged into the graph. Library modules can contribute their own cleanup this way.

```kotlin
@ContributesTo(AppScope::class)
interface AnalyticsLifecycle {
  @OnGraphClose
  fun flushAnalytics(analytics: Analytics) {
    analytics.flush()
  }
}
```

Callback parameters are requested from the graph like accessors. They take part in validation, so a missing binding is reported against the callback parameter. They can request `Provider`, `Lazy`, and other wrapped types. Parameters can't have default values, `@OptionalBinding`, or `@Assisted`.

Callbacks must be public, or protected in an abstract class graph. They can't have type parameters, extension receivers, or context parameters. They also can't have other Metro annotations, such as `@Provides`.

Metro calls each callback once. A callback inherited through more than one supertype still runs once. A function that overrides a callback must repeat its annotation, and the override replaces the overridden callback. Calling a callback yourself, including through `super`, or taking a reference to it is an error.

Metro runs callbacks in a stable order but doesn't promise a specific one. Don't rely on one callback running before another.

## Init

Init callbacks run while the graph is being created, after the graph has set itself up. If an init callback throws, graph creation throws the same exception.

## Close

Closing a graph runs its close callbacks. The first call to `close()` does the work and later calls return right away. If a close callback throws, the graph stays closed and the exception propagates from `close()`.

Avoid close callback parameters that the graph doesn't otherwise use. Resolving them during close may create those instances just to clean them up. The `CloseOnlyBinding` warning reports these. See [warnings](#warnings).

## Graph extensions

Graph extensions can have callbacks too. When a closeable graph creates a closeable graph extension, the parent tracks it.

- Closing the parent closes its tracked extensions before running the parent's own close callbacks.
- Closing an extension directly removes it from its parent.
- A closed parent can't create more extensions. Trying throws an `IllegalStateException`.

A parent holds a reference to each extension it tracks until that extension closes. Close extensions you no longer need, such as a graph for a finished screen or request. Otherwise they stay in memory until their parent closes.

A non-closeable extension of a closeable graph isn't tracked. A `CloseableGraph` parent can't create a `SuspendCloseableGraph` extension, since it couldn't close it. Make the parent a `SuspendCloseableGraph` instead.

## Suspend callbacks

Callbacks can be `suspend` functions. This needs Metro's [suspend provider support](coroutines.md), which is enabled with `enableSuspendProviders`.

Graph creation suspends when an init callback is a `suspend` function or one of its parameters depends on a suspend binding. A graph like this must be created with a graph factory whose function is `suspend`. `createGraph()` and accessors that return a graph extension directly can't create it.

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit
  suspend fun warmUp(database: Database) {
    database.warmUp()
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(): AppGraph
  }
}

val graph = createGraphFactory<AppGraph.Factory>().create()
```

The same rule applies to graph extensions created through a `@GraphExtension.Factory` and to dynamic graphs created with `createDynamicGraphFactory`.

A non-suspend callback can't have a parameter that depends on suspend bindings. Make the callback `suspend`, or request `suspend () -> T` instead.

### Suspend close

Graphs with `suspend` close callbacks implement `SuspendCloseableGraph`. Its `close()` is a `suspend` function. A `CloseableGraph` can't have them.

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : SuspendCloseableGraph {
  @OnGraphClose
  suspend fun closeDatabase(database: Database) {
    database.closeAsync()
  }
}
```

Cleanup runs in a `NonCancellable` context. Cancelling the coroutine that called `close()` doesn't stop cleanup partway through. `SuspendCloseableGraph` uses `dev.zacsweers.metro:runtime-coroutines`. The Gradle plugin adds it automatically.

## Warnings

Metro reports two warnings for callback parameters that are likely mistakes.

`UnscopedLifecycleParameter` reports a parameter that requests an unscoped binding directly. The callback gets its own instance, which can be different from the one the rest of the graph uses. Closing that instance usually doesn't close the one your app used. Scope the binding, or request `() -> T` or `Lazy<T>` if a separate instance is intended. For a multibinding parameter like `Set<Closeable>`, Metro checks the bindings contributed to it.

`CloseOnlyBinding` reports a close callback parameter whose binding is only used by close callbacks. Resolving it during close creates the instance for the first time. Graph inputs and bindings from a parent graph don't count.

Suppress either warning with `@Suppress("UNSCOPED_LIFECYCLE_PARAMETER")` or `@Suppress("CLOSE_ONLY_BINDING")`, or with kotlinc's [`-Xwarning-level`](https://kotlinlang.org/docs/compiler-reference.html#xwarning-level).

## Tracing

With [runtime tracing](performance.md#runtime-tracing) enabled, graphs record a span for each lifecycle phase, like `AppGraph init` and `AppGraph close`. Each callback gets its own span inside it, like `@OnGraphInit AppGraph.migrate`. These spans record `metro.lifecycle_phase` and `metro.callable` metadata.

## Graph reports

[Graph reports](graph-analysis.md) list lifecycle callbacks as roots, with the keys their parameters request. The graph viewer shows them alongside accessors and injectors.
