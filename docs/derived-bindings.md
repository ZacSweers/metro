# Derived Bindings

Some interfaces have one implementation for each type argument, like `Encoder<T>` or `Comparator<T>`. Their implementations often compose. For example, an `Encoder<List<User>>` wraps an `Encoder<User>`. Without derived bindings, each combination needs its own provider.

```kotlin
@Provides fun userList(element: Encoder<User>): Encoder<List<User>> = listEncoder(element)
@Provides fun teamList(element: Encoder<Team>): Encoder<List<Team>> = listEncoder(element)
// One more function for each new type or combination.
```

With derived bindings, `@Provides` and `@Binds` functions can declare type parameters. This page calls such a function a *generic provider*. When a graph requests a key that has no exact binding, Metro looks for a generic provider whose return type matches the key. Metro infers the type arguments from the key and creates a *derived binding* for it. For example, `fun <T> list(element: Encoder<T>): Encoder<List<T>>` supplies `Encoder<List<User>>` with `T = User`.

The design is described in [MEEP-3124](https://github.com/ZacSweers/metro/discussions/3124).

!!! warning "Experimental"

    Derived bindings are experimental and disabled by default. Enable them in the Metro Gradle configuration:

    ```kotlin
    @OptIn(ExperimentalMetroGradleApi::class)
    metro {
      enableDerivedBindings.set(true)
    }
    ```

    The compiler option is `enable-derived-bindings`. When the option is disabled, type parameters on `@Provides` and `@Binds` functions are an error. A graph that uses generic providers from another module also reports [`Metro/DerivedBindingsNotEnabled`](diagnostics.md#derivedbindingsnotenabled).

## Example

`JsonEncoders` holds a base encoder and the generic providers that compose encoders.

```kotlin
fun interface Encoder<T> {
  fun encode(value: T): String
}

data class User(val name: String, val nickname: String?, val tags: Map<String, List<String>>)

@BindingContainer
object JsonEncoders {
  @Provides fun string(): Encoder<String> = Encoder { "\"$it\"" }

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
    values.joinToString(",", "[", "]") { element.encode(it) }
  }

  @Provides
  fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = Encoder { value ->
    if (value == null) {
      "null"
    } else {
      inner.encode(value)
    }
  }

  @Provides
  fun <V> stringMap(value: Encoder<V>): Encoder<Map<String, V>> = Encoder { map ->
    map.entries.joinToString(",", "{", "}") { "\"${it.key}\":${value.encode(it.value)}" }
  }
}
```

Encoders for model types are ordinary injected classes. They request the composed encoders they need.

```kotlin
@ContributesBinding(AppScope::class)
@Inject
class UserEncoder(
  private val string: Encoder<String>,
  private val nickname: Encoder<String?>,
  private val tags: Encoder<Map<String, List<String>>>,
) : Encoder<User> {
  override fun encode(value: User): String =
    "{\"name\":${string.encode(value.name)}," +
      "\"nickname\":${nickname.encode(value.nickname)}," +
      "\"tags\":${tags.encode(value.tags)}}"
}

@Inject
class UserExporter(private val encoder: Encoder<List<User>>) {
  fun export(users: List<User>): String = encoder.encode(users)
}

@DependencyGraph(AppScope::class, bindingContainers = [JsonEncoders::class])
interface AppGraph {
  val exporter: UserExporter
}
```

Metro generates the equivalent of this code:

```kotlin
UserExporter(
  encoder = JsonEncoders.list(
    UserEncoder(
      string = JsonEncoders.string(),
      nickname = JsonEncoders.nullable(JsonEncoders.string()),
      tags = JsonEncoders.stringMap(JsonEncoders.list(JsonEncoders.string())),
    )
  )
)
```

## Declaring generic providers

Metro infers type arguments from the return type. So these rules apply to `@Provides` and `@Binds` functions with type parameters:

- Every type parameter must appear in the return type.
- The return type can't be a bare type parameter like `T` or `T?`. It would match every key.
- The function can't have `@IntoSet`, `@IntoMap`, or `@ElementsIntoSet`. An ordinary multibinding contribution can still depend on a derived binding.

Otherwise, generic providers work like other providers. They can have qualifiers, scopes, and context parameters. They can be `private`, unless they're `inline`. They can be `suspend` when [suspend providers](coroutines.md) are enabled. You can declare them in graphs, companion objects, binding containers, and contributed binding containers. They work across modules. A generic binding container that a graph includes with concrete type arguments, like `TypedBindings<String>`, can also declare them.

`@Binds` extension properties and `@Multibinds` declarations can't have type parameters.

## How Metro picks a binding

Metro resolves a requested key in this order:

1. An exact binding for the key wins, as before.
2. Otherwise, Metro collects the generic providers with the same raw return class and qualifier. It keeps each one whose return type matches the key and whose type parameter bounds hold.
3. If one of them is more specific than all the others, Metro uses it.
4. If two or more match and none is the most specific, Metro reports [`Metro/AmbiguousDerivedBinding`](diagnostics.md#ambiguousderivedbinding).
5. If none match, Metro looks for an `@Inject` class, as before.

Return type A is more specific than return type B when B matches A and A doesn't match B. `Encoder<List<T>>` is more specific than `Encoder<T>`, because `Encoder<T>` matches `Encoder<List<T>>`. In this example, `Encoder<List<Token>>` uses `list`, and `Encoder<Token>` uses `anything`.

```kotlin
@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}
```

Bounds also select providers. Here, `Formatter<List<Int>>` uses `number` and `Formatter<List<String>>` uses `text`.

```kotlin
@Provides fun <T : Number> number(): Formatter<List<T>> = NamedFormatter("number")

@Provides fun <T : CharSequence> text(): Formatter<List<T>> = NamedFormatter("text")
```

A bound does not make a provider more specific. If two providers have the same return type and both bounds hold, the key is ambiguous.

If only a bound rejects a provider, the missing binding error names the provider and the bound.

Matching is exact, like other Metro keys. A return type of `Encoder<T?>` matches only nullable type arguments, and `T` binds the non-null type. For example, `Encoder<String?>` matches with `T = String`. An unbounded `T` can bind a nullable type. When a return type uses both `T` and `T?`, the `T` position decides. `Pair<T, T?>` matches `Pair<String?, String?>` with `T = String?`.

After Metro picks a generic provider, the provider's parameters must resolve. If one is missing, Metro reports a missing binding. Metro doesn't try another provider, even when the requesting parameter has a default value. The error trace has a note that names the generic provider and its type arguments.

```
  note: `Encoder<List<Token>>` is derived from `Encoders.list` with `T = Token`
```

Metro resolves a generic provider's parameters like any other dependency, so a parameter can be a derived binding too. That is how `list` supplies `Encoder<List<List<Token>>>`.

## Generic `@Binds`

A generic `@Binds` function binds a generic `@Inject` class to an interface. Both the parameter form and the extension receiver form work.

```kotlin
@Inject class LruCache<T>(override val label: String) : Cache<T>

@Inject class DiskStore<T>(override val label: String) : Store<T>

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>

  @Binds fun <T> DiskStore<T>.bindStore(): Store<T>
}
```

A request for `Cache<String>` resolves to `LruCache<String>`.

## Reified providers

A generic `@Provides` function can be `inline` with `reified` type parameters. This is useful for a fallback that works for any type, because the function body can read the type.

```kotlin
@BindingContainer
object JsonEncoders {
  @Provides
  inline fun <reified T> serializable(): Encoder<T> = Encoder { "${T::class.simpleName}($it)" }

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
    values.joinToString(",", "[", "]") { element.encode(it) }
  }
}
```

In this example, `serializable` handles any `Encoder<T>` that nothing else provides. A request for `Encoder<List<Order>>` uses `list`, because its return type is more specific. `list` then gets its `Encoder<Order>` from `serializable`. If you add a provider for exactly `Encoder<User>`, Metro uses it for `Encoder<User>`.

The graph calls a reified provider directly, so the provider must be public or `@PublishedApi internal`. Any other visibility is an error.

Kotlin doesn't allow `inline` on open members, so you can't declare a reified provider on a graph interface or a binding container interface. Declare it in an object, a companion object, a final binding container class, or as a final member of an abstract class graph.

Reified providers can be scoped and can be `suspend`. The "no benefit to scoped inline providers" warning doesn't apply to them. When something needs one as a `Provider`, `() -> T`, or `Lazy`, Metro wraps the call in a lambda.

## Scoping

Once derived, a binding is like any other binding in the graph. So a scoped generic provider gives each derived binding its own instance. In this example, `Cache<String>` and `Cache<Int>` are two separate singletons.

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringCache: Cache<String>
  val intCache: Cache<Int>

  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}
```

## Graph extensions

A graph extension can use the generic providers of its ancestors. A scoped derived binding lives in the graph that owns its scope, like a scoped `@Inject` class. For example, if only a child graph requests `Cache<String>` from an `AppScope` generic provider, the instance still lives in the `AppScope` graph. All child graph instances share it.

A nearer graph can declare a generic provider with the same return type and bounds as a provider in a farther ancestor. Then the nearer one wins, like ordinary providers. If only the bounds differ, the nearer graph keeps both providers. Two equivalent generic providers on the same graph are ambiguous.

## Generic binding contributions

Generic classes can use `@ContributesBinding` when derived bindings are enabled.

```kotlin
interface Cache<T>

@ContributesBinding(AppScope::class)
@Inject
class LruCache<T> : Cache<T>

@DependencyGraph(AppScope::class)
interface AppGraph {
  val strings: Cache<String>
  val ints: Cache<Int>
}
```

Metro generates the equivalent of this contributed binding container:

```kotlin
@ContributesTo(AppScope::class)
@BindingContainer
interface LruCacheBindings {
  @Binds fun <T> bindsAsCache(instance: LruCache<T>): Cache<T>
}
```

For `Cache<String>`, Metro infers `T = String` and uses the injection factory for `LruCache<String>`.

If `generateContributionProviders` is enabled, Metro generates something like the following:

```kotlin
@ContributesTo(AppScope::class)
@BindingContainer
interface LruCacheBindings {
  @Provides fun <T> provideAsCache(): Cache<T> = LruCache<T>()
}
```

Like [generic providers](#declaring-generic-providers), every class type parameter must appear in the bound type. Generic classes can't use `@ContributesIntoSet` or `@ContributesIntoMap`.

## Dynamic graphs

In a [dynamic graph](dependency-graphs.md#dynamic-graphs), a generic provider from a dynamic binding container replaces every `@Provides` and `@Binds` binding it matches. This includes other generic providers. An exact binding from a dynamic binding container wins over it. Graph inputs, like `@Provides` parameters of a graph factory, are not replaced.

## Cycles and divergence

Recursive types create ordinary dependency cycles. A deferrable parameter like `Provider<T>`, `Lazy<T>`, or `() -> T` breaks them as usual.

```kotlin
data class Category(val name: String, val children: List<Category>)

@Inject
class CategoryEncoder(
  private val children: () -> Encoder<List<Category>>,
) : Encoder<Category> {
  override fun encode(value: Category): String = value.name + children().encode(value.children)
}
```

Some generic providers never end. For example, this provider requests a larger key each time it is applied:

```kotlin
@Provides fun <T> wrapped(inner: Encoder<List<T>>): Encoder<T> = SimpleEncoder()
```

Metro reports [`Metro/DivergentDerivedBinding`](diagnostics.md#divergentderivedbinding) when a chain applies the same provider to a key that keeps growing. It also reports this error when one chain would hold more than 64 derived bindings.

## Reporting overlaps

An *overlap* is a key that several generic providers match, where Metro picks the most specific one. By default, Metro doesn't report overlaps. To report them as [`Metro/DerivedBindingOverlap`](diagnostics.md#derivedbindingoverlap), set `derivedBindingOverlapSeverity` to `WARN` or `ERROR`:

```kotlin
@OptIn(ExperimentalMetroGradleApi::class)
metro {
  derivedBindingOverlapSeverity.set(DiagnosticSeverity.WARN)
}
```

The compiler option is `derived-binding-overlap-severity`. The default is `NONE`. This check runs only in IR, so the IDE-only severities are rejected. An exact binding that wins over a generic provider isn't an overlap.

## Reports

[Graph metadata reports](graph-analysis.md) add a `derivedFrom` object to each derived binding. It has the generic `provider` and its `typeArguments`, keyed by type parameter name. [Binding explanations](graph-analysis.md#binding-explanations) use the reason `selected_derived` for a derived binding and `less_specific` for a generic provider that lost to a more specific one.

## Limitations

- Generic multibinding contributions aren't supported.
- A graph doesn't pick up generic providers from a generic interface that it extends, like `interface AppGraph : BaseBindings<String>`. Include `BaseBindings<String>` as a binding container instead.
