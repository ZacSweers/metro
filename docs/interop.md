# Interop

## Annotations

Metro can recognize annotations from other DI frameworks or your own code. Supported mappings depend on the annotation’s role.

Metro supports mappings for these compile-time annotations:

* `@AssistedFactory`
* `@AssistedInject`
* `@Assisted`
  * Dagger’s explicit `@Assisted.value` identifiers are supported. Metro’s own `@Assisted` matches parameters by name.
* `@BindsInstance`
* `@Binds`
* `@ContributesBinding`
* `@ContributesTo`
* `@DependencyGraph.Factory`
* `@DependencyGraph`
* `@ElementsIntoSet`
* `@Inject`
* `@IntoMap`
* `@IntoSet`
* `@MapKey`
* `@Module`
* `@Multibinds`
* `@Provides`
* `@Qualifier`
* `@Scope`

Configure annotation mappings in the Metro Gradle extension:

```kotlin
metro {
  interop {
    assisted.add("dagger/assisted/Assisted")
  }
}
```

Use the built-in helpers for supported frameworks:

```kotlin
metro {
  interop {
    // Dagger
    includeDagger()
    includeAnvilForDagger()

    // Hilt
    includeHilt()

    // kotlin-inject
    includeKotlinInject()
    includeAnvilForKotlinInject()
    
    // Guice
    includeGuice()
  }
}
```
!!! warning
    Dagger's special-case `@Reusable` and `@LazyClassKey` annotations are not supported in Metro.

A custom `@DependencyGraph` mapping may need migration work if your code uses Anvil’s separate `@MergeComponent` or Dagger modules. Metro combines graph creation and contribution merging in one annotation.

Metro maps Anvil’s `@ContributesMultibinding` to `@ContributesIntoSet` or `@ContributesIntoMap`, according to the contribution.

Metro treats kotlin-inject-anvil’s `@ContributesBinding(multibinding = true)` as `@ContributesIntoSet`.

Metro’s `binding` supports generic types. Anvil’s `boundType: KClass<*>` is also supported through interop.

Metro's `@ContributesBinding` and `@ContributesIntoMap` use `priority` to choose between conflicting contributions. When Anvil interop is enabled for the consuming graph, Anvil's numeric `rank` argument is interpreted as the same priority. Anvil's unrelated enum-valued `priority` argument is ignored.

## Components

Metro graphs can depend on Dagger and kotlin-inject components through their public accessors.

