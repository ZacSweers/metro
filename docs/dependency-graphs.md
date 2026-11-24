# Dependency Graphs

A dependency graph is Metro’s entry point to an object graph. Declare an interface or abstract class with `@DependencyGraph`. Its accessor properties and functions expose bindings. Use a `@DependencyGraph.Factory` when the graph needs runtime inputs.

!!! tip
    These are synonymous with *components* and `@Component`/`@Component.Factory` in Dagger and kotlin-inject.

!!! tip
    “Accessors” in Metro are synonymous with Dagger’s [provision methods](https://dagger.dev/api/latest/dagger/Component.html#provision-methods).

Metro resolves dependencies from accessor and member injection roots. Bindings come from `@Provides` declarations in the graph or its supertypes, injected constructors, and included graph dependencies.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @Provides
  fun provideMessage(): String = "Hello, world!"
}
```

*A `@Provides` function must declare its return type.*

Create a graph without runtime inputs with `createGraph()`:

```kotlin
val graph = createGraph<AppGraph>()
```

Graphs are relatively cheap to create, so use separate graphs when their ownership or lifetime calls for one.

## Inputs

Declare a `@DependencyGraph.Factory` that returns the graph to supply runtime inputs. Every factory input must be annotated with `@Provides` or `@Includes`.

### Provides

A parameter annotated with `@Provides` becomes an instance binding in the graph.

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

Metro reads the public accessors of an `@Includes` parameter as available bindings.

Use this to consume another graph through its public API.

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

Your `@Includes` input doesn’t have to be another graph! You can pass an ordinary class. Enums and annotation classes aren’t supported.

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

See [Binding Containers](#binding-containers) for a special kind of included dependency.

### Creating factories

Graph factories can be created with the `createGraphFactory()` intrinsic.

```kotlin
val messageGraph =
  createGraphFactory<AppGraph.Factory>()
    .create("Hello, world!")
```

## Scoping

_See [Scopes](scopes.md) for more details on scopes!_

A graph declares its aggregation scope with `scope` and can add more with `additionalScopes`. Each declared scope acts as an implicit `@SingleIn` for [aggregation](aggregation.md).

For example:
```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph
```

This is equivalent to:

```kotlin
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph
```

## Graph lifecycle

Graphs can run startup and cleanup code in annotated callback functions. Metro calls each callback, injects its parameters, and validates them with the rest of the graph.

A graph opts in by implementing `CloseableGraph` or `SuspendCloseableGraph` and annotating functions with `@OnGraphInit` or `@OnGraphClose`.

```kotlin
@SingleIn(AppScope::class)
@Inject
class Analytics {
  fun start() { /* ... */ }
  fun flush() { /* ... */ }
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit
  fun initialize(analytics: Analytics) {
    analytics.start()
  }

  @OnGraphClose
  fun shutdown(analytics: Analytics) {
    analytics.flush()
  }
}
```

`CloseableGraph` extends `AutoCloseable`, so `use {}` works with it too. `SuspendCloseableGraph` has a suspend `close()` for cleanup that suspends. Metro generates the implementation of `close()`, so implementing it yourself is reported as a compiler error.

### Callbacks

Callbacks are ordinary functions with a body that return `Unit`. Each function can have only one lifecycle annotation. A graph can have any number of callbacks, with or without parameters. They run in no particular order.

Only Metro may *invoke* these functions. Calling one directly or taking a reference to it is an error. An override can still call the callback it overrides through `super`.

The names are hopefully self-explanatory! Init callbacks run after the graph's own initialization. If one fails or creation is cancelled, creation stops there.

!!! note

    Graph factories and `createGraph()` return the graph only after every init callback succeeds.

!!! warning

    Metro doesn't run `close` callbacks to undo partial startup, so release anything acquired before the failure yourself.

### Contributed callbacks

A contributed interface can bring its own callbacks.

```kotlin
@ContributesTo(AppScope::class)
interface AnalyticsLifecycle {
  @OnGraphInit
  fun initializeAnalytics(analytics: Analytics) {
    analytics.start()
  }

