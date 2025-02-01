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

## @ContributesBinding

This annotation is used to contribute injected classes to a target scope as a given bound type.

The below example will contribute the `CacheImpl` class as a `Cache` type to `AppScope`.

```kotlin
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

For simple cases where there is a single typertype, that type is implicitly used as the bound type. If your bound type is qualified, for the implicit case you can put the qualifier on the class.

```kotlin
@Named("cache")
@ContributesBinding(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

For classes with multiple supertypes or advanced cases where you want to bind an ancestor type, you can explicitly define this via `boundType` parameter.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  boundType = BoundType<Cache>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

Note that the bound type is defined as the type argument to `@ContributesBinding`. This allows for the bound type to be generic and is validated in FIR.

Qualifier annotations can be specified on the `BoundType` type parameter and will take precedence over any qualifiers on the class itself.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  boundType = BoundType<@Named("cache") Cache>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

This annotation is *repeatable* and can be used to contribute to multiple scopes.

```kotlin
@ContributesBinding(
  scope = AppScope::class,
  boundType = BoundType<Cache>()
)
@ContributesBinding(
  scope = AppScope::class,
  boundType = BoundType<AnotherType>()
)
@Inject
class CacheImpl(...) : Cache, AnotherType
```

## @ContributesIntoSet/@ContributesIntoMap

To contribute into a multibinding, use the `@ContributesIntoSet` or `@ContributesIntoMap` annotations as needed.

```kotlin
@ContributesIntoSet(AppScope::class)
@Inject
class CacheImpl(...) : Cache
```

Same rules around qualifiers and `boundType()` apply in this scenario

To contribute into a Map multibinding, the map key annotation must be specified on the class or `BoundType` type argument.

```kotlin
// Will be contributed into a Map multibinding with ClassKey(
@ContributesIntoMap(AppScope::class)
@StringKey("Networking")
@Inject
class CacheImpl(...) : Cache

// Or if using BoundType
@ContributesIntoMap(
  scope = AppScope::class,
  boundType = BoundType<@StringKey("Networking") Cache>()
)
@Inject
class CacheImpl(...) : Cache
```

This annotation is also repeatable and can be used to contribute to multiple scopes, multiple bound types, and multiple map keys.

## `@GraphExtension`

Not yet implemented, but TL;DR will work the same as kotlin-inject-anvil’s `@ContributesSubcomponent`.

## Implementation notes

This leans on Kotlin’s ability to put generic type parameters on annotations. That in turn allows for both generic bound types and to contribute map bindings to multiple map keys.

Because it’s a first-party feature, there’s no need for intermediary “merged” components like kotlin-inject-anvil and anvil-ksp do.

Generated contributing interfaces are generated to the `metro.hints` package and located during graph supertype generation in FIR downstream. Any contributed bindings are implemented as `@Binds` (± IntoSet/IntoMap/etc) annotated properties.
