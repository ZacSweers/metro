# Aggregation (aka 'Anvil')

Metro supports Anvil-style aggregation in graphs via `@ContributesTo` and `@ContributesBinding` annotations. As aggregation is a first-class citizen of Metro’s API, there is no `@MergeComponent` annotation like in Anvil. Instead, `@DependencyGraph` defines which contribution scope it supports directly.

```kotlin
@DependencyGraph(scope = AppScope::class)
interface AppGraph
```

When a graph declares a `scope`, all contributions to that scope are aggregated into the final graph implementation in code gen.

If a graph supports multiple scopes, use `additionalScopes`.

```kotlin
@DependencyGraph(
  AppScope::class,
  additionalScopes = [LoggedOutScope::class]
)
interface AppGraph
```

Similar to [kotlin-inject-anvil](https://github.com/amzn/kotlin-inject-anvil), `@DependencyGraph` supports excluding contributions by class. This is useful for cases like tests, where you may want to contribute a test/fake implementation that supersedes the “real” graph.

```kotlin
@DependencyGraph(
  scope = AppScope::class,
  excludes = [RealNetworkProviders::class]
)
interface TestAppGraph

@ContributesTo(AppScope::class)
interface TestNetworkProviders {
  @Provides fun provideHttpClient(): TestHttpClient
}
```

## @ContributesTo

This annotation is used to contribute graph interfaces to the target scope to be merged in at graph-processing time to the final merged graph class as another supertype.

```kotlin
@ContributesTo(AppScope::class)
interface NetworkProviders {
  @Provides fun provideHttpClient(): HttpClient
}
```

This annotation is *repeatable* and can be used to contribute to multiple scopes.

```kotlin
@ContributesTo(AppScope::class)
@ContributesTo(LoggedInScope::class)
interface NetworkProviders {
  @Provides fun provideHttpClient(): HttpClient
}
```

Similar to [kotlin-inject-anvil](https://github.com/amzn/kotlin-inject-anvil), `@ContributesBinding` (as well as the other `@Contributes*` annotations) supports replacing other contributions by class. This is useful for cases like tests, where you may want to contribute a test/fake implementation that supersedes the “real” graph.

```kotlin
@DependencyGraph(AppScope::class)
interface TestAppGraph

@ContributesTo(AppScope::class, replaces = [RealNetworkProviders::class])
interface TestNetworkProviders {
  @Provides fun provideHttpClient(): TestHttpClient
}
```

## @ContributesBinding

This annotation is used to contribute injected classes to a target scope as a given bound type.

The below example will contribute the `CacheImpl` class as a `Cache` type to `AppScope`.

```kotlin
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

For simple cases where there is a single supertype, that type is implicitly used as the bound type. If your bound type is qualified, for the implicit case you can put the qualifier on the class.

```kotlin
@Named("cache")
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

For classes with multiple supertypes or advanced cases where you want to bind an ancestor type, you can explicitly define this via `binding` parameter.

```kotlin
@Named("cache")
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<Cache>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

!!! tip
    Whoa, is that a function call in an annotation argument? Nope! `binding` is just a decapitalized class in this case, intentionally designed for readability. It's an adjective in this context and the functional syntax better conveys that.

Note that the bound type is defined as the type argument to `@ContributesBinding`. This allows for the bound type to be generic and is validated in FIR.

Qualifier annotations can also be specified on the `binding` type parameter and will take precedence over any qualifiers on the class itself.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<@Named("cache") Cache>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

This annotation is *repeatable* and can be used to contribute to multiple scopes.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<Cache>()
)
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<AnotherType>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

!!! tip
    Contributions may be `object` classes. In this event, Metro will automatically provide the object instance in its binding.

### Implicitly-injected `@ContributesBinding` types

Up to version `0.7.0`, Metro has always required you to use `@Inject` on most `@Contributes*` annotated types. However, this can feel a bit repetitive and tedious. Starting in `0.7.0`, there is a new `contributesAsInject` Gradle DSL option that can be enabled that will treat all `@Contributes*` annotated types as `@Inject` by default. You can still use `@Inject` on classes to be explicit, and if you have multiple constructors you must still use `@Inject` on the constructor you want to be used.

_The only exception to this is `@ContributesTo`, which isn't applicable to injected types._

This is disabled by default to start but will likely become the default in a future release.

```kotlin
@ContributesBinding(AppScope::class)
// @Inject // <-- now implicit!
class TacoImpl(...) : Taco
```

## @ContributesIntoSet/@ContributesIntoMap

To contribute into a multibinding, use the `@ContributesIntoSet` or `@ContributesIntoMap` annotations as needed.

```kotlin
@ContributesIntoSet(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

Same rules around qualifiers and `binding()` apply in this scenario

To contribute into a Map multibinding, the map key annotation must be specified on the class or `binding` type argument.

```kotlin
// Will be contributed into a Map multibinding with @StringKey("Networking")
@ContributesIntoMap(AppScope::class)
@StringKey("Networking")
@Inject
class CacheImpl(...) : Cache

// Or if using binding
@ContributesIntoMap(
  scope = AppScope::class,
  binding = binding<@StringKey("Networking") Cache>()
)
@Inject
class CacheImpl(...) : Cache
```

This annotation is also repeatable and can be used to contribute to multiple scopes, multiple bound types, and multiple map keys.

You can use `@IntoMap`/`@IntoSet` to provide into the same container:

```kotlin
// Method 1: applying @ContributesIntoMap to bind directly from the implementation class
@ContributesIntoMap(AppScope::class)
@StringKey("remote")
@Inject
class RemoteCache(...) : Cache

// Method 2: Declare the class, then provide @IntoMap binding separately via a BindingContainer
class LocalCache(...) : Cache

@BindingContainer
@ContributesTo(AppScope::class)
object CacheBindingContainer {
  @Provides
  @IntoMap
  @StringKey("local")
  fun cache(): Cache = LocalCache(...)
}

// Accessing the resultant map, containing both implementations:
@Inject
class CompositeCache(private val caches: Map<String, Cache>) {
  val local: Cache = caches["local"]
  val remote: Cache = caches["remote"]
}

// Alternatively, specify Provider in the map type to lazily-initialize the implementations
@Inject
class CompositeCacheAlternate(private val caches: Map<String, Provider<Cache>>) {
  val local: Cache = caches["local"]()
  val remote: Provider<Cache> = caches["remote"]
  
  fun someTimeLater() {
    remote().doSomethingWithCache()
  }
}
```

Like `@ContributesBinding`, enabling the `contributesAsInject` Gradle DSL option will treat all `@ContributesIntoSet`/`@ContributesIntoMap`-annotated types as `@Inject` by default.

## Contributing Binding Containers

Binding containers (see [Binding Containers](dependency-graphs.md#binding-containers)) can also be contributed to scopes via `@ContributesTo`:

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

### Replacing Contributed Binding Containers

Similar to other contribution types, binding containers can replace other contributed binding containers:

```kotlin
// In production
@ContributesTo(AppScope::class)
@BindingContainer
object NetworkBindings {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}

// In tests
@ContributesTo(AppScope::class, replaces = [NetworkBindings::class])
@BindingContainer
object FakeNetworkBindings {
  @Provides fun provideFakeHttpClient(): HttpClient = FakeHttpClient()
}
```

### Replacing Contributed Bindings

Binding containers can replace other contributed bindings, too:

```kotlin
// In production
@ContributesBinding(AppScope::class)
@Inject
class HttpClientImpl : HttpClient

// In tests
@ContributesTo(AppScope::class, replaces = [HttpClientImpl::class])
@BindingContainer
object FakeNetworkBindings {
  @Provides fun provideFakeHttpClient(): HttpClient = FakeHttpClient()
}
```

### Excluding Contributed Binding Containers

Graphs can exclude specific contributed binding containers:

```kotlin
@ContributesTo(AppScope::class)
@BindingContainer
object NetworkBindings {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph(AppScope::class, excludes = [NetworkBindings::class])
interface TestAppGraph {
  // NetworkBindings will not be included
}
```

## Implementation notes

This leans on Kotlin’s ability to put generic type parameters on annotations. That in turn allows for both generic bound types and to contribute map bindings to multiple map keys.

Because it’s a first-party feature, there’s no need for intermediary “merged” components like kotlin-inject-anvil and anvil-ksp do.

Generated contributing interfaces are generated to the `metro.hints` package and located during graph supertype generation in FIR downstream. Any contributed bindings are implemented as `@Binds` (± IntoSet/IntoMap/etc) annotated properties.
