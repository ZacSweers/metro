# Coroutines Support

Metro's coroutine support currently includes `suspend` provider functions and graph accessors. Use
them for dependencies whose creation suspends, such as opening a database, reading configuration,
or performing a network handshake.

!!! warning "Experimental"

    Suspend provider support is experimental and disabled by default. The
    `metro.enableSuspendProviders` option is required for any suspend binding, suspend graph
    accessor, and injection request containing `suspend () -> T`, `SuspendProvider<T>`,
    `SuspendLazy<T>`, or `Map<K, suspend () -> V>`, including nested uses. Enable it in the Metro
    Gradle configuration:

    ```kotlin
    metro {
      enableSuspendProviders.set(true)
    }
    ```

    `SuspendProvider`, `SuspendLazy`, and their helper APIs are annotated with
    `@ExperimentalMetroCoroutinesApi`. Using them directly requires
    `@OptIn(ExperimentalMetroCoroutinesApi::class)` or
    `-opt-in=dev.zacsweers.metro.ExperimentalMetroCoroutinesApi`.

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

Because creating `Database` suspends, its graph accessor must also be `suspend`. Metro checks this
at compile time.

`@Binds` and `@Multibinds` only declare graph relationships, so Metro does not allow them to be
`suspend`.

## How suspension propagates

A binding requires initialization in a suspend context when either:

1. Its provider is a `suspend` function, or
2. It depends on a suspend binding directly, without a deferring wrapper.

This is computed separately for each graph and requires no annotation on consuming classes. Here,
constructing `Repository` requires `Database`, so the `Repository` accessor must be `suspend` too:

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

Suspension propagates through any number of unwrapped dependencies. A non-suspend accessor for one
of those bindings produces an error with the full dependency path to the suspend provider.

Suspend providers may depend on ordinary bindings without restriction.

## Deferring with `suspend () -> T`

Injecting `suspend () -> T` instead of `T` defers initialization until the function is called. The
consumer can then be constructed synchronously; only invoking the function suspends.

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

`suspend () -> T` is the suspend form of the [`() -> T` function provider](metro-intrinsics.md).
Prefer it at injection sites. It requires `enableFunctionProviders`, which is enabled by default.

