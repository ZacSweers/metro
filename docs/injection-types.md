# Injection Types

Metro supports multiple common injection types.

## Constructor Injection

Most types should use constructor injection if possible. For this case, you can annotate either a class itself (if it has exactly one, primary constructor) or exactly one specific constructor.

```kotlin
@Inject
class ClassInjected

class SpecificConstructorInjection(val text: String) {
  @Inject constructor(value: Int) : this(value.toString())
}
```

Constructor-injected classes can be instantiated+managed entirely by Metro and encourages immutability.

## Assisted Injection

For types that require dynamic dependencies at instantiation, assisted injection can be used to supply these inputs. In this case \- an injected constructor (or class with one constructor) must be annotated with `@Inject`, assisted parameters annotated with `@Assisted`, and a factory interface or abstract class with one single abstract function that accepts these assisted parameters and returns the target class.

```kotlin
@Inject
class HttpClient(
  @Assisted val timeout: Duration,
  val cache: Cache
) {
  @AssistedFactory
  fun interface Factory {
    fun create(timeout: Duration): HttpClient
  }
}
```

Then, the `@AssistedFactory`\-annotated type can be accessed from the dependency graph.

```kotlin
@Inject
class ApiClient(httpClientFactory: HttpClient.Factory) {
  private val httpClient = httpClientFactory.create(30.seconds)
}
```

Like Dagger, the `@Assisted` parameters can take optional `value` keys to disambiguate matching types.

```kotlin
@Inject
class HttpClient(
  @Assisted("connect") val connectTimeout: Duration,
  @Assisted("request") val requestTimeout: Duration,
  val cache: Cache
) {
  @AssistedFactory
  fun interface Factory {
    fun create(
      @Assisted("connect") connectTimeout: Duration,
      @Assisted("request") requestTimeout: Duration,
    ): HttpClient
  }
}
```

### Automatic Assisted Factory Generation

As of [this PR](https://github.com/ZacSweers/lattice/pull/60), there is a feature (behind a compiler option) to let Metro *automatically* generate assisted factory interfaces for `@Inject`\-annotated constructors that contain `@Assisted`\-annotated parameters. This should save boilerplate in most cases, but may not be suitable for all cases. Cases where you may not want this include:

1. You want assisted factories to conform to a common interface.
2. Likely in combination with \#1, you may want to contribute these factories to multibindings of some sort and thus need to annotate them with `@IntoSet`, etc.

## Member Injection

Metro supports *member injection* to inject mutable properties or functions post-construction or into existing class instances.

This can be useful for classes that cannot be constructor-injected, for example Android Activity classes (on older SDK versions) as well as constructor-injected classes that perhaps don’t want or need to expose certain types directly in their constructors.

!!! tip
    Unlike Dagger and kotlin-inject, injected members in Metro can be `private`.

!!! note
    Member function injection does not (currently) support default values.

```kotlin
class ProfileActivity : Activity() {
  // Property injection
  @Inject private lateinit var db: UserDatabase

  @Inject private var notifications: Notifications? = null

  // Function injection
  @Inject private fun injectUser(user: User) {
    // ...
  }
}
```

Like Dagger, these classes can be injected via multiple avenues.

### 1. In constructor-injected types, `@Inject`\-annotated members are injected *automatically*.

```kotlin
// Injection with constructor injection
@Inject
class ProfileInjector(
  // ...
) {
  // Automatically injected during constructor injection
  @Inject private fun injectUser(value: String) {
    // ...
  }
}
```

In these cases, Metro will automatically inject these members automatically and immediately after instantiation during constructor injection.

### 2. Exposing a `fun inject(target: ProfileActivity)` function on the graph

```kotlin
// Graph inject() functions
@DependencyGraph
interface AppGraph {
  // ...

  fun inject(target: ProfileActivity)
}

// Somewhere else
val graph = createGraph<AppGraph>()
graph.inject(profileActivity)
```

With this option, you can call `graph.inject(target)` on the instance with members you wish to inject.

### 3. Requesting a `MembersInjector` instance from the dependency graph.

```kotlin
// Injection with MembersInjector
@Inject
class ProfileInjector(
  private val injector: MembersInjector<ProfileActivity>
) {
  fun performInjection(activity: ProfileActivity) {
    injector.inject(activity)
  }
}
```

Like Dagger, option #3 is accomplished via `MembersInjector` interface at runtime and in code gen. This should be reserved for advanced use cases.

### Implementation notes

* Property accessors don’t use `get`/`set` names in `inject{name}()` function names.
* MembersInjector classes are generated as nested classes, allowing private member access.
    * This includes parent classes’ private members (!!)
* Optional bindings are not supported for injected member functions currently, but may be possible in the future.

## Top-level Function Injection

Like KI, Metro supports top-level function injection. The primary use case for this is composable functions and standalone applications that run from `main` functions.

```kotlin
@Inject
fun App(message: String) {
  // ...
}
```

To do this, Metro’s FIR plugin will generate a concrete type that acts as a bridge for this function.

```kotlin
@Inject
class AppClass(
  private val message: Provider<String>
) {
  operator fun invoke() {
    App(message())
  }
}
```

Because it’s generated in FIR, this type will be user-visible in the IDE and can then be referenced in a graph.

*Note that this feature requires enabling third party FIR plugins in the IDE to fully work. It will compile without it, but generated wrapper classes will be red/missing in the IDE.*

!!! note
    The generated class is called `<function name>` \+ `Class` because of a limitation in the Kotlin compiler. TODO Link issue?

```kotlin
@DependencyGraph
interface AppGraph {
  val app: AppClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(message: String): AppGraph
  }
}

// Usage
val app = createGraphFactory<AppGraph.Factory>()
  .create("Hello, world!")
  .app

// Run the app
app()
```

To add assisted parameters, use `@Assisted` on the parameters in the function description. These will be propagated accordingly.

```kotlin
@Inject
fun App(@Assisted message: String) {
  // ...
}

// Generates...
@Inject
class AppClass {
  operator fun invoke(message: String) {
    App(message)
  }
}

// Usage
val app = createGraph<AppGraph>()
  .app

// Run the app
app("Hello, world!")
```

This is particularly useful for Compose, and `@Composable` functions will be copied over accordingly.

```kotlin
@Inject
@Composable
fun App(@Assisted message: String) {
  // ...
}

// Generates...
@Inject
class AppClass {
  @Composable
  operator fun invoke(message: String) {
    App(message)
  }
}

// Usage
val App = createGraph<AppGraph>()
  .app

// Call it in composition
setContent {
  App("Hello, world!")
}
```

Similarly, if the injected function is a `suspend` function, the `suspend` keyword will be ported to the generated `invoke()` function too.

### Implementation notes

This is fairly different from kotlin-inject’s typealias approach. This is necessary because Metro doesn’t use higher order function types or typealiases as qualifiers.