  @OnGraphClose
  fun closeAnalytics(analytics: Analytics) {
    analytics.flush()
  }
}
```

- The graph that the interface is contributed to **must** implement `CloseableGraph` or `SuspendCloseableGraph` (the interface itself doesn't have to).
- Metro calls an inherited callback exactly once, even through diamond inheritance.
- An override replaces the inherited callback.
- It can call the inherited callback with `super`.
- It must re-declare the callback's annotation and the qualifiers on its own parameters.

### Callback parameters

Metro resolves callback parameters like graph accessors and validates them at compile time.

!!! warning

    Close callbacks have two traps! Metro warns about both. Suppress either warning when you intend that behavior.
    
    1. A binding that nothing else requests can be created during close just to be cleaned up.
    2. An unscoped binding gives the callback a new instance, so cleaning it up leaves the one the rest of the graph used untouched.

    [UnscopedLifecycleParameter](diagnostics.md#unscopedlifecycleparameter) reports any callback parameter that requests an unscoped binding directly (not including graph inputs or values from included graphs). Request `() -> T` or `Lazy<T>` to circumvent this, but only if you need to!

    [CloseOnlyBinding](diagnostics.md#closeonlybinding) reports a binding that only `close` callbacks request.

### Closing a graph

The first call to `close()` marks the graph closed and runs any `@OnGraphClose` callbacks. Later calls return immediately, even while cleanup is running, so close callbacks run at most once. A close callback or extension that fails doesn't stop the rest of cleanup. `close()` throws the first failure after cleanup finishes, with later failures attached as suppressed exceptions.

!!! note "Post-close graph usage"

    A closed graph still serves dependency requests. A request after close can create a binding for the first time, so stop using a graph before you close it.
    
    Closing does not clear cached instances. The graph and its providers retain them until they can be garbage-collected.

    Creating a graph extension through a closed graph **fails**, even with a factory obtained earlier.

#### Graph extensions

Closing a graph closes its closeable extensions first, then runs its own close callbacks. Each extension closes its own extensions the same way. Closing an extension yourself removes it from its parent's internal references.

```kotlin
val factory = appGraph.userGraphFactory
val first = factory.create(firstUser)
val second = factory.create(secondUser)

first.close()
appGraph.close() // Closes second, then runs appGraph's close callbacks. `first` is already closed
```

!!! warning

    A `CloseableGraph` keeps a reference to each closeable extension it creates until the extension or the parent graph closes. Close extensions when you're done with them so they can be garbage collected.

An extension joins its parent’s cleanup after its init callbacks succeed. If the parent closes during initialization, creation fails and Metro closes the extension first. If a non-suspend factory creates an extension with a suspend `close()`, creation still fails in this race, but the extension stays open. An `@Includes` graph is owned separately and is not closed by the consuming graph. Parents close sibling extensions in no particular order.

A `CloseableGraph` can't own a `SuspendCloseableGraph` extension, but a `SuspendCloseableGraph` can own both kinds.

A graph that isn't closeable can create closeable extensions too. It doesn't track them, so close each one yourself. No graph above it can be closeable, because closing that graph couldn't reach the extension.

### Suspend callbacks

Callbacks can be `suspend` functions when [suspend provider support](coroutines.md) is enabled.

A graph with a suspend init callback needs a graph factory whose creation function suspends. A non-suspend callback can't request a binding that suspends. Request `suspend () -> T` or `SuspendLazy<T>` to defer it.

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit
  suspend fun initialize(database: Database) {
    database.migrate()
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(@Provides config: AppConfig): AppGraph
    // ^^^ create() is suspending
  }
}

val factory = createGraphFactory<AppGraph.Factory>()
val graph = factory.create(config) // Returns after initialize() finishes
```

Obtaining the factory instance alone doesn't suspend, only invoking its `create` function does. Graph extensions and dynamic graph factories follow the same rule through their `@GraphExtension.Factory`.

