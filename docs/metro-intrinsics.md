# Metro Intrinsics

Like Dagger, Metro supports injection of bindings wrapped in intrinsic types. Namely \- `Provider` and `Lazy`. These are useful for deferring creation/initialization of dependencies. These only need to be requested at the injection site, Metro’s code gen will generate all the necessary stitching to fulfill that request.

## `Provider`

`Provider` is like Dagger’s `Provider` — it is a simple interface who’s `invoke()` call returns a new instance every time. If the underlying binding is scoped, then the same (scoped) instance is returned every time `invoke()` is called.

```kotlin
@Inject
class HttpClient(val cacheProvider: Provider<Cache>) {
  fun createCache() {
    val cache = cacheProvider()
  }
}
```

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

Note that `Lazy` is different from *scoping* in that it is confined to the scope of the *injected type*, rather than the component instance itself. There is functionally no difference between injecting a `Provider` or `Lazy` of a *scoped* binding. A `Lazy` of a scoped binding can still be useful to defer initialization. The underlying implementation in Metro’s `DoubleCheck` prevents double memoization in this case.

!!! note "Why doesn’t `Provider` just use a property like `Lazy`?"
    A property is appropriate for `Lazy` because it fits the definition of being a *computed* value that is idempotent for repeat calls. Metro opts to make its `Provider` use an `invoke()` function because it does not abide by that contract.

## Providers of Lazy

Metro supports combining `Provider` and `Lazy` to inject `Provider<Lazy<T>>`. On unscoped bindings this means the provider will return a new deferrable computable value (i.e. a new Lazy). Meanwhile `Lazy<Provider<T>>` is meaningless and not supported.
