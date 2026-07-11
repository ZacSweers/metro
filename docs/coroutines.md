# Coroutines Support

Metro supports `suspend` provider functions and `suspend` graph accessors. This lets you model dependencies whose creation requires suspending work, such as opening a database, reading a config file, or performing a network handshake.

!!! warning "Experimental"

    Suspend provider support is experimental and disabled by default. Enable it in the Metro Gradle configuration:

    ```kotlin
    metro {
      enableSuspendProviders.set(true)
    }
    ```

    The Gradle plugin then adds `dev.zacsweers.metro:runtime-coroutines` automatically. If `automaticallyAddRuntimeDependencies` is disabled, add that artifact yourself.

    `SuspendProvider`, `SuspendLazy`, and their helper APIs are also annotated with `@ExperimentalMetroCoroutinesApi`. Using those APIs directly requires a Kotlin opt-in, either at the use site or with `-opt-in=dev.zacsweers.metro.ExperimentalMetroCoroutinesApi`.

## Declaring suspend bindings

Annotate a `suspend` function with `@Provides` like any other provider.

```kotlin
@DependencyGraph
interface AppGraph {
  suspend fun database(): Database

  @Provides
  suspend fun provideDatabase(config: DbConfig): Database = openDatabase(config)
}
```

Because creating a `Database` suspends, the graph accessor for it must also be a `suspend` function. Metro validates this at compile time.

`@Binds` and `@Multibinds` declarations cannot be `suspend`. They have no body to suspend in.

## How suspend-ness spreads through the graph

Suspend-ness is contagious. A binding is suspend if either:

1. Its provider is a `suspend` function, or
2. It depends on a suspend binding directly, without a deferring wrapper.

This applies per graph and requires no annotations on consuming classes. In this example, `Repository` never mentions suspension, but it depends on `Database`, which is suspend. That makes `Repository` suspend too, so its accessor must be a `suspend` function:

```kotlin
@Inject
class Repository(val database: Database)

@DependencyGraph
interface AppGraph {
  suspend fun repository(): Repository

  @Provides
  suspend fun provideDatabase(): Database = openDatabase()
}
```

The chain can be arbitrarily long. Any binding that transitively reaches a suspend binding through unwrapped dependencies becomes suspend itself. If you expose such a binding through a non-suspend accessor, Metro reports an error with a trace showing the full path to the suspend source.

The reverse direction is always fine. Suspend providers can depend on non-suspend bindings freely.

## Deferring with `suspend () -> T`

Injecting `suspend () -> T` instead of `T` defers the suspending work to whenever you invoke the function. This breaks the suspend chain: the consuming class is not itself suspend, because constructing it doesn't suspend. Only calling the function does.

```kotlin
@Inject
class Repository(val database: suspend () -> Database) {
  suspend fun load(id: String): Row = database().query(id)
}

@DependencyGraph
interface AppGraph {
  // Not a suspend accessor. Repository construction doesn't suspend.
  val repository: Repository

  @Provides
  suspend fun provideDatabase(): Database = openDatabase()
}
```

The same works for graph accessors:

```kotlin
@DependencyGraph
interface AppGraph {
  val database: suspend () -> Database

  @Provides
  suspend fun provideDatabase(): Database = openDatabase()
}
```

`suspend () -> T` is the suspend analog of the [`() -> T` function provider](metro-intrinsics.md). Prefer the function type at injection sites. Metro uses `SuspendProvider<T>` internally and adapts it to the function type. The runtime helper APIs below operate on `SuspendProvider<T>` directly. Like function providers generally, this requires the `enableFunctionProviders` option, which is on by default.

Each invocation resolves the binding again, or returns the cached instance if the binding is scoped.

## Deferring and memoizing with `SuspendLazy<T>`

`SuspendLazy<T>` is the suspend analog of Kotlin's `Lazy<T>`. Injecting it defers the work like `suspend () -> T` does, and additionally memoizes the result per injected instance. Since a suspending computation can't back a plain `val`, it exposes a `suspend fun value()` instead of a property.

```kotlin
@Inject
class Repository(val database: SuspendLazy<Database>) {
  suspend fun load(id: String): Row = database.value().query(id)
}

@DependencyGraph
interface AppGraph {
  // Not a suspend accessor. SuspendLazy defers, so this is legal.
  val database: SuspendLazy<Database>

  @Provides
  suspend fun provideDatabase(): Database = openDatabase()
}
```

Like `Lazy<T>`, the memoization is per wrapper instance. Two injection sites each compute their own value for an unscoped binding. For a scoped binding, all `SuspendLazy` wrappers share the graph's single cached instance, so there is no double computation.

The memoization is coroutine-safe with the same semantics as a regular scoped binding's single instance: one caller computes, concurrent callers share the result, failures are retried, and cancellation doesn't poison the cache. The `runtime-coroutines` artifact added when suspend providers are enabled supplies this implementation.

It also works over non-suspend bindings if you want a uniform suspend API.

### What you can't do

`Provider<T>` and `Lazy<T>` cannot wrap a suspend binding. Their accessors are not suspend functions, so they have no way to await the work. Metro reports an error and suggests `suspend () -> T` or `SuspendLazy<T>` instead.

Nested suspend wrapper types are unsupported. `suspend () -> T` and `SuspendLazy<T>` must wrap the binding type directly. Wrapping them in `Provider` or `Lazy`, or in each other, is a compile-time error.

## Scoping

