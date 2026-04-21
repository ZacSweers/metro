Adoption Strategies
===================

If adopting Metro into an existing codebase, you can use a few different strategies.

1. First, add the Metro Gradle plugin and runtime deps. The plugin id is `dev.zacsweers.metro`, runtime is `dev.zacsweers.metro:runtime`. The Gradle Plugin _should_ add the runtime automatically, but it's there just in case!
2. Apply the Gradle plugin to your relevant project(s).

=== "From Dagger"

    ### Precursor steps

    !!! tip "Compiler options you should enable in Dagger"
        Dagger has some compiler options you should enable and get working first to make it easier to move to Metro.

        - [useBindingGraphFix](https://dagger.dev/dev-guide/compiler-options#useBindingGraphFix) 
            - The issue it fixes is something that Metro catches as well.
        - [ignoreProvisionKeyWildcards](https://dagger.dev/dev-guide/compiler-options#ignore-provision-key-wildcards)

    !!! warning "K2 Migration"
        If you are migrating from square/anvil, you likely are also going to have to migrate to Kotlin K2 as a part of this. If you want to split that effort up, you can consider migrating to [anvil-ksp](https://github.com/zacsweers/anvil) first. This would move fully over to KSP and K2 first, then you can resume here.

    ### Option 1: Interop at the component/graph level

    This option is good if you only want to use Metro for _new_ code. Metro graphs can depend on Dagger components (as `@Includes` parameters) and vice versa. [Here](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-dagger) is an example project that does this.

    This option is also good if you just want to do a simple, isolated introduction of Metro in one part of your codebase, such as a smaller modularized feature or library.

    ### Option 2: Migrate existing usages + reuse your existing annotations

    If you want the least amount of splash as possible, you can tell Metro to reuse your annotations from Dagger/Anvil. [Here](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-dagger/build.gradle.kts#L22-L27) is an example for enabling that in Gradle.

    1. Remove the dagger-compiler/anvil plugin (but keep their runtime deps).
    2. Enable interop with the Metro gradle plugin

    ```kotlin
    metro {
      interop {
        includeDagger()
        includeAnvil() // If using Anvil
      }
    }
    ```

    Most things will Just Work™, but you will still possibly need to do some manual migrations.

    - If you use `KClass` and `Class` interchangeably in your graph, Metro distinguishes between these by default. You can temporarily enable interop via `enableKClassToClassMapKeyInterop` (with some runtime overhead caveats, see the docs), or you'll need to move fully over to `KClass`.
    - If you use `@MergeComponent` with `@Component`, it'll be easier if you just migrate those interfaces to `@DependencyGraph` since they're combined in there now.
      - Not necessary if coming from anvil-ksp.
    - Migrate `@BindsInstance` to `@Provides`. Metro consolidated these to just one annotation.
    - Update references to generated `Dagger*Component` calls to use metro's `createGraph`/`createGraphFactory` APIs.

    You can also remove any `@JvmSuppressWildcard` annotations, these are ignored in Metro.

    ### Option 3: Full migration

    - Remove the Dagger and anvil runtimes.
    - Replace all Dagger/anvil annotations with Metro equivalents.
    - Update references to generated `Dagger*Component` calls to use metro's `createGraph`/`createGraphFactory` APIs.
    - Migrate from javax/jakarta `Provider` and `dagger.Lazy` APIs to the function-syntax `() -> T` provider form (or Metro's `Provider<T>`) and the stdlib's `Lazy` APIs. See [Migrating `Provider<T>` to function syntax](#migrating-providert-to-function-syntax) below.

=== "From kotlin-inject"

    ### Precursor steps

    1. Remove the kotlin-inject(-anvil) dependencies (but keep their runtime deps if you use option 1 below!).
    2. Migrate to `@AssistedFactory` if you haven't already.

    ### Option 1: Interop at the component/graph level

    This option is good if you only want to use Metro for _new_ code. Metro graphs can depend on kotlin-inject components (as `@Includes` parameters) and vice versa. [Here](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-kotlinInject) is an example project that does this.

    This option is also good if you want to do a simple, isolated introduction of Metro in one part of your codebase, such as a smaller modularized feature or library.

    ### Option 2: Migrate existing usages + reuse your existing annotations

    If you want the least amount of splash as possible, you can tell Metro to reuse your annotations from kotlin-inject/kotlin-inject-anvil. [Here](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-kotlinInject/build.gradle.kts#L22-L27) is an example for enabling that in Gradle.

    1. Remove the kotlin-inject and kotlin-inject-anvil KSP processors (but keep their runtime deps).
    2. Enable interop with the Metro Gradle plugin

    ```kotlin
    metro {
      interop {
        includeKotlinInject()
        includeAnvil() // If using kotlin-inject-anvil
      }
    }
    ```

    You will still possibly need to do some manual migrations, namely providers.

    - Any map multibindings need to migrate to use [map keys](bindings.md#multibindings).
    - Any higher order function injection is directly compatible with Metro's function-syntax providers (`() -> T`), which are enabled by default. See [Migrating `Provider<T>` to function syntax](#migrating-providert-to-function-syntax) below for details.
    - Any higher order _assisted_ function injection will need to switch to using `@AssistedFactory`-annotated factories.
    - If you use `@MergeComponent` + `@Component`, it'll be easier if you just migrate those interfaces to `@DependencyGraph` since they're combined in there now.
    - If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
    - Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.

    ### Option 3: Full migration

    - Any map multibindings need to migrate to use [map keys](bindings.md#multibindings).
    - Any higher order function injection is directly compatible with Metro's function-syntax providers (`() -> T`), which are enabled by default.
    - Any higher order _assisted_ function injection will need to switch to using `@AssistedFactory`-annotated factories.
    - Remove the kotlin-inject and kotlin-inject-anvil runtimes.
    - Replace all kotlin-inject/kotlin-inject-anvil annotations with Metro equivalents.
    - If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
    - Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.

## Migrating `Provider<T>` to function syntax

Metro supports two equivalent provider forms:

- **Function syntax** (preferred, default): `() -> T`
- **Desugared**: `Provider<T>`

The function-syntax form is enabled by default (`enableFunctionProviders = true`) and is the recommended way to declare provider dependencies going forward. The desugared `Provider<T>` form still works and remains useful for some interop scenarios, but Metro will surface a compiler diagnostic when it encounters it so you can incrementally migrate.

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

For large codebases, a helper script is available at [`scripts/migrate-metro-provider.sh`](https://github.com/ZacSweers/metro/blob/main/scripts/migrate-metro-provider.sh) that mechanically rewrites `Provider<T>` → `() -> T` across all `.kt` files in a directory:

```bash
scripts/migrate-metro-provider.sh path/to/your/code
```

What it does:

- Only touches files that import `dev.zacsweers.metro.Provider` (explicit or via a `dev.zacsweers.metro.*` wildcard).
- Skips files that also import a non-Metro `Provider` (`javax.inject`, `jakarta.inject`, `com.google.inject`, `dagger.internal`) to avoid ambiguity.
- Handles nested generics (`Provider<Lazy<Foo>>`, `Map<K, Provider<V>>`, etc.) via a recursive regex that loops until stable.
- Preserves `Provider<*>` (since `() -> *` is not valid Kotlin) and `Provider { ... }` factory calls.
- Drops the now-unused `dev.zacsweers.metro.Provider` import when no `Provider` identifier remains.

Review the diff afterwards — the script is deliberately conservative about what it rewrites, but manual spot-checking is always recommended.

### If you previously used function types as binding keys

With `enableFunctionProviders` enabled (the default), any `() -> T` (i.e., `Function0<T>`) on the graph is interpreted as a provider of `T`. If your existing code uses a bare function type as the binding *itself* — e.g., a navigation callback or a side-effecting action injected as `() -> Unit` — Metro will now resolve it as "provide me a `Unit`" instead of "provide me this particular function".

The fix is to give that function a concrete type so its binding key is distinct from the generic function shape. A `fun interface` extending the original function type is the lightest-weight option and keeps SAM conversion at call sites:

```kotlin
// Before — ambiguous under function-syntax providers
@Inject class AppShell(val onBackPressed: () -> Unit)

@Provides fun provideOnBackPressed(): () -> Unit = { /* ... */ }
```

```kotlin
// After — the binding is keyed by OnBackPressed, not by the function shape
fun interface OnBackPressed : () -> Unit

@Inject class AppShell(val onBackPressed: OnBackPressed)

@Provides fun provideOnBackPressed(): OnBackPressed = OnBackPressed { /* ... */ }
```

The same pattern works for any arity (`(T) -> R`, `(T1, T2) -> R`, `suspend () -> T`, etc.) — declare a `fun interface` that extends the function type you were injecting before. Qualifiers (`@Named`, custom `@Qualifier`s) do *not* disambiguate here — the provider semantics apply to `() -> T` regardless of qualifier — so a named type is required.

If you prefer to keep raw function types as bindings (and give up the function-syntax provider form for the whole project), set `enableFunctionProviders = false` in the Gradle `metro { }` block. That's the escape hatch, but be aware it's a project-wide decision and the `desugaredProviderSeverity` diagnostic will be forced to `NONE` since there is no alternative form to migrate to.
