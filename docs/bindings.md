# Bindings

## Qualifiers

Like Dagger and KI, Metro supports *qualifier annotations* to allow disambiguation of types. These are applied at injection and provision sites. A qualifier annotation is any annotation annotated with `@Qualifier`. For convenience, there is an included `@Named` qualifier available in Metro’s runtime that can be used too.

A “type key” in Metro is composed of a concrete type and (if any) qualifier annotation attached to it.

```kotlin
@DependencyGraph
interface AppGraph {
  val int: Int
  @Named("named") val namedInt: Int

  @Provides
  fun provideInt(): Int = 3

  @Provides
  @Named("named")
  fun provideNamedInt(): Int = 4
}
```

## @Binds

In many cases, a developer may have an implementation type on the graph that they want to expose as just its supertype.

Like Dagger, Metro supports this with `@Binds`.

!!! tip "For multi-module projects"
    If you're working across multiple modules and want bindings to be automatically discovered, see [`@ContributesBinding`](aggregation.md#contributesbinding) for a more powerful approach that supports aggregation.

For these cases, an abstract provider can be specified with the following conditions.

* It must be abstract
* It must define one extension receiver that is a subtype of its provided type

```kotlin
@DependencyGraph
interface AppGraph {
  val message: Message

  // Bind MessageImpl as Message
  @Binds val MessageImpl.bind: Message

  @Provides
  fun provideText(): String = "Hello, world!"
}

@Inject
class MessageImpl(val text: String) : Message
```

If you want to limit access to these from your API, you can make these declarations `private` and just return `this`. Note it’s still important to annotate them with `@Binds` so that the Metro compiler understands its intent! Otherwise, it’s an error to *implement* these declarations.

`@Binds` declarations can also declare multibinding annotations.

```kotlin
@DependencyGraph
interface AppGraph {
  val messages: Message

  @Binds @IntoSet val MessageImpl.bind: Message
}
```

`@Binds` declarations may also be declared in [binding Containers](dependency-graphs.md#binding-containers).

!!! note
    In theory, you can implement a provider with a getter that replicates this (similar to how kotlin-inject uses `@get:Provider` + `this`), but this will be an error in FIR because Metro can generate more efficient code at compile-time if you use `@Binds`. This is because Metro can avoid calling the function entirely and just use this information at compile-time to optimize the generated code.

## Multibindings

Like Dagger and KI, Metro supports `Set` and `Map` multibindings. Multibindings are collections of bindings of a common type. Multibindings are implicitly declared by the existence of providers annotated with `@IntoSet`, `@IntoMap`, or `@ElementsIntoSet`.

```kotlin
@DependencyGraph
interface SetMultibinding {
  // contains a set of [1, 2, 3, 4]
  val ints: Set<Int>

  @Provides @IntoSet fun provideInt1() = 1

  @Provides @IntoSet fun provideInt2() = 2

  @Provides
  @ElementsIntoSet
  fun provideInts() = setOf(3, 4)
}
```

Map multibindings use `@IntoMap` and require a *map key* annotation. Map keys are any annotation annotated with `@MapKey`. Metro’s runtime includes a number of common ones like `@ClassKey` and `@StringKey`.

```kotlin
@DependencyGraph
interface MapMultibinding {
  // contains a map of {1:1, 2:2}
  val ints: Map<Int, Int>

  @Provides
  @IntoMap
  @IntKey(1)
  fun provideInt1() = 1

  @Provides
  @IntoMap
  @IntKey(2)
  fun provideInt2() = 2
}
```

Alternatively, they can be declared with an `@Multibinds`-annotated accessor property/function in a component. This member will be implemented by the Metro compiler and is useful for scenarios where the multibinding may be empty.

```kotlin
@DependencyGraph
interface MapMultibinding {
  @Multibinds(allowEmpty = true)
  val ints: Map<Int, Int>
}
```

Multibinding collections are immutable at runtime and cannot be defined as mutable at request sites.

Map multibindings support injecting *map providers*, where the value type can be wrapped in `Provider`.

```kotlin
@DependencyGraph
interface MapMultibinding {
  @Multibinds(allowEmpty = true)
  val ints: Map<Int, Provider<Int>>
}
```

Unlike Dagger, empty multibindings in Metro are a compile error by default. Empty multibindings are allowed but must be opted into via `@Multibinds(allowEmpty = true)`.

??? note "Implementation Notes"

    Metro takes inspiration from Guice in handling these in the binding graph. Since they cannot be added directly to the graph as-is (otherwise they would cause duplicate binding errors), a synthetic `@MultibindingElement` _qualifier_ annotation is generated for them at compile-time to disambiguate them. These are user-invisible but allows them to participate directly in graph validation like any other dependency. Metro then just adds these bindings as dependencies to `Binding.Multibinding` types.

## Optional Bindings

Metro supports optional bindings by leaning on Kotlin’s native support for default parameter values. These are checked at injection sites and are allowed to be missing from the dependency graph when performing a lookup at validation/code-gen time.

The below example would, since there is no `Int` binding provided, provide a message of `Count: -1`.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @Provides fun provideMessage(count: Int = -1) = "Count: $count"
}
```

To expose accessors for optional bindings, use `@OptionalBinding` on the accessor. Note that the accessor _must_ declare a default body that Metro will use if the binding is absent.

```kotlin
@DependencyGraph
interface AppGraph {
  @OptionalBinding
  val message: String
    get() = "Absent!"
}
```

There are a couple of optional configuration for Metro's optional binding support that can be configured via the `optionalBindingBehavior` Gradle DSL:

- `DISABLED` - Disallows optional bindings entirely.
- `REQUIRE_OPTIONAL_BINDING` - Requires optional binding _parameters_ to also be annotated with `@OptionalBinding`. This may be preferable for consistency with accessors and/or explicitness.
- `DEFAULT` - The default behavior as described above — accessors must be annotated with `@OptionalBinding` with default bodies and parameters just use default value expressions.

Dagger supports a similar feature via `@BindsOptionalOf`, but requires a separate declaration of this optional binding to the graph and works via `Optional<T>` types.

KI supports the same feature.

## Nullability

As nullability is a first-class concept in Kotlin, Metro supports it too. Bindings in Metro graphs may be nullable, but it's important to understand how Metro treats them!

In short, Metro will treat nullable types as different type keys than their non-nullable analogues. That is to say, `String` and `String?` are treated as distinct types in Metro.

Furthermore, a `String` binding cannot satisfy a `String?` automatically. You _may_ however `@Binds` a `String` to a `String?` and Metro will treat it as a valid binding.

```kotlin
@DependencyGraph(Unit::class)
interface ExampleGraph {
  val int: Int
  val nullableInt: Int?
  