Metro uses `SuspendProvider<T>` as the runtime type and converts it to the function type where
needed. The [runtime helpers](#runtime-helpers) operate on `SuspendProvider<T>` directly.

Each invocation initializes the binding again, or returns the cached instance if the binding is
scoped.

## Deferring and memoizing with `SuspendLazy<T>`

`SuspendLazy<T>` is a cached value obtained in a suspend context. When Metro injects one, it defers
initialization until `value()` is called and caches the first successful result for that wrapper
instance.

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

Like `Lazy<T>`, an unscoped `SuspendLazy<T>` caches per wrapper. Two injection sites therefore have
separate caches. For a scoped binding, every wrapper uses the graph's shared cached value.

Injected `SuspendLazy` values are single-flight: one caller computes the value, concurrent callers
wait for it, and a failed or cancelled computation can be retried (i.e. unlike `Deferred`).

`SuspendLazy` can also wrap an ordinary binding when a uniform suspend API is useful.

`suspend () -> T`, `SuspendProvider<T>`, and `SuspendLazy<T>` all support nullable `T`.

### Nested wrappers

`Provider`, function providers, `Lazy`, `SuspendProvider`, suspend functions, and `SuspendLazy` can
be nested to any depth in a scalar wrapper stack. Each wrapper applies to its immediate inner value.
For example:

- `Provider<SuspendLazy<T>>` creates a `SuspendLazy<T>` when the provider is invoked.
- `() -> suspend () -> T` creates the suspend function when the outer function is invoked.
- `SuspendLazy<suspend () -> T>` caches the suspend function, not the `T` returned by that
  function.

When the underlying binding suspends, the wrapper closest to it must also support suspension.
`Provider<T>`, `() -> T`, and `Lazy<T>` cannot fill that role. For example, `suspend () -> Lazy<T>`
cannot wrap a suspending `T`; use `suspend () -> SuspendLazy<T>` instead. The same stack is valid
when `T` is not suspending.

Every supported wrapper stack containing `suspend () -> T`, `SuspendProvider<T>`, or
`SuspendLazy<T>` is behind `metro.enableSuspendProviders`.

## Scoping

!!! note "Scope here means Metro scope"

    Here, scope means a Metro binding scope (`@SingleIn`, `@DependencyGraph(scope = ...)`), not a
    `kotlinx.coroutines.CoroutineScope`. Metro graphs do not own a `CoroutineScope`; suspend
    providers run in the calling coroutine.

Scoped suspend bindings work like other scoped bindings. The first successful result is cached:

```kotlin
@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  suspend fun database(): Database

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): Database = openDatabase()
}
```

The cache has these semantics on every platform:

- At most one caller computes at a time. Concurrent callers wait and share a successful result.
- A failed initialization is not cached. A later caller retries.
- Cancellation during initialization leaves the cache empty. A later caller retries.
- A request for the same cache from its current initialization chain fails with a circular
  dependency error instead of waiting on itself.

All platforms use a coroutine mutex from kotlinx-coroutines.

The initialization check follows structured child coroutines and indirect calls within the same
chain. It does not coordinate separate initialization chains. Two independently launched
coroutines can still deadlock if each initializes one cached binding and then requests the other.
Metro reports cycles visible in the dependency graph; code that invokes providers or lazy values
dynamically must not initialize the same caches in conflicting orders.

Scoped suspend bindings use `dev.zacsweers.metro:runtime-coroutines`, which must be available at
compile time and runtime. The Gradle plugin adds it automatically. If automatic dependency
management is disabled, follow the [manual dependency setup](installation.md#manual-dependency-management).
If the artifact is missing, Metro reports a compile-time error with the dependency to add.

Metro caches the value but does not close it. The application remains responsible for releasing
resources when they are no longer needed.

## Execution context

Metro does not switch dispatchers or launch independent dependencies concurrently. Provider code
runs in the calling coroutine's context, and dependency arguments are initialized sequentially.

For a scoped binding, the first caller runs the initializer and other callers share its result. If
initialization needs a specific dispatcher, switch inside the provider:

```kotlin
@Provides
@SingleIn(AppScope::class)
suspend fun provideDatabase(): Database =
  withContext(Dispatchers.IO) {
    openDatabase()
  }
```

Use the same approach for blocking I/O or thread-confined work. Metro does not choose an execution
context for the provider.

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

Suspend and non-suspend contributions can be mixed. Each provider is initialized when its function
is invoked.

Other collection forms are unsupported:

- `Set<T>` multibindings cannot contain suspend contributions. Provider-valued set forms such as
  `Set<suspend () -> T>` are unsupported, matching `Set<Provider<T>>`.
- `Map<K, V>` over suspend values must be consumed as `Map<K, suspend () -> V>` or
  `Map<K, SuspendProvider<V>>` instead.
- `SuspendLazy` and additional suspend-wrapper layers in map value position, such as
  `Map<K, SuspendLazy<V>>`, are unsupported.

## Assisted injection

If an `@AssistedInject` class consumes suspend bindings, its `@AssistedFactory` function must be
declared `suspend`:

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

Obtaining the factory does not suspend. Calling `create(...)` does. Metro reports an error when the
factory function is not `suspend` but constructing its target requires suspension.

## Member injection

Member injection cannot initialize suspend bindings because injector functions and `MembersInjector`
are synchronous. Change the member type to `suspend () -> T` or `SuspendLazy<T>` to defer
initialization:

```kotlin
class ProfileActivity {
  @Inject lateinit var database: suspend () -> Database
}
```

Metro also rejects a constructor-injected class with injected members when constructing the class
requires suspension. Generated suspend factories do not run the usual post-construction member
injection yet.

## Runtime helpers

The core `runtime` artifact provides `suspendProvider`, `suspendProviderOf`, `suspendLazyOf`, and the
`map`, `flatMap`, and `zip` provider operators. `suspendLazyOf` returns an already initialized
`SuspendLazy`.

```kotlin
// Wrap a lambda
val provider: SuspendProvider<String> = suspendProvider { fetchToken() }

// Wrap an existing value
val fixed: SuspendProvider<String> = suspendProviderOf("token")
val fixedConfig: SuspendLazy<Config> = suspendLazyOf(Config())

// Transform lazily
val mapped: SuspendProvider<Int> = provider.map { it.length }
val zipped: SuspendProvider<Pair<String, Int>> = provider.zip(mapped) { a, b -> a to b }
```

The `runtime-coroutines` artifact provides `suspendLazy` for values initialized on first access:

```kotlin
val config: SuspendLazy<Config> = suspendLazy { loadConfig() }
```

`suspendLazy` accepts the same `LazyThreadSafetyMode` values as `lazy`:

- `SYNCHRONIZED` runs one initializer while other callers wait.
- `PUBLICATION` allows initializers to overlap and caches one result.
- `NONE` does not coordinate concurrent callers.

## Multiplatform

Suspend providers are available on JVM, Android, JS, Native, and Wasm.
