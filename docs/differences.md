# Differences from other DI frameworks

=== "Dagger"

    * `@Binds` and `@Provides` declarations can be added directly within graphs and their supertypes.
        * `@BindingContainer` is similar to a Dagger `@Module`. Prefer declaring providers directly in graphs or their supertypes when practical.
        * `@BindingContainer` cannot declare graph extensions in the way `@Module` can declare subcomponents. Use contributed graph extensions.
        * Concrete `@BindingContainer` classes must be effectively final. Kotlin classes are final by default; Dagger `@Module` classes used through interop may still be non-final.
    * Metro supports [suspend providers](coroutines.md) and does not implement Dagger Producers.
    * Metro supports [Hilt module and entry point interop](interop.md#hilt). It does not generate Hilt’s Android component tree.
    * There is no `@Reusable`.
    * There is no `@LazyClassKey`.
    * When migrating `@BindsOptionalOf`, use a default value for an optional parameter or `@OptionalBinding` for an [optional accessor](bindings.md#optional-bindings).
        * Metro does support [interop](interop.md) with Dagger's `@BindsOptionalOf` annotation.
    * Metro can inject private properties, functions, and constructors.
    * There is no `@BindsInstance` annotation. Use `@Provides` on `@DependencyGraph.Factory` function parameters instead.
    * Included dependencies ("component dependencies" in Dagger) must be annotated with `@Includes` in graph creators.
    * Metro does not process Java code.
    * `@Multibinds` declarations are implemented in Metro graphs to return the declared multibinding.
    * Empty multibindings are an error by default in Metro. To allow a multibinding to be empty, it must be declared with `@Multibinds(allowEmpty = true)`.
    * Metro graph classes cannot directly extend other graph classes. Use [graph extensions](dependency-graphs.md#graph-extensions).
      * Dagger technically allows this, but only accessors and injectors cross these boundaries.
    * Metro prohibits scopes on `@Binds` declarations. Either use `@Provides` or move the scope to the source class type.
    * Generic binding containers can be concrete.

=== "Kotlin-Inject"

    * Typealiases are not implicit qualifiers.
    * Graph classes cannot have primary constructors. Supply inputs as `@Provides` or `@Includes` parameters on a `@DependencyGraph.Factory` function.
    * Metro supports `() -> T` providers by default. Use `@AssistedFactory` for assisted parameters.
    * Most annotations do not need use-site targets.
    * Use [`@Binds`](bindings.md#binds) for alias bindings such as `@get:Provides Impl.bind: Type get() = this` in kotlin-inject.
    * Metro can inject private properties and functions.
    * Metro does not support detached graph extensions the way kotlin-inject does. Instead, use [graph extensions](dependency-graphs.md#graph-extensions).
    * Metro does not process Java code.
    * Metro does not support assisted parameters in `@Provides` functions.
    * Metro uses static [map keys](bindings.md#multibindings); kotlin-inject contributions return a `Pair` of key and value. See the [design discussion](https://github.com/ZacSweers/metro/discussions/770#discussioncomment-13852087).

=== "Anvil"

    * `@ContributesBinding` and `@ContributesIntoMap` use `priority` instead of Anvil's `rank`.
        * If Anvil interop is enabled, its numeric `rank` properties are treated as contribution priorities.
    * There is no `ignoreQualifier` in Metro's `@Contributes*` annotations.
        * Anvil interop supports Anvil’s `ignoreQualifier` properties.
    * `@ContributesBinding` uses `binding` for generic bound types. See [Aggregation](aggregation.md).
        * Anvil interop supports Anvil’s `boundType` properties.

=== "kotlin-inject-anvil"

    * There is no need for `@CreateComponent` or `expect fun createComponent()` functions.
    * `@ContributesBinding` uses `binding` for generic bound types. See [Aggregation](aggregation.md).
        * Anvil interop supports Anvil’s `boundType` properties.
