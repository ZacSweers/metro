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

Instance parameters and graph dependencies can be provided via a `@DependencyGraph.Factory` interface that returns the target graph.

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

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(messageGraph: MessageGraph): AppGraph
  }

  @DependencyGraph interface MessageGraph {
    val message: String

    @Provides fun provideMessage(): String = "Hello, world!"
  }
}
```

Like Dagger, non- `@Provides` instance dependencies can be any type. Metro will treat accessor candidates of these types as usable dependencies.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(messageProvider: MessageProvider): AppGraph
  }

  interface MessageProvider {
    val message: String
  }
}
```

Graph factories can be created with the `createGraphFactory()` intrinsic.

```kotlin
val messageGraph =
  createGraphFactory<AppGraph.Factory>()
    .create("Hello, world!")
```

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