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

Runtime inputs can be provided via a `@DependencyGraph.Factory` interface that returns the target graph. These parameters must be annotated with either `@Provides` or `@Includes`.

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

[Binding Containers](#binding-containers) are a special type of `@Includes` type, see more in its section below.

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

Dependency graphs can be extended via _graph extensions_. As the name implies, graph extensions _extend_ a parent graph they are declared for and contain a superset of bindings that includes both the parent graph(s) as well as their own. These are similar in functionality to Dagger's _Subcomponents_.

Graph extensions must be either an interface or an abstract class and are annotated with `@GraphExtension`. They are created via `@GraphExtension.Factory` types.

Metro's compiler plugin will build, validate, and implement this graph at compile-time _when the parent graph is generated_. This means that graph extensions are not available until the parent graph is generated.

Graph extensions can be chained and implicitly inherit their parents' scopes.

### Creating Graph Extensions

You cannot create a graph extension independent of its parent graph, you may only access it via accessor on the parent graph. You can declare this in multiple ways:

* Declare an accessor on the parent graph directly.

```kotlin
@GraphExtension
interface LoggedInGraph

@DependencyGraph
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}
```

* (If the extension has a creator) declare the creator on the parent graph directly.

```kotlin
@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph {
  val loggedInGraphFactory: LoggedInGraph.Factory
}
```

* (If the extension has a creator) make the parent graph implement the creator.

```kotlin
@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory
```

* Contribute the factory to the parent graph via [@ContributesTo](#contributed-graph-extensions). More on this below.

```kotlin
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph
```

### Scoping

_See [Scopes](scopes.md) for more details on scopes!_

Like [DependencyGraph](#scoping), graph extensions may declare a `scope` (and optionally `additionalScopes` if there are more). Each of these declared scopes act as an implicit `@SingleIn` representation of that scope. For example:

```kotlin
@GraphExtension(AppScope::class)
interface AppGraph
```

Is functionally equivalent to writing the below.

```kotlin
@SingleIn(AppScope::class)
@GraphExtension(AppScope::class)
interface AppGraph
```

### Providers

Like [DependencyGraph](#dependency-graphs), graph extensions may declare providers via `@Provides` and `@Binds` to provide dependencies into the graph.

_Creators_ can provide instance dependencies and other graphs as dependencies.

```kotlin
@GraphExtension
interface AppGraph {
  val httpClient: HttpClient

  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}
```

### Creators

See [DependencyGraph](#inputs)'s section on creators.

### Aggregation

See [DependencyGraph](#scoping)'s section on aggregation.

### Contributed Graph Extensions

Graph extensions may be _contributed_ to a parent graph and its contribution merging will be deferred until the parent graph is generated.

#### The Problem

Imagine this module dependency tree:

```
        :app
      /     \
     v       v
  :login   :user-data
```

`:app` defines the main dependency graph with `@DependencyGraph`. The `:login` module defines a graph extension for authenticated user flows, and `:user-data` provides some core functionality like `UserRepository`.

If `:login` defines its own graph directly with `@DependencyGraph`, it won't see contributions from `:user-data` _unless_ `:login` depends on it directly.

#### The Solution

Instead, `:login` can use `@GraphExtension(LoggedInScope::class)` + `@ContributesTo` on its associated factory to say: "I want to contribute a new graph extension _to_ a future `AppScope` parent graph."

The graph extension will then be generated in `:app`, which already depends on both `:login` and `:user-data`. Now `UserRepository` can be injected in `LoggedInGraph`.

```kotlin
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {

  val userRepository: UserRepository

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}
```

In the `:app` module:

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph
```

The generated code will modify `AppGraph` to implement `LoggedInGraph.Factory` and implement `createLoggedInGraph()` using a generated final `LoggedInGraphImpl` class that includes all contributed bindings, including `UserRepository` from `:user-data`.

```kotlin
interface AppGraph
// modifications generated during compile-time
  interface AppGraph : LoggedInGraph.Factory {
  
  override fun createLoggedInGraph(): LoggedInGraph {
    return LoggedInGraphImpl(this)
  }

  // Generated in IR
  class $$MetroGraph : AppGraph {
    class LoggedInGraphImpl(appGraph: $$MetroGraph) : LoggedInGraph {
      // ...
    }
  }
}
```

Finally, you can obtain a `LoggedInGraph` instance from `AppGraph` since it now implements `LoggedInGraph.Factory`:

```kotlin
// Using the asContribution() intrinsic
val loggedInGraph = appGraph.asContribution<LoggedInGraph.Factory>().createLoggedInGraph()

// Or if you have IDE support enabled
val loggedInGraph = appGraph.createLoggedInGraph()
```

#### Graph arguments

You can pass arguments to the graph via the factory:

```kotlin
@ContributesTo(AppScope::class)
@GraphExtension.Factory
interface Factory {
  fun create(@Provides userId: String): LoggedInGraph
}
```

This maps to:

```kotlin
// Generated in IR
@DependencyGraph(LoggedInScope::class)
class LoggedInGraphImpl(
  parent: AppGraph,
  @Provides userId: String
): LoggedInGraph {
  // ...
}
```

In `AppGraph`, the generated factory method looks like:

```kotlin
// Generated in IR
override fun create(userId: String): LoggedInGraph {
  return LoggedInGraphImpl(this, userId)
}
```

!!! warning
    Abstract factory classes cannot be used as graph contributions.

Contributed graphs may also be chained.

## Binding Containers

Binding containers are classes, objects, or interfaces annotated with `@BindingContainer` that contain binding declarations (`@Provides` or `@Binds`) but are not themselves complete dependency graphs. They're analogous to Dagger's `@Module` annotation and can be used in cases where defining bindings in an (extended) interface is unwieldy or not helpful.

Unlike graphs and other `@Includes` types, their public accessors are _not_ read. Only `@Binds` and `@Provides` declarations are read.

!!! tip
    Binding containers can be seen as partial graphs and are intended to be reusable, composable units that are _included_ in a complete graph.

### Including via `@Includes` Parameters

The most flexible way to include binding containers is via `@Includes`-annotated parameters on graph factories.

```kotlin
@BindingContainer
class NetworkBindings(private val baseUrl: String) {
  @Provides fun provideHttpClient(): HttpClient = HttpClient(baseUrl)
}

@DependencyGraph
interface AppGraph {
  val httpClient: HttpClient

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes networkBindings: NetworkBindings): AppGraph
  }
}
```

This allows you to bring any instance to the graph with its own internal logic.

### Including via `@DependencyGraph.bindingContainers`

For simple binding containers, you can declare them directly in the graph annotation:

```kotlin
@BindingContainer
object NetworkBindings {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph(bindingContainers = [NetworkBindings::class])
interface AppGraph {
  val httpClient: HttpClient
}
```

This method works for:

- `object` classes
- `interface` or `abstract class` types with only `@Binds` providers or companion object `@Provides` providers
- Simple classes with a public, no-arg constructor

### Chaining Binding Containers

Binding containers can include other binding containers using the `includes` parameter:

```kotlin
@BindingContainer
object CacheBindings {
  @Provides fun provideHttpCache(): Cache = Cache()
}

@BindingContainer(includes = [CacheBindings::class])
object NetworkBindings {
  @Provides fun provideHttpClient(cache: Cache): HttpClient = HttpClient(cache)
}

@DependencyGraph(bindingContainers = [NetworkBindings::class])
interface AppGraph {
  val httpClient: HttpClient
}
```

The transitive closure of all included binding containers will be included in the final consuming graph.

### Contributing Binding Containers

Binding containers can be contributed to scopes via `@ContributesTo`:

```kotlin
@ContributesTo(AppScope::class)
@BindingContainer
object NetworkBindings {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val httpClient: HttpClient
}
```

They can also replace other contributed binding containers:

```kotlin
// In a test variant
@ContributesTo(AppScope::class, replaces = [NetworkBindings::class])
@BindingContainer
object FakeNetworkBindings {
  @Provides fun provideFakeHttpClient(): HttpClient = FakeHttpClient()
}
```

Graphs may exclude contributed containers:

```kotlin
@DependencyGraph(AppScope::class, excludes = [NetworkBindings::class])
interface AppGraph {
  val httpClient: HttpClient
}
```

### Notes

- Companion objects, annotation classes, and enum classes/entries cannot be annotated with `@BindingContainer`.
- Provides within a binding container's companion object are automatically included.
- Enclosing classes of `@Binds` or `@Provides` providers don't need to be annotated with `@BindingContainer` for Metro to process them - the annotation is primarily for reference to `@DependencyGraph.Factory` and the ability to use `includes`.
- Binding containers may also be [contributed](aggregation.md#contributing-binding-containers).
- See [#172](https://github.com/ZacSweers/metro/issues/172) for more details.

## Dynamic Graphs

Dynamic graphs are a powerful feature of the Metro compiler that allow for dynamically replacing bindings in a given graph. To use them, you can pass in a vararg set of _binding containers_ to the `createDynamicGraph()` and `createDynamicGraphFactory()` intrinsics.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String
  
  @Provides fun provideMessage(): String = "real"
}

class AppTest {
  val testGraph = createDynamicGraph<AppGraph>(FakeBindings)
  
  @Test
  fun test() {
    assertEquals("fake", testGraph.message)
  }

  @BindingContainer
  object FakeBindings {
    @Provides fun provideMessage(): String = "fake"
  }
}
```

The compiler will dynamically generate a hidden graph impl _within the enclosing class or file_ that is unique to the combination of input [containers] and target type [T].

**Constraints**

- All containers must be instances (or objects) of _binding containers_.
- It's an error to pass no containers.
- All containers must be non-local, canonical classes. i.e., they must be something with a name!
- This overload may be called in a member function body, top-level function body, or property
  initializer.
- The target [T] graph _must_ be annotated with `@DependencyGraph` and must be a
  valid graph on its own.

??? note "Implementation Notes"

    - The bulk of this implementation is in `IrDynamicGraphGenerator`.
    - The generated graph impl is a private nested (static) class of the enclosing class or file.
    - This doesn't swap bindings in a real graph or use a real graph at all, instead tracking available dynamic bindings and preferring them when constructing a graph in `BindingGraphGenerator`.

## General Implementation Notes

Dependency graph code gen is designed to largely match how Dagger components are generated.

* Internal graph validation uses [Tarjan's algorithm](https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm) + [topological sort](https://en.wikipedia.org/wiki/Topological_sorting) implementation.
  * This runs in O(V+E) time
  * The returned ordered list of bindings can be used to determine provider field generation order.
  * Any binding whose order depends on one later in the returned order implicitly requires use of `DelegateFactory`.
* Dependencies are traversed from public accessors and `inject()` functions.
* Metro generates Provider Factory classes for each provider. These should be generated at the same time that the provider is compiled so that their factory classes. This is for two primary purposes:
    * They can be reused to avoid code duplication
    * Metro can copy default values for provider values over to the generated factory to support optional dependencies. Since default values may refer to private references, we must generate these factories as nested classes.
* Metro generates a graph *impl* class that holds all aggregated bindings and manages scoping.
* Scoped bindings are stored in provider fields backed by `DoubleCheck`.
* Reused unscoped providers instances are stored in reusable fields.
* `@Provides` factory parameters are stored in a field backed by `InstanceFactory`.
* Multibindings create new collection instances every time.
* Multibinding providers are not accessible as standalone bindings.
* Graph extensions are implemented via a combination of things
    * Custom `MetroMetadata` is generated and serialized into Kotlin's `Metadata` annotations.
    * Extendable parent graphs opt-in to generating this metadata. They write information about their available provider and instance fields, binds callable IDs, parent graphs, and provides callable IDs.
    * Extendable parent graphs generate `_metroAccessor`-suffixed `internal` functions that expose instance fields and provider fields.
    * Child graphs read this metadata and look up the relevant callable symbols, then incorporating these when building its own binding graph.
