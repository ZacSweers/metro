Adoption Strategies
===================

Choose a migration strategy based on how much existing code you want to change.

1. Add the `dev.zacsweers.metro` Gradle plugin to your build. It adds `dev.zacsweers.metro:runtime` automatically by default.
2. Apply the plugin to each project that uses Metro.

=== "From Dagger"

    ### Precursor steps

    !!! tip "Compiler options you should enable in Dagger"
        Enable these Dagger compiler options before migrating:

        - [useBindingGraphFix](https://dagger.dev/dev-guide/compiler-options#useBindingGraphFix) 
            - Metro also detects the graph issue this option fixes.
        - [ignoreProvisionKeyWildcards](https://dagger.dev/dev-guide/compiler-options#ignore-provision-key-wildcards)

    !!! warning "K2 Migration"
        If square/anvil keeps your project on K1, you may also need to migrate to K2. You can first migrate to [anvil-ksp](https://github.com/zacsweers/anvil) to finish the KSP/K2 migration separately, then move to Metro.

    ### Option 1: Interop at the component/graph level

    Use this approach to introduce Metro in new code or an isolated feature. Metro graphs can depend on Dagger components through `@Includes`, and Dagger components can depend on Metro graphs. See the [example project](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-dagger).

    ### Option 2: Migrate existing usages + reuse your existing annotations

    You can switch to the Metro compiler and keep your Dagger and Anvil annotations. Enable annotation interop as shown in the [Gradle example](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-dagger/build.gradle.kts#L22-L27).

    1. Remove the Dagger compiler and Anvil plugin. Keep their runtime dependencies.
    2. Enable interop in the Metro Gradle plugin:

    ```kotlin
    metro {
      interop {
        includeDagger()
        includeAnvil() // If using Anvil
      }
    }
    ```

    If your Dagger codebase also uses Hilt, use `includeHilt()` instead of `includeDagger()` in Metro modules that should consume Hilt declarations. This also enables Dagger interop.

    ```kotlin
    metro {
      interop {
        includeHilt()
      }
    }
    ```

    With this enabled, Metro treats Hilt's component target as an aggregation scope:

    - `@InstallIn(<component>::class) @Module` classes are merged as binding containers.
    - `@InstallIn(<component>::class) @EntryPoint` interfaces are merged as graph supertypes.
    - Standard Hilt components map to their canonical scopes, such as `SingletonComponent` to `@Singleton`.
    - Custom `@DefineComponent` interfaces are mapped from the scope annotation on the component interface.

    This lets you keep Hilt modules and entry points in place while introducing Metro graphs. Hilt scoped bindings still use their concrete scope annotations, so a Metro graph that consumes scoped Hilt bindings should carry both the aggregation key and the concrete scope annotation:

    ```kotlin
    @Singleton
    @DependencyGraph(Singleton::class)
    interface AppGraph
    ```

    Metro does not consume Hilt's generated component tree from `@HiltAndroidApp`; it merges the Hilt declarations into Metro graphs directly. See [Hilt interop](interop.md#hilt) for the full mapping and current limitations.

    Most things should Just Work™, but check these cases as you migrate.

    - Metro distinguishes `KClass` and `Class` map keys by default. Migrate to `KClass`, or temporarily enable [`enableKClassToClassMapKeyInterop`](interop.md#classkclass-map-key-interop), which can add runtime overhead.
    - Consider replacing paired `@MergeComponent` and `@Component` annotations with `@DependencyGraph`.
        - This step is unnecessary when migrating from anvil-ksp.
    - Replace `@BindsInstance` with `@Provides`.
    - Replace generated `Dagger*Component` calls with Metro’s `createGraph` or `createGraphFactory` APIs.

    Metro ignores `@JvmSuppressWildcards`. You can remove it if nothing else needs it.

    ### Option 3: Full migration

    - Remove the Dagger and Anvil runtimes.
    - Replace Dagger and Anvil annotations with Metro equivalents.
    - Replace generated `Dagger*Component` calls with `createGraph` or `createGraphFactory`.
    - Migrate from javax/jakarta `Provider` and `dagger.Lazy` APIs to the function-syntax `() -> T` provider form (or Metro's `Provider<T>`) and the stdlib's `Lazy` APIs. See [Migrating `Provider<T>` to function syntax](#migrating-providert-to-function-syntax) below.

=== "From kotlin-inject"

    !!! warning "Keep `enableFunctionProviders` enabled"
        Leave `enableFunctionProviders` enabled. It is on by default and makes Metro resolve kotlin-inject’s `() -> T` provider requests as providers of `T`. Disabling it breaks those requests.

    ### Precursor steps

    1. Remove the kotlin-inject and kotlin-inject-anvil processors. Keep their runtimes if you choose option 1.
    2. Migrate to `@AssistedFactory` if you haven't already.

    ### Option 1: Interop at the component/graph level

    Use this approach for new code or an isolated feature. Metro graphs can depend on kotlin-inject components through `@Includes`, and kotlin-inject components can depend on Metro graphs. See the [example project](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-kotlinInject).

    ### Option 2: Migrate existing usages + reuse your existing annotations

    Enable annotation interop to keep kotlin-inject and kotlin-inject-anvil annotations. See the [Gradle example](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-kotlinInject/build.gradle.kts#L22-L27).

    1. Remove the kotlin-inject and kotlin-inject-anvil KSP processors. Keep their runtime dependencies.
    2. Enable interop in the Metro Gradle plugin:

    ```kotlin
    metro {
      interop {
        includeKotlinInject()
        includeAnvil() // If using kotlin-inject-anvil
      }
    }
    ```

    You may still need to migrate a few things by hand. Here’s what to check.

    - Migrate map multibindings to [map keys](bindings.md#multibindings).
    - Keep `() -> T` provider requests. Metro supports them by default. See [Migrating `Provider<T>` to function syntax](#migrating-providert-to-function-syntax).
    - Replace assisted function injection with `@AssistedFactory` factories.
    - Consider replacing paired `@MergeComponent` and `@Component` annotations with `@DependencyGraph`.
    - Migrate `@Component` parameters to [graph extensions](dependency-graphs.md#graph-extensions). Annotate the parameter with `@Nested` and mark the parent graph as extendable.
    - Replace generated `SomeComponent::class.create(...)` calls with `createGraph` or `createGraphFactory`.

    ### Option 3: Full migration

    - Migrate map multibindings to [map keys](bindings.md#multibindings).
    - Keep `() -> T` provider requests. Metro supports them by default.
    - Replace assisted function injection with `@AssistedFactory` factories.
    - Remove the kotlin-inject and kotlin-inject-anvil runtimes.
    - Replace all kotlin-inject/kotlin-inject-anvil annotations with Metro equivalents.
    - Migrate `@Component` parameters to [graph extensions](dependency-graphs.md#graph-extensions). Annotate the parameter with `@Nested` and mark the parent graph as extendable.
    - Replace generated `SomeComponent::class.create(...)` calls with `createGraph` or `createGraphFactory`.

## Migrating `Provider<T>` to function syntax

Metro supports two equivalent provider forms:

- **Function syntax** (preferred, default): `() -> T`
- **Desugared**: `Provider<T>`

Metro recommends `() -> T` and enables it by default through `enableFunctionProviders`. `Provider<T>` remains supported for interop. Metro reports a configurable diagnostic when it encounters that form.

### Controlling the diagnostic severity

Use the `desugaredProviderSeverity` compiler option to control how Metro reports `Provider<T>` usages:

```kotlin
metro {
  // `WARN` by default. Set to `NONE` while migrating a large codebase, or
  // `ERROR` to enforce function syntax for new code.
  desugaredProviderSeverity.set(DiagnosticSeverity.NONE)
}
```

Values:

- `NONE` — no diagnostic (also forced when `enableFunctionProviders` is disabled).
- `WARN` *(default)* — emit a compiler warning.
- `ERROR` — fail the compilation.

If a specific declaration intentionally uses `Provider<T>` (e.g., to interop with an external API), suppress the warning locally:

```kotlin
@Suppress("DESUGARED_PROVIDER_WARNING")
val metroProvider: Provider<Foo>
```

### Automated migration

Run [`scripts/migrate-metro-provider.sh`](https://github.com/ZacSweers/metro/blob/main/scripts/migrate-metro-provider.sh) to rewrite `Provider<T>` to `() -> T` in Kotlin files under a directory:

```bash
scripts/migrate-metro-provider.sh path/to/your/code
```

The script:

- Only touches files that import `dev.zacsweers.metro.Provider` (explicit or via a `dev.zacsweers.metro.*` wildcard).
- Skips files that also import a non-Metro `Provider` (`javax.inject`, `jakarta.inject`, `com.google.inject`, `dagger.internal`) to avoid ambiguity.
- Handles nested generics (`Provider<Lazy<Foo>>`, `Map<K, Provider<V>>`, etc.) via a recursive regex that loops until stable.
- Preserves `Provider<*>` (since `() -> *` is not valid Kotlin) and `Provider { ... }` factory calls.
- Drops the now-unused `dev.zacsweers.metro.Provider` import when no `Provider` identifier remains.

The script deliberately skips ambiguous cases. Review the resulting diff for cases that need manual migration.

### If you previously used function types as binding keys

When `enableFunctionProviders` is enabled (the default), Metro interprets `() -> T` (`Function0<T>`) as a provider of `T`. If you inject a callback such as `() -> Unit` as a value, Metro will instead try to resolve a `Unit` binding.

Give the callback a named type so it has a distinct binding key. A `fun interface` extending the function type keeps SAM conversion at call sites:

```kotlin
// Before: ambiguous under function-syntax providers
@Inject class AppShell(val onBackPressed: () -> Unit)

@Provides fun provideOnBackPressed(): () -> Unit = { /* ... */ }
```

```kotlin
// After: the binding is keyed by OnBackPressed, not by the function shape
fun interface OnBackPressed : () -> Unit

@Inject class AppShell(val onBackPressed: OnBackPressed)

@Provides fun provideOnBackPressed(): OnBackPressed = OnBackPressed { /* ... */ }
```

Qualifiers do not change provider semantics for `() -> T`. A named type is required even if the callback has `@Named` or another `@Qualifier`.

To use raw function types as bindings throughout a project, set `enableFunctionProviders = false` in the `metro` DSL. This disables function-syntax providers and forces `desugaredProviderSeverity` to `NONE`.