  @Provides
  fun provideInt(): Int = 1
  
  @Binds
  val Int.bindAsNullable: Int?
}
```

This pattern can be combined with optional bindings to allow for easy conditional injection, for example with optional bindings across different source sets:
```kotlin
// in src/main
@Inject
class ExampleUseCase(
  val optionalRepository: OptionalRepository? = null
)

interface OptionalRepository

// If you want to expose it to your graph
@ContributesTo(AppScope::class)
interface OptionalRepositoryProvider {
  @OptionalDependency
  val optionalRepository: OptionalRepository? get() = null
}
```

then in the optionally included module:
```kotlin
// Dependency of the src/debug source set
@Inject
@ContributesBinding(AppScope::class) // Standard binding
// Add a nullable binding so it can satisfy the nullable injection
// in the consuming project when present on the classpath
@ContributesBinding(AppScope::class, binding = binding<OptionalRepository?>())
class OptionalRepositoryImpl : OptionalRepository
```

??? note "Implementation Notes"

    While kotlin-inject can support this by simply invoking functions with omitted arguments, Metro has to support this in generated factories.
    
    To accomplish this, Metro will slightly modify how generated provider/constructor injection factory classes look compared to Dagger. Since we are working in IR, we can copy the default value expressions from the source function/constructor to the factory’s newInstance and create() functions. This in turn allows calling generated graphs to simply omit absent binding arguments from their creation calls. This is a tried and tested pattern used by other first party plugins, namely kotlinx-serialization.
    
    There are a few cases that need to be handled here:
    
    * Expressions may reference previous parameters or instance members. To support this, we’ll transform them in IR to point at new parameters in those functions.
    * Expressions may reference private instance members. To support this, Metro factories are generated as nested classes within the source class or graph.
        * This does depart from how dagger factories work, but if we ever wanted to have some sort of interop for that we could always generate bridging factory classes in the places dagger expects later.
    * Parameters in `create()` need to be wrapped in `Provider` calls. This means that for cases where they back-reference other parameters, those will need to be transformed into `invoke()` calls on those providers too.
