# Metro Intrinsics

Like Dagger, Metro supports injection of bindings wrapped in intrinsic types. Namely — _provider_ functions (i.e. `() -> T`) and `Lazy`. These are useful for deferring creation/initialization of dependencies. They only need to be requested at the injection site; Metro’s code gen will generate all the necessary stitching to satisfy that request.

## Functions (`() -> T`)

Metro supports using Kotlin's `() -> T` function type as a provider. This is the **preferred form** for expressing providers: it requires no Metro-specific import, plays naturally with Kotlin idioms, and is just as efficient as the legacy `Provider<T>` type from JSR-330-esque patterns. Each invocation returns a new instance (or the cached instance if the underlying binding is scoped).

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

Provider functions can be freely mixed with `Lazy<T>`, and also work with multibindings (e.g., `() -> Set<T>`, `Map<K, () -> V>`) and nested intrinsics like `() -> Lazy<T>`.

!!! warning "Caveat"
    Enabling this feature effectively prevents using bare function types as regular bindings in your graph. If you rely on injecting `() -> T` as a _value_ rather than a provider, you may need to migrate those bindings to a more strongly typed wrapper.

!!! note "Kotlin/JS"
    On Kotlin/JS, the legacy `Provider<T>` type does not implement `() -> T` due to JS runtime limitations. Metro handles this transparently by wrapping/unwrapping at the call site, similar to other provider interop scenarios.

!!! tip "Opting out"
    Function provider support is enabled by default and can be disabled via the `enableFunctionProviders` compiler option. Disabling it reverts to using `Provider<T>` exclusively.

## `Lazy`

`Lazy` is Kotlin’s standard library `Lazy`. It lazily computes a value the first time it’s evaluated and is thread-safe.

```kotlin
@Inject
class HttpClient(val cacheProvider: Lazy<Cache>) {
  fun createCache() {
    // The value is computed once and cached after
    val cache = cacheProvider.value
  }
}
```

Note that `Lazy` is different from *scoping* in that it is confined to the scope of the *injected type*, rather than the component instance itself. There is functionally no difference between injecting a provider or `Lazy` of a *scoped* binding. A `Lazy` of a scoped binding can still be useful to defer initialization. The underlying implementation in Metro’s `DoubleCheck` prevents double memoization in this case.

!!! note "Why doesn’t `Provider` just use a property like `Lazy`?"
    A property is appropriate for `Lazy` because it fits the definition of being a *computed* value that is idempotent for repeat calls. Metro opts to make its `Provider` use an `invoke()` function because it does not abide by that contract.

## `Provider<T>` (legacy form)

Before the function-syntax form was introduced, Metro used an explicit `Provider<T>` interface (mirroring javax/jakarta’s `Provider`). This form is still fully supported and continues to work — it’s simply no longer the recommended style for new code.

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

`Provider<T>` and `() -> T` are fully interchangeable within a graph — they resolve to the same underlying binding — so you can migrate incrementally.

## Providers of Lazy

Metro supports combining functions and `Lazy` to inject `() -> Lazy<T>` (or `Provider<Lazy<T>>`). On unscoped bindings this means the provider will return a new deferrable computable value (i.e. a new `Lazy`). Meanwhile `Lazy<() -> T>` / `Lazy<Provider<T>>` is meaningless and not supported.