Suspend `@OnGraphClose` callbacks require `SuspendCloseableGraph`. Cancelling the caller of `close()` doesn't interrupt cleanup. A failure still reaches the caller after cleanup finishes.

!!! tip

    A graph with *only* suspending `@OnGraphClose` callbacks can be created without suspending.

### Coroutines and resources

Scoped suspend bindings initialize in the caller's coroutine context, so the graph doesn't own that work. Closing a graph also doesn't cancel or dispose of the instances it created, such as a `CoroutineScope` binding. You're responsible for closing resources you own in an `@OnGraphClose` callback.

### Tracing

With [runtime tracing](performance.md#runtime-tracing) enabled, Metro records how long the init callbacks and close take, with a span for each callback. The graph doesn't own its tracer, so closing the graph leaves the tracer open.

## Graph Extensions

A graph extension inherits its parent’s bindings and adds its own. It is similar to a Dagger subcomponent.

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

### Graph-private Bindings

Graph extensions inherit all eligible parent bindings by default. Mark `@Provides`, `@Binds`, or `@Multibinds` declarations with `@GraphPrivate` to keep them in the declaring graph:

- Private `@Provides` and `@Binds` bindings **may not** be exposed directly via an accessor.
- The binding **will not** be visible to extensions of this graph.

Private bindings _may_ be depended on by other bindings within the same graph as an implementation detail. `@GraphPrivate @Multibinds` declarations may also be requested from the graph that declares them; the privacy boundary only prevents them from being inherited by extensions.

#### Private Implementation Bindings

Use `@GraphPrivate` when a binding should be available as an implementation detail of its graph, but should not become part of that graph's public accessor surface or extension surface.

```kotlin
@Inject
class ApiService(val httpClient: HttpClient)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val apiService: ApiService

  @GraphPrivate
  @Provides
  fun provideHttpClient(): HttpClient = HttpClient()

  // Error: cannot expose a @GraphPrivate binding as an accessor
  val httpClient: HttpClient

  fun loggedInGraph(): LoggedInGraph
}

@GraphExtension
interface LoggedInGraph {
  // Error: HttpClient is @GraphPrivate in the parent and not visible here
  val httpClient: HttpClient
}
```

#### Same-typed Scoped Bindings

`@GraphPrivate` is also useful when parent and child graphs both provide the same scoped type. The private binding remains available inside the graph that provides it, so consumers can request the plain type without qualifiers that only exist to distinguish graph ownership.

Compare a qualifier-based version with the same graph-local binding expressed with `@GraphPrivate`:

=== "With qualifiers"

    ```kotlin
    @Inject
    class AppWorker(@Named("app") val scope: CoroutineScope)

    @Inject
    class LoggedInWorker(@Named("logged-in") val scope: CoroutineScope)

    @DependencyGraph(AppScope::class)
    interface AppGraph {
      val appWorker: AppWorker

      @Named("app")
      @Provides
      @SingleIn(AppScope::class)
      fun provideCoroutineScope(): CoroutineScope = CoroutineScope("app")

      val loggedInGraph: LoggedInGraph
    }

    @GraphExtension(LoggedInScope::class)
    interface LoggedInGraph {
      val loggedInWorker: LoggedInWorker

      @Named("logged-in")
      @Provides
      @SingleIn(LoggedInScope::class)
      fun provideCoroutineScope(): CoroutineScope = CoroutineScope("logged-in")
    }
    ```

=== "With @GraphPrivate"

    ```kotlin
    @Inject
    class AppWorker(val scope: CoroutineScope)

    @Inject
    class LoggedInWorker(val scope: CoroutineScope)

    @DependencyGraph(AppScope::class)
    interface AppGraph {
      val appWorker: AppWorker

      @GraphPrivate
      @Provides
      @SingleIn(AppScope::class)
      fun provideCoroutineScope(): CoroutineScope = CoroutineScope("app")

      fun loggedInGraph(): LoggedInGraph
    }

    @GraphExtension(LoggedInScope::class)
    interface LoggedInGraph {
      val loggedInWorker: LoggedInWorker

      @GraphPrivate
      @Provides
      @SingleIn(LoggedInScope::class)
      fun provideCoroutineScope(): CoroutineScope = CoroutineScope("logged-in")
    }
    ```

#### Graph-private Multibindings

Graph-local multibindings use the same pattern, but the private binding is the collection declaration plus its local contributions. This is useful for graph-local registries such as menu actions, navigation entries, or feature handlers. You can leave individual contributions public when they should continue to be visible to extensions.

```kotlin
data class MenuAction(val label: String)

@Inject
class AppMenu(val actions: Set<MenuAction>)

@Inject
class AccountMenu(val actions: Set<MenuAction>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val appMenu: AppMenu

  @GraphPrivate
  @Multibinds(allowEmpty = true)
  val menuActions: Set<MenuAction>

  @GraphPrivate
  @Provides
  @IntoSet
  fun provideHelpAction(): MenuAction = MenuAction("Help")

  fun loggedInGraph(): LoggedInGraph
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val accountMenu: AccountMenu

  @GraphPrivate
  @Multibinds(allowEmpty = true)
  val menuActions: Set<MenuAction>

  @GraphPrivate
  @Provides
  @IntoSet
  fun provideSignOutAction(): MenuAction = MenuAction("Sign out")
}

fun example(appGraph: AppGraph) {
  // appActions contains only "Help"
  val appActions = appGraph.appMenu.actions

  // loggedInActions contains only "Sign out"
  val loggedInActions = appGraph.loggedInGraph().accountMenu.actions
}
```

??? note "Binds Implementation Notes"

    A non-private `@Binds` declaration _may_ depend on a `@GraphPrivate` binding. In this case, the result type is visible to child graphs even though the source type is not. The child accesses the aliased type as a parent-resolved dependency rather than re-resolving the alias.

    ```kotlin
    @DependencyGraph(AppScope::class)
    interface AppGraph {
      @GraphPrivate @SingleIn(AppScope::class) @Provides fun provideString(): String = "hello"
      @Binds fun bindCharSequence(value: String): CharSequence

      fun createChild(): ChildGraph
    }

    @GraphExtension
    interface ChildGraph {
      val text: CharSequence // OK — CharSequence is visible via the public @Binds alias
      val string: String     // Error — String is @GraphPrivate in the parent
    }
    ```

### Creators

See [DependencyGraph](#inputs)'s section on creators.

### Aggregation

See [DependencyGraph](#scoping)'s section on aggregation.

### Contributed Graph Extensions

Contribute a graph extension to defer merging until Metro generates its parent graph.

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

In `:login`, annotate `LoggedInGraph` with `@GraphExtension(LoggedInScope::class)`. Use `@ContributesTo(AppScope::class)` on its factory to contribute the extension to the future parent graph.

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

Metro makes `AppGraph` implement `LoggedInGraph.Factory`. Its generated `createLoggedInGraph()` returns a generated final `LoggedInGraphImpl`. That implementation includes the matching contributions visible to `:app`, including `UserRepository` from `:user-data`.

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

A `@BindingContainer` class, object, or interface groups `@Provides` and `@Binds` declarations. It is a reusable partial graph, similar to a Dagger `@Module`. Use one when placing bindings in a graph supertype is unwieldy.

Metro reads only `@Binds` and `@Provides` declarations from binding containers. For other `@Includes` inputs, Metro reads public accessors as bindings.

!!! tip
    Include a binding container in a graph to reuse a group of declarations.

### Including via `@Includes` Parameters

Pass a binding container instance through an `@Includes` factory parameter:

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

This lets the caller construct and configure the container.

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

The graph includes all binding containers reached transitively through `includes`.

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
- `@Provides` declarations in a container’s companion object are included automatically.
- A class with `@Binds` or `@Provides` declarations does not need `@BindingContainer` for those declarations to be processed. The annotation lets a graph reference the class as a container and use its `includes` parameter.
- Binding containers may also be [contributed](aggregation.md#contributing-binding-containers).
- See [#172](https://github.com/ZacSweers/metro/issues/172) for more details.

## Dynamic Graphs

Dynamic graphs replace bindings when you create a graph. Pass one or more binding container instances to `createDynamicGraph()` or `createDynamicGraphFactory()`.

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

Metro generates a private graph implementation in the enclosing class or file for that combination of containers and target graph type.

**Constraints**

- All containers must be instances (or objects) of _binding containers_.
- It's an error to pass no containers.
- Each container class must be non-local and have a canonical name.
- This overload may be called in a member function body, top-level function body, or property initializer.
- The target graph type must have `@DependencyGraph` and be valid without the replacements.

??? note "Implementation Notes"

    - The bulk of this implementation is in `IrDynamicGraphGenerator`.
    - The generated implementation is a private nested static class of the enclosing class or file.
    - `BindingGraphGenerator` tracks dynamic bindings and prefers them while constructing a new graph implementation. It does not modify an existing graph instance.

## General Implementation Notes

Metro generates dependency graphs using patterns similar to Dagger components.

* Graph validation uses [Tarjan’s algorithm](https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm) and a [topological sort](https://en.wikipedia.org/wiki/Topological_sorting).
    * The traversal runs in O(V+E) time.
    * The ordered bindings determine provider field initialization order.
    * A binding that depends on a later binding in the order needs a `DelegateFactory`.
* Dependencies are traversed from public accessors and `inject()` functions.
* Metro generates a factory when it compiles each provider:
    * Factories can be reused to avoid duplicate code.
    * Factories copy default expressions to support optional dependencies. They are nested in the source class so those expressions can reference private members.
* A generated graph implementation holds aggregated bindings and manages scoping.
* Scoped bindings are stored in provider fields backed by `DoubleCheck`.
* Reused unscoped providers are stored in fields.
* `@Provides` factory parameters are stored in a field backed by `InstanceFactory`.
* Multibindings create new collection instances every time.
* Multibinding providers are not accessible as standalone bindings.
* Graph extensions use metadata and generated accessors:
    * Custom `MetroMetadata` is generated and serialized into Kotlin's `Metadata` annotations.
    * Extendable parent graphs record provider and instance fields, binding callable IDs, parent graphs, and provider callable IDs.
    * Extendable parent graphs generate `_metroAccessor`-suffixed `internal` functions that expose instance fields and provider fields.
    * Child graphs read the metadata, resolve the callable symbols, and add them to their binding graphs.

### Optimizations

Metro applies several optimizations to generated graph code to reduce class size and improve runtime performance.

#### Unused binding elimination

By default, Metro removes bindings that are not reachable from any accessor or member injector function. This reduces the size of generated code and avoids unnecessary initialization work.

```kotlin
@DependencyGraph
interface AppGraph {
  val httpClient: HttpClient  // This is a root - it's reachable

  fun inject(memberInjectedClass: MemberInjectedClass) // This is also a root

  @Provides fun provideHttpClient(): HttpClient = HttpClient()

  // This binding is NOT reachable from any accessor, so it will be eliminated
  @Provides fun provideUnusedService(): UnusedService = UnusedService()
}
```

This can be disabled via `metro.shrinkUnusedBindings=false` if you need all declared bindings to be generated (e.g., for debugging).

#### Property collection

Not all bindings need dedicated provider fields. Metro analyzes the binding graph to determine which bindings require properties and what kind:

**Scoping**: Scoped bindings (e.g. `@SingleIn`) always get a field backed by `DoubleCheck` to ensure lazy, thread-safe instantiation that is a singleton within the enclosing graph.

**Reference counting**: Unscoped bindings used only once are inlined at their call site. Bindings used two or more times get a reusable provider field to avoid duplicate instantiation logic.

**Alias resolution**: When counting references, Metro follows `@Binds` alias chains to the final implementation. If `Api` binds to `ApiImpl`, references to `Api` count toward `ApiImpl`'s ref count.

**Multibinding source accessors**: Bindings contributed to multibindings (`@IntoSet`, `@IntoMap`) that have dependencies are extracted into _getter_ properties. This avoids duplicating dependency resolution code at each multibinding collection site and avoids risking `MethodTooLargeException` issues at compile-time.

#### Chunking

JVM methods have a size limit. For graphs with many bindings, the constructor initialization code can exceed this limit. To avoid this, Metro splits initialization into multiple private `init` functions if necessary, each handling a subset of bindings.

```kotlin
// Generated structure for a large graph:
class AppGraph$Impl : AppGraph {
  private val provider1: Provider<...>
  private val provider2: Provider<...>
  // ... hundreds more ...

  init {
    init1()
    init2()
    // ...
  }

  private fun init1() {
    provider1 = ...
    provider2 = ...
    // ... up to statementsPerInitFun statements ...
  }

  private fun init2() {
    // ... next batch ...
  }
}
```

!!! Configuration

    | Gradle Extension Property | Gradle/System Property | Compiler Option | Description |
    | ------------------ | --------------- | --------------- | ----------- |
    | statementsPerInitFun | `metro.statementsPerInitFun` | `statements-per-init-fun` | Max statements per init function (default: `25`) |

#### Sharding

For very large graphs, even chunking may not be enough — the class itself can exceed JVM's class file size limits. Sharding moves initialization logic into inner shard classes, distributing the code across multiple class files while keeping provider fields on the main graph class.

```kotlin
// Generated structure with sharding:
class AppGraph$Impl : AppGraph {
  // Provider fields stay on the main class
  private val repoProvider: Provider<Repository>
  private val apiProvider: Provider<ApiService>
  // ... many more ...

  // Shards hold initialization logic
  private inner class Shard1 {
    fun initialize() {
      repoProvider = provider { Repository.MetroFactory.create() }
      apiProvider = provider { ApiService.MetroFactory.create(graph.repoProvider) }
    }
  }

  private inner class Shard2 {
    fun initialize() {
      // ... next batch of initializations ...
    }
  }

  init {
    Shard1().initialize()
    Shard2().initialize()
  }
}
```

Sharding respects required binding initialization order and keeps strongly connected components (valid cycles broken by `Provider`/`Lazy`) together in the same shard.

!!! Configuration

    | Gradle Extension Property | Gradle/System Property | Compiler Option | Description |
    | ------------------ | --------------- | --------------- | ----------- |
    | enableGraphSharding | `metro.enableGraphSharding` | `enable-graph-sharding` | Enable/disable sharding (default: `true`) |
    | keysPerGraphShard | `metro.keysPerGraphShard` | `keys-per-graph-shard` | Max bindings per shard (default: `2000`) |

#### Switching Providers

Switching providers defer binding class initialization until the binding is requested. This can reduce graph initialization time.

This is analogous to Dagger's `fastInit` option.

I’d _only_ enable switching providers if you’ve benchmarked them and the difference is worth it. Each switching provider holds a reference to the graph for as long as the provider lives.

Switching providers also support [suspend providers](coroutines.md).

```kotlin
// The graph
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hello, world!"
  @Provides @SingleIn(AppScope::class) fun provideInt(string: String): Int = string.length

  val stringLength: Int
}

// Generated structure for a graph with switching providers
class AppGraph$Impl : AppGraph {
  private val provider1: Provider<String> DoubleCheck.provider(SwitchingProvider<String>(this, 0))
  private val provider2: Provider<Int> DoubleCheck.provider(SwitchingProvider<Int>(this, 1))

  @Provides

  private class SwitchingProvider<T>(private val graph: Impl, private val id: Int) : Provider<T> {
    override operator fun invoke(): T {
      when (id) {
        0 -> StringFactory.newInstance(...) as T
        1 -> IntFactory.newInstance(graph.provider1()) as T
      }
    }
  }
}
```

!!! Configuration

    | Gradle Extension Property | Gradle/System Property | Compiler Option | Description |
    | ------------------ | --------------- | --------------- | ----------- |
    | enableSwitchingProviders | `metro.enableSwitchingProviders` | `enable-switching-providers` | Enable/disable switching providers (default: `false`) |
