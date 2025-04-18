# Dependency Graphs

The primary entry points in Metro are *dependency graphs*. These are interfaces annotated with `@DependencyGraph` and created with `@DependencyGraph.Factory` interfaces. Graphs expose types from the object graph via accessor properties or functions.

!!! tip
    These are synonymous with *components* and `@Component`/`@Component.Factory` in Dagger and kotlin-inject.

!!! tip
    “Accessors” in Metro are synonymous with Dagger’s [provision methods](https://dagger.dev/api/latest/dagger/Component.html#provision-methods).

Accessors and member injections act as roots, from which the dependency graph is resolved. Dependencies can be provided via conventional `@Provides` functions in graphs or their supertypes, constructor-injected classes, or accessed from graph dependencies.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @Provides
  fun provideMessage(): String = "Hello, world!"
}
```

*Note the `@Provides` function must define an explicit return type.*

Simple graphs like this can be created via the `createGraph()` intrinsic.

```kotlin
val graph = createGraph<AppGraph>()
```

Graphs are relatively cheap and should be used freely.

## Inputs

Runtime inputs can be provided via a `@DependencyGraph.Factory` interface that returns the target graph. These parameters must be annotated with exactly one of `@Provides`, `@Includes`, or `@Extends`.

### Provides

The simplest input is an instance parameter annotated with `@Provides`. This provides this instance as an available binding on the graph.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides message: String): AppGraph
  }
}
```

Provided parameters may be any type.

!!! tip
    This is analogous to Dagger's `@BindsInstance`.

### Includes

`@Includes`-annotated parameters are treated as containers of available bindings. Metro will treat _accessors_ of these types as usable dependencies.

They are commonly other graph types whose' dependencies you want to consume via explicit API.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes messageGraph: MessageGraph): AppGraph
  }

  @DependencyGraph interface MessageGraph {
    val message: String

    @Provides fun provideMessage(): String = "Hello, world!"
  }
}
```

`@Includes` instance dependencies do not _need_ to be other graphs though! They can be any regular class type. They _cannot_ be enums or annotation classes.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes messageProvider: MessageProvider): AppGraph
  }

  interface MessageProvider {
    val message: String
  }
}
```

!!! warning
    Includes parameters cannot be injected from the graph.

### Extends

`@Extends`-annotated parameters are for extending parent graphs. See _Graph Extensions_ at the bottom of this doc for more information.

### Creating factories

Graph factories can be created with the `createGraphFactory()` intrinsic.

```kotlin
val messageGraph =
  createGraphFactory<AppGraph.Factory>()
    .create("Hello, world!")
```

## Scoping

_See [Scopes](scopes.md) for more details on scopes!_

Graphs may declare a `scope` (and optionally `additionalScopes` if there are more). Each of these declared scopes act as an implicit `@SingleIn` representation of that scope for [aggregation](aggregation.md).

For example:
```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph
```

Is functionally equivalent to writing the below.

```kotlin
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph
```

## Graph Extensions

Dependency graphs can be marked as _extendable_ to allow child graphs to extend them. These are similar in functionality to Dagger's `Subcomponents` but are detached in nature like in kotlin-inject.

A graph must opt itself into extension in via `@DependencyGraph(..., isExtendable = true)`, which will make the Metro compiler generate extra metadata for downstream child graphs.

Then, a child graph can add an `@Extends`-annotated parameter to its creator to extend that graph.

```kotlin
@DependencyGraph(isExtendable = true)
interface AppGraph {
  @Provides fun provideHttpClient(): HttpClient { ... }
}

@DependencyGraph
interface UserGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Extends appGraph: AppGraph): UserGraph
  }
}
```

Child graphs then contain a _superset_ of bindings they can inject, including both their bindings and their parents'. Graph extensions can be chained as well.

Child graphs also implicitly inherit their parents' _scopes_.

## Implementation Notes

Dependency graph code gen is designed to largely match how Dagger components are generated.

* Dependencies are traversed from public accessors and `inject()` functions.
* Metro generates Provider Factory classes for each provider. These should be generated at the same time that the provider is compiled so that their factory classes. This is for two primary purposes:
    * They can be reused to avoid code duplication
    * Metro can copy default values for provider values over to the generated factory to support optional bindings. Since default values may refer to private references, we must generate these factories as nested classes.
* Metro generates a graph *impl* class that holds all aggregated bindings and manages scoping.
* Scoped bindings are stored in provider fields backed by `DoubleCheck`.
* Reused unscoped providers instances are stored in reusable fields.
* `@Provides` factory parameters are stored in a field backed by `InstanceFactory`.
* Multibindings create new collection instances every time.
* Multibinding providers are not accessible as standalone bindings.
* Graph extensions are implemented via combination of things
    * Custom `MetroMetadata` is generated and serialized into Kotlin's `Metadata` annotations.
    * Extendable parent graphs opt-in to generating this metadata. They write information about their available provider and instance fields, binds callable IDs, parent graphs, and provides callable IDs.
    * Extendable parent graphs generate `_metroAccessor`-suffixed `internal` functions that expose instance fields and provider fields.
    * Child graphs read this metadata and look up the relevant callable symbols, then incorporating these when building its own binding graph.