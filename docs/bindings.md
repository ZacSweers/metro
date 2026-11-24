# Bindings

## Qualifiers

A qualifier distinguishes bindings of the same type. Define a qualifier with an annotation marked `@Qualifier`, or use Metro’s `@Named`. Apply it at both the provision and injection sites.

A binding key consists of a concrete type and its qualifier, if any.

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

Use `@Binds` to expose an implementation as a supertype.

!!! tip "For multi-module projects"
    Use [`@ContributesBinding`](aggregation.md#contributesbinding) to discover bindings across modules.

An abstract `@Binds` property must meet these conditions:

* It must be abstract.
* Its extension receiver must be a subtype of the provided type.

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

Want to keep the alias out of your public API? Make the property `private` and give it a getter that returns `this`. You still need `@Binds` so Metro knows it’s an alias.

`@Binds` declarations can also declare multibinding annotations.

```kotlin
@DependencyGraph
interface AppGraph {
  val messages: Set<Message>

  @Binds @IntoSet val MessageImpl.bind: Message
}
```

`@Binds` declarations can also appear in [binding containers](dependency-graphs.md#binding-containers).

`@Binds` functions can declare type parameters when [derived bindings](derived-bindings.md#generic-binds) are enabled.

!!! note
    kotlin-inject can express an alias with a provider getter that returns `this`. Metro reports the FIR warning `PROVIDES_COULD_BE_BINDS` for a `@Provides` extension whose body returns `this`. Use `@Binds` so Metro can resolve the alias at compile time and eliminate the runtime call. A `@Binds` extension with a body must return `this`; any other body is an error.

### Parameter-less `@Binds`

`@Binds` can also declare a constructor-injected class as an explicit binding for itself.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: MessageImpl

  @Binds fun bindMessage(): MessageImpl

  @Provides
  fun provideText(): String = "Hello, world!"
}

@Inject
class MessageImpl(val text: String)
```

This is useful when you want Metro to fail if another explicit binding for `MessageImpl` is added to the same graph.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: MessageImpl

  @Binds fun bindMessage(): MessageImpl

  // Error: duplicate binding for MessageImpl.
  @Provides fun provideMessage(): MessageImpl = MessageImpl("Hello, world!")
}

@Inject
class MessageImpl(val text: String)
```

The target must be a non-generic constructor-injected class and the `@Binds` declaration cannot have parameters, a receiver, qualifiers, scopes, or multibinding annotations. If `generateContributionProviders` hides the implementation binding, bind the contributed type instead or annotate the implementation with `@ExposeImplBinding`.

This matches Dagger 2.60+'s behavior: https://dagger.dev/dev-guide/#parameterless-binds

## Multibindings

Set and map multibindings collect bindings of a common type. Metro creates them implicitly from `@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` contributions.

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

If a custom collection has no type parameter or its type parameter differs from its element type, declare the element type explicitly on the `@ElementsIntoSet` provider.

```kotlin
class UserIds : ArrayList<Long>()

@Provides
@ElementsIntoSet
fun userIds(ids: UserIds): Set<Long> = ids.toSet()
```

You can also return `ids` directly with a declared return type of `Collection<Long>` to avoid copying it.

Map contributions use `@IntoMap` and a key annotation marked `@MapKey`. Metro includes common keys such as `@ClassKey` and `@StringKey`.

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

!!! idea
    Metro infers class-based map key values, such as `@ClassKey`, on classes and `@Binds` declarations.

Declare a multibinding explicitly with an `@Multibinds` accessor property or function. Metro implements the accessor. Use this when the collection may be empty.

```kotlin
@DependencyGraph
interface MapMultibinding {
  @Multibinds(allowEmpty = true)
  val ints: Map<Int, Int>
}
```

Multibinding collections are immutable at runtime and cannot be defined as mutable at request sites.

Map multibindings support injecting *map providers*, where the value type can be wrapped in a function (`() -> V`).

```kotlin
@DependencyGraph
interface MapMultibinding {
  @Multibinds(allowEmpty = true)
  val ints: Map<Int, () -> Int>
}
```

Unlike Dagger, Metro reports an empty multibinding as a compile error by default. Allow one explicitly with `@Multibinds(allowEmpty = true)`.

??? note "Implementation Notes"

    Following Guice’s approach, Metro gives each contribution a synthetic, user-invisible `@MultibindingElement` qualifier to prevent duplicate binding keys. Contributions then participate in graph validation as dependencies of `Binding.Multibinding`.

## Optional Bindings

A parameter with a default value can be absent from the graph. Metro uses the default expression when it cannot resolve the parameter’s binding.

This example returns `Count: -1` when the graph has no `Int` binding:

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @Provides fun provideMessage(count: Int = -1) = "Count: $count"
}
```

Annotate an optional accessor with `@OptionalBinding` and provide a default body. Metro uses that body if the binding is absent.

```kotlin
@DependencyGraph
interface AppGraph {
  @OptionalBinding
  val count: Int? get() = null
}
```

Set `optionalBindingBehavior` in the Gradle DSL to configure optional bindings:

- `DISABLED` - Disallows optional bindings entirely.
- `REQUIRE_OPTIONAL_BINDING` - Requires optional binding _parameters_ to also be annotated with `@OptionalBinding`. This may be preferable for consistency with accessors and/or explicitness.
- `DEFAULT` - The default behavior as described above — accessors must be annotated with `@OptionalBinding` with default bodies and parameters just use default value expressions.

Dagger supports a similar feature via `@BindsOptionalOf`, but requires a separate declaration of this optional binding to the graph and works via `Optional<T>` types.

KI supports the same feature.

## Nullability

Metro treats nullable and non-nullable bindings as distinct keys.

For example, `String` and `String?` are separate binding keys.

A `String` binding does not automatically satisfy `String?`. Use `@Binds` to declare the conversion:

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

Combine nullable and optional bindings for conditional injection, such as a binding supplied by an optional source set:
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

In the optional module:
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

    kotlin-inject can call a function with an argument omitted. Metro supports missing arguments in its generated provider and constructor factories.
    
    In IR, Metro copies default expressions from the source provider or constructor into the factory’s `newInstance` and `create()` functions. Generated graphs can then omit arguments for missing bindings. kotlinx-serialization uses a similar compiler-plugin pattern.
    
    Metro handles these cases:
    
    * References to earlier parameters or instance members are remapped to the generated function’s parameters.
    * Factories are nested in the source class or graph so expressions can access private members. Dagger expects its factories outside the source class. If we need that layout, we could generate bridge classes there.
    * In `create()`, parameters are wrapped in `Provider` values. References to earlier parameters must be transformed into `invoke()` calls on those providers.
