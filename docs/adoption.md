Adoption Strategies
===================

If adopting Metro into an existing codebase, you can use a few different strategies.

1. First, add the Metro Gradle plugin and runtime deps. The plugin id is `dev.zacsweers.metro`, runtime is `dev.zacsweers.metro:runtime`. The Gradle Plugin _should_ add the runtime automatically, but it's there just in case!
2. Apply the Gradle plugin to your relevant project(s).

## Into a Dagger codebase

First, remove the dagger-compiler/anvil plugin (but keep their runtime deps if you use option 1 below!).

### Option 1 - Interop at the component/graph level

This option is good if you only want to use Metro for _new_ code. Metro graphs can depend on Dagger components (as `@Includes` parameters) and vice versa. [Here](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-dagger) is an example project that does this.

This option is good if you want to do a very simple introduction of Metro in one part of your codebase, such as a smaller modularized feature or library.

### Option 2 - Migrate existing usages + reuse your existing annotations

If you want the least amount of splash as possible, you can tell Metro to reuse your annotations from Dagger/Anvil. [Here](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-dagger/build.gradle.kts#L22-L27) is an example for enabling that in Gradle.

You will still possibly need to do some manual migrations, namely modules.

- All modules will need to be converted to interfaces and either contributed to or extended by your target graph (i.e. a component).
  - If your module is an `object`, easiest way to change is to
    - Change it to an `interface`
    - Create a companion object in it
    - Move all your provides declarations to within that companion object
- If you use `@MergeComponent` + `@Component`, it'll be easier if you just migrate those interfaces to `@DependencyGraph` since they're combined in there now.
- Migrate `@BindsInstance` to `@Provides`. Metro consolidated these to just one annotation.
- If you use subcomponents, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions).
- Update references to generated `Dagger*Component` calls to use metro's `createGraph`/`createGraphFactory` APIs.

!!! warning
    If you use `@ContributesSubcomponent`, you'll have to either wait until they're implemented (see [#166](https://github.com/ZacSweers/metro/issues/166))

### Option 3 - Full migration

- Remove the Dagger and anvil runtimes.
- Replace all Dagger/anvil annotations with Metro equivalents.
- If you use subcomponents, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions).
- Update references to generated `Dagger*Component` calls to use metro's `createGraph`/`createGraphFactory` APIs.

!!! warning
    If you use `@ContributesSubcomponent`, you'll have to either wait until they're implemented (see [#166](https://github.com/ZacSweers/metro/issues/166)

## Into a kotlin-inject codebase

First, remove the kotlin-inject(-anvil) dependencies (but keep their runtime deps if you use option 1 below!).

### Option 1 - Interop at the component/graph level

This option is good if you only want to use Metro for _new_ code. Metro graphs can depend on kotlin-inject components (as `@Includes` parameters) and vice versa. [Here](https://github.com/ZacSweers/metro/tree/main/samples/interop/dependencies-kotlinInject) is an example project that does this.

This option is good if you want to do a very simple introduction of Metro in one part of your codebase, such as a smaller modularized feature or library.

### Option 2 - Migrate existing usages + reuse your existing annotations

If you want the least amount of splash as possible, you can tell Metro to reuse your annotations from kotlin-inject/kotlin-inject-anvil. [Here](https://github.com/ZacSweers/metro/blob/main/samples/interop/customAnnotations-kotlinInject/build.gradle.kts#L22-L27) is an example for enabling that in Gradle.

You will still possibly need to do some manual migrations, namely providers.

- Any higher order function injection will need to switch to using Metro's `Provider` API.
- Any higher order _assisted_ function injection will need to switch to using `@AssistedFactory`-annotated factories.
- If you use `@MergeComponent` + `@Component`, it'll be easier if you just migrate those interfaces to `@DependencyGraph` since they're combined in there now.
- If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
- Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.

!!! warning
    If you use `@ContributesSubcomponent`, you'll have to either wait until they're implemented (see [#166](https://github.com/ZacSweers/metro/issues/166))

### Option 3 - Full migration

- Remove the kotlin-inject and Anvil runtimes.
- Replace all kotlin-inject/anvil annotations with Metro equivalents.
- If you use `@Component` parameters for graph extensions, you'll have to switch to [Graph extensions](dependency-graphs.md#graph-extensions). This will primarily entail annotating the parameter with `@Nested` and marking the parent graph as extendable.
- Update calls to generated `SomeComponent::class.create(...)` functions to use metro's `createGraph`/`createGraphFactory` APIs.

!!! warning
    If you use `@ContributesSubcomponent`, you'll have to either wait until they're implemented (see [#166](https://github.com/ZacSweers/metro/issues/166))