# Aggregation (aka 'Anvil')

Metro supports Anvil-style aggregation through `@ContributesTo` and `@ContributesBinding`. Aggregation is built into `@DependencyGraph`, which declares its contribution scope directly. Metro does not need Anvil’s separate `@MergeComponent` annotation.

```kotlin
@DependencyGraph(scope = AppScope::class)
interface AppGraph
```

Metro merges all visible, eligible contributions for the declared `scope` into the generated graph.

If a graph supports multiple scopes, use `additionalScopes`.

```kotlin
@DependencyGraph(
  AppScope::class,
  additionalScopes = [LoggedOutScope::class]
)
interface AppGraph
```

Like [kotlin-inject-anvil](https://github.com/amzn/kotlin-inject-anvil), Metro can exclude contributions by class. Use `DependencyGraph.excludes` to omit a production contribution when a test supplies a fake.

```kotlin
@DependencyGraph(
  scope = AppScope::class,
  excludes = [RealNetworkProviders::class]
)
interface TestAppGraph

@ContributesTo(AppScope::class)
interface TestNetworkProviders {
  @Provides fun provideHttpClient(): HttpClient = TestHttpClient()
}
```

## Checking for hidden contributions

A graph can miss contributions when an upstream project's `implementation` dependencies are hidden from its compile classpath. The Metro Gradle plugin has an opt-in check for this. Run it on the project that owns the graph, including on CI:

```shell
./gradlew :app:checkMainMetroHiddenDependencies
```

Task names include the target and compilation when present. For example, KMP JVM uses `checkJvmMainMetroHiddenDependencies` and an Android debug compilation uses `checkDebugMetroHiddenDependencies`. Each check resolves just that compilation's dependencies. The graph project itself can have compilation errors and still run the check. Local dependencies may need to compile before their hints can be inspected.

To run these checks automatically, enable `checkOnCompile` in the graph project's `metro.hiddenDependencies` DSL:

```kotlin
@OptIn(ExperimentalMetroGradleApi::class)
metro {
  hiddenDependencies {
    checkOnCompile.set(true)
  }
}
```

Each supported Kotlin compilation task runs its associated check as a Gradle finalizer, including when compilation fails or is up to date. This option is experimental and disabled by default. It may be enabled by default in a future release. You can also enable it with the `metro.hiddenDependencies.checkOnCompile=true` Gradle property.

The report lists the resolved dependency, a path through the runtime dependency graph, and matching hint entries. Add the reported dependency directly in the graph project, or expose it with `api` upstream if it belongs in that project's public API.

By default any Metro hints count. You can limit checks with `hiddenDependencies.scopes` using Kotlin ClassId strings. Slashes separate package segments and dots separate nested classes:

```kotlin
@OptIn(ExperimentalMetroGradleApi::class)
metro {
  hiddenDependencies {
    scopes.addAll("dev/zacsweers/metro/AppScope", "com/example/Scopes.User")
  }
}
```

!!! tip "Interop"
    With `includeHilt()`, the check also reads Hilt's `hilt_aggregated_deps` markers.
    
    Hilt markers name the components they install into. A standard Hilt component matches its canonical scope, so `javax/inject/Singleton` selects `SingletonComponent` markers. To select a custom `@DefineComponent`, add the component's own ClassId.

Each check writes its report to `build/reports/metro/<target>/<compilation>/hidden-dependencies.txt` and fails if it finds hidden contributions. An empty file means the check passed. A blank target is omitted. Successful checks can be reused from Gradle's build cache.

The check currently covers JVM and Android artifacts, including JVM targets in KMP projects. Other KMP platforms are outside its current scope. A finding means a dependency advertises contribution hints. The compiler remains responsible for scope and Kotlin visibility rules. Dependencies without hints can still be needed for types referenced by bindings.

## @ContributesTo

`@ContributesTo` adds an interface as a supertype of graphs with the target scope.

```kotlin
@ContributesTo(AppScope::class)
interface NetworkProviders {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}
```

This annotation is *repeatable* and can be used to contribute to multiple scopes.

```kotlin
@ContributesTo(AppScope::class)
@ContributesTo(LoggedInScope::class)
interface NetworkProviders {
  @Provides fun provideHttpClient(): HttpClient = HttpClient()
}
```

Use `replaces` on a `@Contributes*` annotation to replace another contribution by class, such as a production implementation in tests. This follows kotlin-inject-anvil’s replacement pattern.

```kotlin
@DependencyGraph(AppScope::class)
interface TestAppGraph

@ContributesTo(AppScope::class, replaces = [RealNetworkProviders::class])
interface TestNetworkProviders {
  @Provides fun provideHttpClient(): HttpClient = TestHttpClient()
}
```

## @ContributesBinding

`@ContributesBinding` binds an injected class to a type in the target scope.

This example contributes `CacheImpl` as `Cache` in `AppScope`:

```kotlin
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

If the class has one supertype, Metro infers it as the bound type. Put a qualifier on the class to qualify that inferred binding.

```kotlin
@Named("cache")
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

For multiple supertypes or an ancestor type, specify the `binding` argument:

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
    Wait, is that a function call in an annotation argument? Nope! `binding` is an annotation class. I gave it a lowercase name so `binding<T>()` reads naturally.

The bound type is a type argument of `binding`. It can be generic and is validated in FIR.

A qualifier on the `binding` type argument takes precedence over a qualifier on the class.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<@Named("cache") Cache>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

This annotation is *repeatable*, allowing the annotated type to contribute to multiple scopes, as multiple bound types, or both.

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
    An `object` contribution supplies the object instance as its binding.

### Contribution priority

`@ContributesBinding` supports a `priority` argument for choosing between contributions to the same qualified bound type without directly referencing another implementation. The highest-priority contribution is used:

```kotlin
@ContributesBinding(AppScope::class)
class DefaultCache : Cache

@ContributesBinding(AppScope::class, priority = 10)
class PreferredCache : Cache
```

In this example, `PreferredCache` supplies the `Cache` binding. Priorities default to `Int.MIN_VALUE`, so any explicitly specified higher value takes precedence. Contributions with equal priorities remain duplicate bindings and produce the usual error.

Priority only suppresses the individual conflicting binding. Other bindings or set/map contributions from the same implementation remain available. Explicit `excludes` and `replaces` are applied first and still exclude or replace whole contributing classes.

### Implicitly-injected `@ContributesBinding` types

Metro treats `@ContributesBinding`, `@ContributesIntoSet`, and `@ContributesIntoMap` types as injected by default.

```kotlin
@ContributesBinding(AppScope::class)
// @Inject // <-- now implicit!
class TacoImpl(...) : Taco
```

You can still add `@Inject` explicitly. If the class has multiple constructors, you must annotate exactly one constructor with `@Inject`. `@ContributesTo` does not imply injection. It contributes graph supertypes and binding containers.

This behavior can be disabled via the `contributesAsInject` Gradle DSL option if you prefer to require explicit `@Inject` annotations.

## @ContributesIntoSet/@ContributesIntoMap

To contribute into a multibinding, use the `@ContributesIntoSet` or `@ContributesIntoMap` annotations as needed.

```kotlin
@ContributesIntoSet(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

The same qualifier and `binding()` rules apply.

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

`@ContributesIntoMap` also supports `priority` when multiple contributions target the same qualified map and the same map-key value:

```kotlin
@ContributesIntoMap(AppScope::class)
@StringKey("remote")
class DefaultRemoteCache : Cache

@ContributesIntoMap(AppScope::class, priority = 10)
@StringKey("remote")
class PreferredRemoteCache : Cache

@ContributesIntoMap(AppScope::class)
@StringKey("local")
class LocalCache : Cache
```

Here the `"remote"` entry uses `PreferredRemoteCache`, while the separate `"local"` entry remains unaffected. Equal-priority contributions to the same map key still produce the usual duplicate-key error.

Set contributions are additive and do not support `priority`; use `replaces` or graph `excludes` to remove a contribution. `@ContributesTo` also does not support priority.

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

// Alternatively, use a function in the map value type to instantiate the implementations on-demand
@Inject
class CompositeCacheAlternate(private val caches: Map<String, () -> Cache>) {
  val local: Cache = caches["local"]()
  val remote: () -> Cache = caches["remote"]
  
  fun someTimeLater() {
    remote().doSomethingWithCache()
  }
}
```

`contributesAsInject` is enabled by default. When enabled, it treats `@ContributesIntoSet` and `@ContributesIntoMap` types as implicitly injected. Disable it to require explicit `@Inject` annotations.

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

## `@DefaultBinding`

Use `@DefaultBinding` on a supertype to set the default bound type for contributed subclasses. This avoids repeating generic `binding<T>()` declarations.

```kotlin
@DefaultBinding<BaseFactory<*>>
interface BaseFactory<T : BaseFactory<T>>

@ContributesIntoSet(AppScope::class) // now implicitly contributed as BaseFactory<*>
@Inject
class HomeFactory(...) : BaseFactory<HomeFactory>
```

## `generateContributionProviders`

Enable `generateContributionProviders` to generate top-level `@Provides` declarations with the injected class’s inputs and bound return type. This lets the implementation remain `internal`, keeps implementation details encapsulated, and can reduce downstream ABI changes during incremental compilation.

```kotlin
interface Base

@ContributesBinding(AppScope::class)
@Inject
internal class Impl : Base

// Works across modules!
@DependencyGraph(AppScope::class)
interface AppGraph {
  val base: Base
}
```

The tradeoff is that `Impl` is no longer available directly on the graph.

Contribution priorities are also honored for generated providers, including contributions from `internal` implementations in upstream modules.

Generic contributions are supported when [derived bindings](derived-bindings.md#generic-binding-contributions) are enabled.

## Implementation notes

Kotlin annotations can have generic type parameters. Metro uses them for generic bound types and multiple map-key contributions.

Metro merges contributions directly into graphs. kotlin-inject-anvil and anvil-ksp use intermediate merged components.

Metro generates contribution hints in `metro.hints` and discovers them during downstream FIR graph generation. Contributed bindings become `@Binds` properties with any required multibinding annotations.
