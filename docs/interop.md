# Interop

## Annotations

Metro supports user-defined annotations for common annotations. This means that a user doesn’t necessarily have to use Metro’s annotations if they’re introducing it to an existing codebase. Support varies depending on the annotation’s use case.

Compile-only annotations are mostly supported. This includes the following:

* `@Assisted`
* `@AssistedFactory`
* `@AssistedInject`
* `@Binds`
* `@BindsInstance`
* `@ContributesTo`
* `@ContributesBinding`
* `@ElementsIntoSet`
* `@DependencyGraph`
* `@DependencyGraph.Factory`
* `@Inject`
* `@IntoMap`
* `@IntoSet`
* `@MapKey`
* `@Multibinds`
* `@Provides`
* `@Qualifier`
* `@Scope`

These are configurable via Metro’s Gradle extension.

```kotlin
metro {
  customAnnotations {
    assisted.add("dagger/assisted/Assisted")
  }
}
```

For Dagger and KI specifically, there are convenience helper functions.

```kotlin
metro {
  customAnnotations {
    includeDagger()
    includeKotlinInject()
    includeAnvil()
  }
}
```

`@DependencyGraph` is replaceable but your mileage may vary if you use Anvil or modules, since Metro’s annotation unifies Anvil’s `@MergeComponent` functionality and doesn’t support modules.

Similarly, `@ContributesBinding` is replaceable but there are not direct analogues for Anvil’s `@ContributesMultibinding` or kotlin-inject-anvil’s `@ContributesBinding(multibinding = …)` as these annotations are implemented as `@ContributesInto*` annotations in Metro. Also - `boundType` in metro uses a more flexible mechanism to support generics.

Intrinsics like `Provider` and `Lazy` are not supported because their semantics are slightly different. However, we could look into this in the future as integration artifacts that offer composite implementations (similar to how Dagger’s internal `Provider` implements both `javax.inject.Provider` and `jakarta.inject.Provider` now).

## Components

Metro graphs can interop with components generated by Dagger and Kotlin-Inject. These work exclusively through their public accessors and can be depended on like any other graph dependency.

```kotlin
@DependencyGraph
interface MetroGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      daggerComponent: DaggerComponent
    ): MetroGraph
  }
}

@dagger.Component
interface DaggerComponent {
  val message: String

  @dagger.Component.Factory
  fun interface Factory {
    fun create(@Provides message: String): DaggerComponent
  }
}
```

Conversely, kotlin-inject and Dagger components can also depend on Metro graphs.

```kotlin
@DependencyGraph
interface MessageGraph {
  val message: String

  // ...
}

// Dagger
@Component(dependencies = [MetroGraph::class])
interface DaggerComponent {
  val message: String

  @Component.Factory
  fun interface Factory {
    fun create(messageGraph: MessageGraph): DaggerComponent
  }
}

// kotlin-inject
@Component
abstract class KotlinInjectComponent(
  @Component val messageGraph: MessageGraph
) {
  abstract val message: String
}
```