```kotlin
@DependencyGraph
interface MetroGraph {
  val message: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes daggerComponent: DaggerComponent
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
@Component(dependencies = [MessageGraph::class])
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

## Runtime

### Dagger

Enabling dagger interop also enables more advanced _runtime_ interop with Dagger/Javax/Jakarta's `Provider`/`Lazy` types.

```kotlin
metro {
  interop {
    includeDagger()
  }
}
```

Dagger interop enables:

1. Interop with Dagger/Javax/Jakarta's `Provider` and `Lazy` runtime intrinsics.
2. Reuse of Dagger-generated factories and injectors for constructor injection, assisted injection, member injection, and modules. Metro can consume an upstream class or module processed by the Dagger compiler, or by Anvil with factory generation enabled, using its generated factory or injector.
3. Interop with Dagger's `@BindsOptionalOf` annotation.

The Gradle plugin adds `dev.zacsweers.metro:interop-dagger` automatically. For annotation-only interop, configure the individual annotation mappings.

### `Class`/`KClass` map key interop

On JVM and Android, enable `enableKClassToClassMapKeyInterop` to treat `java.lang.Class` and `kotlin.reflect.KClass` as interchangeable map key types. This matches Kotlin’s annotation behavior, for example `@ClassKey(Foo::class)` contributing to `Map<Class<*>, V>`. The option applies only to map keys, where annotation arguments become values in ordinary generated code. `Class` and `KClass` remain distinct in regular Kotlin code.

The option is disabled by default, including when `includeDagger()` is enabled. Converting the underlying `KClass` keys can add runtime overhead. Prefer `KClass` keys and call `.java` where needed.

### Guice

Enabling Guice interop enables annotation interop with the following Guice annotations:

- `@Inject` (but not `@Inject.optional`)
- `@Provides`
- `@Assisted`
  - Guice’s explicit `@Assisted.value` identifiers are supported. Metro’s own `@Assisted` matches parameters by name.
- `@AssistedInject`
- `@BindingAnnotation`
- `@ScopeAnnotation`
- `@MapKey`
- `@ProvidesIntoMap`
- `@ProvidesIntoSet`

Enabling this also enables _runtime_ interop with:

- Guice’s `Provider`, interchangeable with Metro’s `Provider`.
- Jakarta's `Provider` type.
- Guice modules
    - Only `@Provides` declarations are supported. Metro does not execute `configure` implementations.

```kotlin
metro {
  interop {
    includeGuice()
  }
}
```

The Gradle plugin adds `dev.zacsweers.metro:interop-guice` automatically.

!!! question "Why not javax.inject?"
    Guice dropped support for javax.inject in 7.0.0.

## Hilt

Hilt interop lets a Metro `@DependencyGraph` merge Hilt modules and entry points during a migration. Enable it with:

```kotlin
metro {
  interop {
    includeHilt()
  }
}
```

!!! tip
    `includeHilt()` implicitly chains `includeDagger()`

Metro treats Hilt's component target as the graph scope. A `@DependencyGraph(<scope>)` will merge:

* `@InstallIn(<component>::class) @Module` classes as binding containers. Their `@Provides` declarations enter the graph.
* `@InstallIn(<component>::class) @EntryPoint` interfaces as graph supertypes. Their accessors are implemented by the graph.

This works for Hilt declarations compiled in the same module as the graph, and for declarations coming from upstream modules. Upstream modules are discovered either through Metro's contribution hints, when they also apply `includeHilt()`, or through Hilt's generated aggregation metadata when Hilt's KSP or KAPT processor ran upstream.

### Built-in component-to-scope mapping

Standard Hilt components map to their canonical scopes automatically:

| Hilt component              | Scope                     |
|-----------------------------|---------------------------|
| `SingletonComponent`        | `@Singleton`              |
| `ActivityRetainedComponent` | `@ActivityRetainedScoped` |
| `ActivityComponent`         | `@ActivityScoped`         |
| `ViewModelComponent`        | `@ViewModelScoped`        |
| `FragmentComponent`         | `@FragmentScoped`         |
| `ServiceComponent`          | `@ServiceScoped`          |
| `ViewComponent`             | `@ViewScoped`             |
| `ViewWithFragmentComponent` | `@ViewScoped`             |

For example, `@DependencyGraph(Singleton::class)` pulls `@InstallIn(SingletonComponent::class)` sites, and `@DependencyGraph(ActivityScoped::class)` pulls `@InstallIn(ActivityComponent::class)` sites.

#### Custom `@DefineComponent`

Custom `@DefineComponent` interfaces work without extra registration. Metro resolves the component's Metro scope from the scope annotation declared on the component itself:

```kotlin
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class FeatureScoped

@FeatureScoped
@DefineComponent(parent = SingletonComponent::class)
interface FeatureComponent

@Module
@InstallIn(FeatureComponent::class)
class FeatureModule {
  @FeatureScoped
  @Provides fun provideTag(): String = "feature"
}

@FeatureScoped
@DependencyGraph(FeatureScoped::class)
interface FeatureGraph {
  val tag: String
}
```

The `@DependencyGraph(FeatureScoped::class)` argument is the aggregation key. If contributed Hilt bindings are also scoped with `@FeatureScoped`, put `@FeatureScoped` on the graph too so Metro's scope compatibility check sees the concrete scope annotation.

### Limitations

* `@TestInstallIn` and `@CustomTestApplication` are not supported.
* `@DefineComponent.parent` is not used to derive Metro `@GraphExtension` parent/child relationships. Component hierarchy must be expressed through Metro's own graph extension APIs.
* No `EntryPointAccessors`-style runtime helper is shipped. Cast the graph to the entry-point interface directly, or write a small helper that does so for your codebase.
* The Hilt-internal `componentEntryPoints` field on `@AggregatedDeps` is ignored.
* A custom `@DefineComponent` must have a scope annotation on the component interface. If Metro cannot find one, matching `@InstallIn` sites are skipped without a diagnostic.
* Hilt component generation from `@HiltAndroidApp` is not consumed. Metro merges contributions itself.

See [`samples/interop/customAnnotations-hilt`](https://github.com/ZacSweers/metro/tree/main/samples/interop/customAnnotations-hilt) for a complete two-module example.

## Diagnostics

When interoping with annotations that are written in Kotlin and have parameters, it may be unsafe to rely on positional arguments. Metro's own annotations often have the same indices, but not always! If you want to be super safe, you can enable the `interopAnnotationsNamedArgSeverity` to `WARN` or `ERROR` to report diagnostics for positional arguments in any custom annotations that Metro is configured to look at.

!!! question "Why only Kotlin annotations?"
    This is because the Kotlin compiler doesn't support positional arguments for annotations that are written in Java.
