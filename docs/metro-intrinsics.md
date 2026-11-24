# Metro Intrinsics

Request `() -> T` or `Lazy<T>` at an injection site to defer creating a dependency. Metro generates the wrapper.

## Functions (`() -> T`)

Use `() -> T` for providers. It requires no Metro import and has the same efficiency as `Provider<T>`. Each call creates an unscoped instance or returns the cached scoped instance.

```kotlin
@Inject
class HttpClient(val cacheProvider: () -> Cache) {
  fun createCache() {
    val cache = cacheProvider()
  }
}
```

This also works for graph accessors:

```kotlin
@DependencyGraph
interface AppGraph {
  val cacheProvider: () -> Cache
}
```

Providers can be combined with `Lazy<T>`, multibindings such as `() -> Set<T>` and `Map<K, () -> V>`, and nested wrappers such as `() -> Lazy<T>`.

!!! warning "Caveat"
    With function providers enabled, Metro interprets `() -> T` as a provider request. Use a named wrapper type for a callback that must be injected as a value.

!!! note "Kotlin/JS"
    On Kotlin/JS, the legacy `Provider<T>` type does not implement `() -> T` due to JS runtime limitations. Metro handles this transparently by wrapping/unwrapping at the call site, similar to other provider interop scenarios.

!!! tip "Opting out"
    Function provider support is enabled by default and can be disabled via the `enableFunctionProviders` compiler option. Disabling it reverts to using `Provider<T>` exclusively.

For suspend bindings, use `suspend () -> T` to defer initialization. See [Coroutines Support](coroutines.md).

## `Lazy`

`Lazy` is Kotlin’s standard library `Lazy`. It initializes a value on first access, caches it, and is thread-safe.

```kotlin
@Inject
class HttpClient(val cacheProvider: Lazy<Cache>) {
  fun createCache() {
    // The value is computed once and cached after
    val cache = cacheProvider.value
  }
}
```

Each injected `Lazy` caches its own value. A scoped binding is cached by the graph, so both providers and lazy wrappers return the graph’s scoped instance. `Lazy` can still defer its initialization. `DoubleCheck` avoids double memoization.

!!! note "Why doesn’t `Provider` just use a property like `Lazy`?"
    A `Lazy` property returns the same cached value on repeat access. A provider uses `invoke()` because each call may create a new value.

## `Provider<T>` (legacy form)

Metro also supports `Provider<T>`, an explicit interface similar to Javax and Jakarta providers. Prefer `() -> T` for new code.

```kotlin
@Inject
class HttpClient(val cacheProvider: Provider<Cache>) {
  fun createCache() {
    val cache = cacheProvider()
  }
}
```

When the function provider feature is enabled (the default), Metro emits a diagnostic whenever `Provider<T>` is used as a provider injection type. You can configure this severity via the `desugaredProviderSeverity` option:

- `NONE` — no diagnostic is emitted (useful if you rely on `Provider<T>` for interop with other DI frameworks)
- `WARN` — emit a warning (the default)
- `ERROR` — fail the compilation

`Provider<T>` and `() -> T` resolve to the same binding, so you can migrate incrementally.

## Nested providers and lazy values

Provider and lazy wrappers can be nested to any depth. For example, Metro supports `() -> Lazy<T>`, `Provider<Lazy<T>>`, `Lazy<() -> T>`, and `Lazy<Provider<T>>`. Each wrapper keeps its normal behavior: a provider call returns its immediate inner value, while a `Lazy` caches its immediate inner value. Suspending provider and lazy wrappers follow the same recursive model; see [Coroutines Support](coroutines.md).