!!! note "Scope here means Metro scope"

    Throughout this page, scope refers to Metro's binding scopes (`@SingleIn`, `@DependencyGraph(scope = ...)`), not a `kotlinx.coroutines` `CoroutineScope`. Metro graphs do not own a `CoroutineScope`. Suspend providers run entirely within the calling coroutine.

Scoped suspend bindings work like any other scoped binding. The value is computed once and cached:

```kotlin
@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  suspend fun database(): Database

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): Database = openDatabase()
}
```

The cache is coroutine-safe:

- Only one caller computes the value. Concurrent callers wait for it and share the result.
- A failed initialization is not cached. The next caller retries.
- If the computing coroutine is cancelled mid-initialization, the cache is untouched and the next caller recomputes.
- A binding that resolves itself during its own initialization fails with a circular dependency error instead of deadlocking.

The cache is single-flight on every platform: one caller runs the initializer, concurrent callers suspend and share its result. On JVM and Native this synchronizes with a coroutine mutex. JS and Wasm are single-threaded, so the lock is a plain waiter queue with no locking overhead.

Scoped suspend bindings require `dev.zacsweers.metro:runtime-coroutines` on the compile and runtime classpath. The Gradle plugin adds it when `enableSuspendProviders` is true. On JVM and Native it depends on `kotlinx-coroutines-core` for the mutex. The JS and Wasm variants have no kotlinx-coroutines dependency. If automatic runtime dependencies are disabled and the artifact is missing, Metro reports a compile-time error naming it.

## Dispatchers

Metro never switches dispatchers. A suspend provider runs in the calling coroutine's context, on whatever dispatcher that caller happens to use.

For a scoped binding this matters more than it first appears. The initializer runs on the context of whichever caller reaches it first, and every other caller shares that result. Which dispatcher actually builds the value is an accident of call order.

If the work needs a particular dispatcher, switch to it explicitly inside the provider:

```kotlin
@Provides
@SingleIn(AppScope::class)
suspend fun provideDatabase(): Database =
  withContext(Dispatchers.IO) {
    openDatabase()
  }
```

This makes the provider correct regardless of where it is called from. Treat any suspend provider that does blocking I/O or thread-affine work without an explicit `withContext` as a bug waiting for the wrong first caller.

## Multibindings

Maps of deferred suspend values are supported:

```kotlin
@DependencyGraph
interface AppGraph {
  val handlers: Map<String, suspend () -> Handler>

  @Provides @IntoMap @StringKey("login")
  suspend fun provideLoginHandler(): Handler = createLoginHandler()

  @Provides @IntoMap @StringKey("logout")
  fun provideLogoutHandler(): Handler = LogoutHandler()
}
```

Suspend and non-suspend contributions can be mixed. Each value resolves when you invoke it.

Scalar multibindings over suspend bindings are errors:

- `Set<T>` multibindings cannot contain suspend contributions. Provider-valued set forms such as `Set<suspend () -> T>` are unsupported, matching `Set<Provider<T>>`.
- `Map<K, V>` over suspend values must be consumed as `Map<K, suspend () -> V>` instead.

## Assisted injection

If an `@AssistedInject` class consumes suspend bindings, its `@AssistedFactory` function must be declared `suspend`:

```kotlin
class AccountCreator
@AssistedInject
constructor(@Assisted val region: String, val database: Database) {
  @AssistedFactory
  fun interface Factory {
    suspend fun create(region: String): AccountCreator
  }
}

@DependencyGraph
interface AppGraph {
  val accountCreatorFactory: AccountCreator.Factory

  @Provides
  suspend fun provideDatabase(): Database = openDatabase()
}
```

The factory itself is not suspend to hold or create. Suspension happens when you call `create(...)`. Metro reports an error if the factory function is not suspend but the target needs it.

## Member injection

Member injection does not support suspend bindings. Injector functions and `MembersInjector` are not suspend and cannot await anything. Metro reports an error. Change the member's type to `suspend () -> T` (or `SuspendLazy<T>`) to defer the resolution instead:

```kotlin
class ProfileActivity {
  @Inject lateinit var database: suspend () -> Database
}
```

## Runtime helpers

The `runtime` artifact includes small utilities for working with suspend providers:

```kotlin
// Wrap a lambda
val provider: SuspendProvider<String> = suspendProvider { fetchToken() }

// Wrap an existing value
val fixed: SuspendProvider<String> = suspendProviderOf("token")

// Transform lazily
val mapped: SuspendProvider<Int> = provider.map { it.length }
val zipped: SuspendProvider<Pair<String, Int>> = provider.zip(mapped) { a, b -> a to b }
```

The `runtime-coroutines` artifact adds memoization:

```kotlin
// Compute once, cache, and share across concurrent callers
val memoized: SuspendProvider<String> = provider.memoize()

// Same, exposed as SuspendLazy
val lazy: SuspendLazy<String> = provider.memoizeAsLazy()
println(lazy.isInitialized()) // false
val value = lazy.value() // suspends and computes
```

You can also create a `SuspendLazy<T>` directly, outside of injection:

```kotlin
val config: SuspendLazy<Config> = suspendLazy { loadConfig() }
val fixedConfig: SuspendLazy<Config> = suspendLazyOf(Config())
```

`suspendLazy` accepts the same `LazyThreadSafetyMode` values as `lazy`.

## Multiplatform

All of the above is common code and works on JVM, Android, JS, Native, and Wasm targets.
