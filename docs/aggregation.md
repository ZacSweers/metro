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

!!! warning
    `@Contributes*.replaces` cannot replace classes in the _same compilation_ as the _graph that is merging them_.

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

For advanced cases where you want to bind an ancestor type, you can explicitly define this via `binding` parameter. For example, if your class has multiple supertypes, you must define an explicit bound type for it.

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

## `@ContributesGraphExtension`

Not yet implemented. Please share design feedback in [#165](https://github.com/ZacSweers/metro/issues/165)!

## Implementation notes

This leans on Kotlin’s ability to put generic type parameters on annotations. That in turn allows for both generic bound types and to contribute map bindings to multiple map keys.

Because it’s a first-party feature, there’s no need for intermediary “merged” components like kotlin-inject-anvil and anvil-ksp do.

Generated contributing interfaces are generated to the `metro.hints` package and located during graph supertype generation in FIR downstream. Any contributed bindings are implemented as `@Binds` (± IntoSet/IntoMap/etc) annotated properties.
