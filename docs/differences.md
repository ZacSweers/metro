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
* Component dependencies must be annotated with `@Includes`.
* Metro does not process Java code.

#### …from Kotlin-Inject

* typealiases are not treated as implicit qualifiers.
* Dependency graph classes cannot have primary constructors, their parameters must be defined as `@Provides` or graph parameters on a `@DependencyGraph.Factory` function like Dagger.
* Higher order functions cannot be used. Instead, use `Provider` and declared `@AssistedFactory`-annotated types.
* No need for use-site targets for most annotations.
* No need for `@get:Provides Impl.bind: Type get() = this` to achieve type bindings. See the docs on `@Provides`.
* Metro can inject private properties and functions.
* When extending parent graphs, they must be annotated with `@Extends` in the child graph's creator.
* Metro does not process Java code.

#### …from Anvil

* There is no `rank`.
* `@ContributesBinding` uses a `binding` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.
* `@Contributes*.replaces` cannot replace classes in the same compilation as the graph that is merging them

#### …from kotlin-inject-anvil

* There is no need for `@CreateComponent` or `expect fun createComponent()` functions.
* `@ContributesBinding` uses a `binding` API to support generic bound types. See the [aggregation docs](aggregation.md) for more info.
* `@Contributes*.replaces` cannot replace classes in the same compilation as the graph that is merging them
