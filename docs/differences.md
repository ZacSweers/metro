# Differences from other DI frameworks

#### …from Dagger

* There is no `@Module`. All providers run through graphs and their supertypes.
* There is no Producers support.
* There is no Hilt support, though some features are similar in the same way that Anvil’s features are similar.
* There is no `@Reusable`.
* There is no `@BindsOptionalOf`.
* There is no `@Subcomponent`.
* Metro can inject private properties and functions.
* There is no `@BindsInstance`. Use `@Provides` on `@DependencyGraph.Factory` function parameters instead

#### …from Kotlin-Inject

* typealiases are not treated as implicit qualifiers.
* Dependency graph classes cannot have primary constructors, their parameters must be defined as `@Provides` or graph parameters on a `@DependencyGraph.Factory` function like Dagger.
* Higher order functions cannot be used. Instead, use `Provider` and declared `@AssistedFactory`-annotated types.
* No need for use-site targets for most annotations.
* No need for `@get:Provides Impl.bind: Type get() = this` to achieve type bindings. See the docs on `@Provides`.
* Metro can inject private properties and functions.

#### …from Anvil

* There is no `rank`.
* `@ContributesBinding` uses a `BoundType` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.

#### …from kotlin-inject-anvil

* There is no need for `@CreateComponent` or `expect fun createComponent()` functions.
* `@ContributesBinding` uses a `BoundType` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.
