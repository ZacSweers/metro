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
    - Migrate from javax/jakarta `Provider` and `dagger.Lazy` APIs to Metro's `Provider` and the stdlib's `Lazy` APIs.

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
    - Any higher order function injection will need to switch to using Metro's `Provider` API.
    - Any higher order _assisted_ function injection will need to switch to using `@AssistedFactory`-annotated factories.
    - If you use `@MergeComponent` + `@Component`, it'll be easier if you just migrate those interfaces to `@DependencyGraph` since they're combined in there now.
    - If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
    - Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.

    ### Option 3: Full migration

    - Any map multibindings need to migrate to use [map keys](bindings.md#multibindings).
    - Any higher order function injection will need to switch to using Metro's `Provider` API.
    - Any higher order _assisted_ function injection will need to switch to using `@AssistedFactory`-annotated factories.
    - Remove the kotlin-inject and kotlin-inject-anvil runtimes.
    - Replace all kotlin-inject/kotlin-inject-anvil annotations with Metro equivalents.
    - If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
    - Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.
