# Graph Lifecycle

Graphs can run startup and cleanup callbacks. Implement `CloseableGraph` for synchronous cleanup or `SuspendCloseableGraph` for cleanup that can suspend. These interfaces and the callback annotations require `@OptIn(ExperimentalMetroApi::class)`.

```kotlin
@OptIn(ExperimentalMetroApi::class)
@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit
  fun initialize(database: Database) {
    database.start()
  }

  @OnGraphClose
  fun shutdown(database: Database) {
    database.close()
  }
}
```

Metro resolves callback parameters through the dependency graph. Scope resources that should be shared between callbacks and other consumers. Callback parameters participate in dependency validation, including missing bindings and suspend dependency paths.

`CloseableGraph` extends Kotlin's `AutoCloseable`, so synchronous graphs work with `use`. Metro generates `close()`. Put cleanup in an `@OnGraphClose` callback. Implementing `close()` manually on a graph is an error.

## Callback rules

Callbacks must be implemented member functions that return `Unit`. Parameterless callbacks are supported. A function with both annotations runs once during initialization and once during close. Callbacks run sequentially and their order is unspecified.

Contributed interfaces can declare callbacks without extending a lifecycle interface themselves. Metro checks that the assembled graph implements `CloseableGraph` or `SuspendCloseableGraph`. Inherited callbacks run once through diamond inheritance. An override must repeat each lifecycle annotation. Metro calls the override.

Direct calls and callable references to annotated callbacks are errors. This also applies to calls to annotated `super` functions.

## Initialization and failures

Metro runs init callbacks after ordinary graph initialization. Graph creation returns only after every init callback succeeds. A failure or cancellation stops initialization immediately. Metro doesn't roll back initialization or run close callbacks automatically. The application owns any resources acquired before the failure.

The first call to `close()` marks the graph closed before cleanup begins. Concurrent, repeated, and reentrant calls return immediately. They don't wait for the first attempt. Close callbacks run at most once.

A failure while resolving a callback argument, running a callback, or closing a child stops cleanup immediately. The error reaches the winning caller. Remaining cleanup doesn't run, and the graph stays closed. Later calls don't retry cleanup.

Closing preserves ordinary dependency requests and cached bindings. An accessor or provider used afterward can return an already closed resource or initialize a new binding. Holding a graph or one of its providers can retain its cached values. End use before cleanup and release those references when you're done.

## Suspending callbacks

Enable suspend support in the Metro Gradle configuration:

```kotlin
metro {
  enableSuspendProviders.set(true)
}
```

A callback that directly requests a suspend dependency must itself be `suspend`. Requesting `suspend () -> Database` or `SuspendLazy<Database>` defers resolution and permits a synchronous callback.

Suspending initialization requires a factory with a suspend single abstract method:

```kotlin
@OptIn(ExperimentalMetroApi::class)
@DependencyGraph
interface AppGraph : SuspendCloseableGraph {
  @OnGraphInit
  suspend fun initialize(database: Database) {
    database.migrate()
  }

  @OnGraphClose
  suspend fun shutdown(database: Database) {
    database.close()
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(@Provides database: Database): AppGraph
  }
}

val factory = createGraphFactory<AppGraph.Factory>()
val graph = factory.create(database)
```

Factory retrieval is synchronous. Calling the factory waits for initialization. Extensions use the same rule through `@GraphExtension.Factory`, including factories implemented by a parent graph. A property can't directly create a child whose initialization suspends. Dynamic graph factories are checked against their concrete binding replacements too.

Suspending close callbacks require `SuspendCloseableGraph` and the `runtime-coroutines` artifact. Cleanup, including children and parent removal, runs in `NonCancellable`. Cancelling the caller doesn't interrupt cleanup. A cleanup exception still stops the attempt and propagates. A graph with only suspending close callbacks can be created synchronously.

Graphs don't own the jobs used to initialize scoped suspend bindings. Cancel application-owned scopes or dispose resources explicitly in callbacks.

## Child ownership

When both a parent and its child extension support lifecycle callbacks, successful child initialization registers that child with the parent before returning it. This applies to graph accessors and extension factories. An existing graph passed as an input stays caller-owned.

The parent closes its children before running its own close callbacks. Each child closes its descendants first. Sibling order is unspecified. A synchronous `CloseableGraph` parent can't own a `SuspendCloseableGraph` child. A suspend parent can own both kinds.

```kotlin
val factory = parent.userGraphFactory
val first = factory.create(firstUser)
val second = factory.create(secondUser)

first.close()
parent.close() // Closes second before the parent's callbacks.
```

The parent retains each registered child until the child's winning close attempt finishes or the parent starts closing its children. Independent child cleanup unregisters the child in `finally`, even when it fails. Close children you no longer need so a long-lived parent can release them.

Creating an extension through a closed parent fails, including through a factory retrieved before close. Wait for child creation and independent child cleanup to finish before closing the parent.

## Dependency warnings

`UnscopedLifecycleParameter` warns when a scalar callback parameter requests an unscoped binding. It can create a temporary instance or close a different instance from the one used elsewhere. Scope the binding when consumers should share it. Request a provider or lazy value when resolution should be explicit. Deferred requests, including suspend forms, are exempt. Supplied `@Provides` inputs count as implicitly scoped.

`CloseOnlyBinding` warns when a binding is reachable only from close callbacks. Cleanup may create it for the first time. Requests from ordinary accessors, init callbacks, or child graphs count as uses outside close. Deferred close requests still participate in this warning. Supplied inputs and values obtained through `@Includes` are exempt.

The warnings describe where a binding can be requested. They don't establish whether an accessor was called. Suppress either warning independently when the behavior is intentional:

```kotlin
@Suppress("CLOSE_ONLY_BINDING")
@OnGraphClose
fun shutdown(database: Database) {
  database.close()
}
```

## Tracing

[Runtime tracing](performance.md#runtime-tracing) records initialization, close, and each callback, including callback argument resolution. Spans finish on failure. The caller owns the tracer. Closing a graph leaves it open, including when child graphs share the parent's tracer.
