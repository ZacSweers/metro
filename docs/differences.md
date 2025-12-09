# Differences from other DI frameworks

=== "Dagger"

    * `@Binds` and `@Provides` declarations can be added directly within graphs and their supertypes.
        * `@BindingContainer` is the Metro equivalent of a Dagger `@Module` but it should rarely be used.
        * `@BindingContainer` cannot declare graph extensions in the way `@Module` can declare subcomponents. Use contributed graph extensions.
    * There is no Producers support.
    * There is no Hilt support, though some features are similar in the same way that Anvil’s features are similar.
    * There is no `@Reusable`.
    * There is no `@BindsOptionalOf`. Instead, Metro supports default [optional bindings](bindings.md#optional-bindings).
        * Metro does support [interop](interop.md) with Dagger's `@BindsOptionalOf` annotation.
    * Metro can inject private properties, functions, and constructors.
    * There is no `@BindsInstance` annotation. Use `@Provides` on `@DependencyGraph.Factory` function parameters instead.
    * Included dependencies ("component dependencies" in Dagger) must be annotated with `@Includes` in graph creators.
    * Metro does not process Java code.
    * `@Multibinds` declarations are implemented in Metro graphs to return the declared multibinding.
    * Empty multibindings are an error by default in Metro. To allow a multibinding to be empty, it must be declared with `@Multibinds(allowEmpty = true)`.
    * Metro graph classes may not directly extend other graph classes. You should use [graph extensions](dependency-graphs.md#graph-extensions) instead in Metro.
      * Dagger technically allows this, but only accessors and injectors cross these boundaries.
    * Metro prohibits scopes on `@Binds` declarations. Either use `@Provides` or move the scope to the source class type.

=== "Kotlin-Inject"

    * typealiases are not treated as implicit qualifiers.
    * Dependency graph classes cannot have primary constructors, their parameters must be defined as `@Provides` or graph parameters on a `@DependencyGraph.Factory` function like Dagger.
    * Higher order functions cannot be used. Instead, use `Provider` and declared `@AssistedFactory`-annotated types.
    * No need for use-site targets for most annotations.
    * No need for `@get:Provides Impl.bind: Type get() = this` to achieve type bindings. See the docs on `@Binds`.
    * Metro can inject private properties and functions.
    * Metro does not support detached graph extensions the way kotlin-inject does. Instead, use [graph extensions](dependency-graphs.md#graph-extensions).
    * Metro does not process Java code.
    * Metro does not support assisted parameters in `@Provides` functions.
    - Metro map multibindings use static [map keys](bindings.md#multibindings) rather than aggregating via `Pair` contributions. More details on why can be found [here](https://github.com/ZacSweers/metro/discussions/770#discussioncomment-13852087).

=== "Anvil"

    * There is no `rank` in Metro's `@Contributes*` annotations.
        * Note that if Anvil interop is enabled, _its_ `rank` properties are supported in interop.
    * There is no `ignoreQualifier` in Metro's `@Contributes*` annotations.
        * Note that if Anvil interop is enabled, _its_ `ignoreQualifier` properties are supported in interop.
    * `@ContributesBinding` uses a `binding` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.
        * Note that if Anvil interop is enabled, _its_ `boundType` properties are supported in interop.

=== "kotlin-inject-anvil"

    * There is no need for `@CreateComponent` or `expect fun createComponent()` functions.
    * `@ContributesBinding` uses a `binding` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.
        * Note that if Anvil interop is enabled, _its_ `boundType` properties are supported in interop.
