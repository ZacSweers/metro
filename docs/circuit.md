# Circuit Integration

Metro includes built-in code generation for [Circuit](https://slackhq.github.io/circuit/), a Compose-first architecture for Kotlin apps.

When enabled, Metro generates and contributes:

- `Presenter.Factory` and `Ui.Factory` implementations for `@CircuitInject`
- `SubPresenterFactory` and `SubUiFactory` implementations for `@SubCircuitInject`
- `CircuitSerializerRegistration` implementations for `@CircuitSerializable` with Circuit 0.36.0 or newer

Generation runs inside Metro's compiler plugin instead of Circuit's KSP code generator.

## Setup

Enable Circuit codegen in your Gradle build:

```kotlin
metro {
  enableCircuitCodegen.set(true)
}
```

This requires the relevant Circuit runtime libraries on your classpath. The `circuit-runtime-presenter` and `circuit-runtime-ui` artifacts are optional — you can use presenter-only or UI-only modules. To generate SubCircuit factories, add `com.slack.circuit:circuitx-subcircuit:<version>`. Enabling this option also adds the `circuit-codegen-annotations` artifact to your implementation classpath.

Serialization registration requires Circuit 0.36.0 or newer, the kotlinx-serialization compiler plugin, and an explicit dependency on `com.slack.circuit:circuit-serialization:<version>`. Metro does not add this optional runtime automatically.

Use **either** Metro's native code generator or Circuit's KSP code generator for a declaration but **NOT** both. Running both for the same `@CircuitInject`, `@SubCircuitInject`, or `@CircuitSerializable` declarations attempts to generate the same types twice.

This is only compatible with Kotlin 2.3.20+ as it requires support for generating top-level declarations in FIR.

This will likely eventually move to a separate artifact.

## Usage

### Class-based Presenters and UIs

Annotate your `Presenter` or `Ui` implementation with `@CircuitInject`:

```kotlin
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
class HomePresenter(
  private val repository: UserRepository,
) : Presenter<HomeState> {
  @Composable
  override fun present(): HomeState {
    // ...
  }
}
```

Metro generates a `Presenter.Factory` (or `Ui.Factory`) that:

- Is annotated with `@Inject` and `@ContributesIntoSet(scope)`
- Has a constructor that accepts a `() -> HomePresenter` function
- Implements `create()` with screen matching and delegation to the provider

### Function-based Presenters and UIs

Annotate a top-level `@Composable` function:

```kotlin
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
@Composable
fun HomePresenter(
  screen: HomeScreen,      // Circuit-provided
  navigator: Navigator,    // Circuit-provided
  repository: UserRepository,  // Injected as () -> UserRepository
): HomeState {
  // ...
}
```

Metro generates a factory class whose constructor accepts provider-wrapped parameters (`() -> T`) for injected dependencies. At `create()` time, providers are invoked _once_ (outside the composition) and passed to the function body along with any Circuit-provided parameters.

**UI functions** return `Unit` and must have a `Modifier` parameter:

```kotlin
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
@Composable
fun HomeUi(
  state: HomeState,        // Circuit-provided
  modifier: Modifier,      // Circuit-provided
  analytics: Analytics,    // Injected
) {
  // ...
}
```

### Assisted Injection

For presenters/UIs that need assisted injection (e.g., receiving a `Navigator` as an assisted parameter):

```kotlin
@AssistedInject
class FavoritesPresenter(
  @Assisted private val navigator: Navigator,
  private val repository: FavoritesRepository,
) : Presenter<FavoritesState> {

  @CircuitInject(FavoritesScreen::class, AppScope::class)
  @AssistedFactory
  fun interface Factory {
    fun create(@Assisted navigator: Navigator): FavoritesPresenter
  }

  @Composable
  override fun present(): FavoritesState { /* ... */ }
}
```

The generated Circuit factory automatically bridges Circuit's `Presenter.Factory.create(screen, navigator, context)` to your `@AssistedFactory`'s `create()` method by matching parameters by name. In the example above, `navigator` from Circuit's `create()` is passed through to `Factory.create(navigator)` automatically.

**Important:**

- The `@CircuitInject` annotation goes on the `@AssistedFactory` interface, not the class itself.
- The `@AssistedFactory` must be nested inside the target `Presenter`/`Ui` class.
- The assisted parameters on your factory's `create()` method must be [circuit-provided parameters](#circuit-provided-parameters) (e.g., `Navigator`, `Screen`). Custom assisted parameters that aren't circuit-provided types are not supported — the generated factory has no way to obtain them at runtime since only Circuit's `create()` parameters are available.

## SubCircuit

The same `enableCircuitCodegen` option supports SubCircuit's `@SubCircuitInject` annotation. Generated factories are contributed into `Set<SubPresenterFactory>` or `Set<SubUiFactory>`.

### SubPresenter and SubUi Classes

Direct class targets must be annotated with `@Inject` and implement `SubPresenter` or `SubUi`:

```kotlin
@SubCircuitInject(ProfileCardScreen::class, AppScope::class)
@Inject
class ProfileCardPresenter(
  private val repository: UserRepository,
) : SubPresenter<ProfileCardEvent, ProfileCardState> {
  @Composable
  override fun present(
    outerEventSink: (ProfileCardEvent) -> Unit,
  ): ProfileCardState {
    // ...
  }
}
```

Assisted presenters and UIs use a nested factory, with `@SubCircuitInject` on the factory interface:

```kotlin
@AssistedInject
class ProfileCardPresenter(
  @Assisted private val screen: ProfileCardScreen,
  private val repository: UserRepository,
) : SubPresenter<ProfileCardEvent, ProfileCardState> {

  @SubCircuitInject(ProfileCardScreen::class, AppScope::class)
  @AssistedFactory
  fun interface Factory {
    fun create(screen: ProfileCardScreen): ProfileCardPresenter
  }

  // ...
}
```

`SubPresenterFactory.create()` and `SubUiFactory.create()` receive only a `SubScreen`. Metro forwards a matching screen to an assisted factory and returns `null` for non-matching screens. Other assisted parameters are not supported. The `@AssistedFactory` must be nested inside its target class.

### SubUi Functions

Top-level `@SubCircuitInject` functions generate `SubUiFactory` implementations. A `Modifier` parameter is required, the `SubCircuitUiState` parameter is optional, and other parameters are injected once per `create()` call:

```kotlin
@SubCircuitInject(ProfileCardScreen::class, AppScope::class)
@Composable
fun ProfileCardUi(
  state: ProfileCardState,
  analytics: Analytics,
  modifier: Modifier = Modifier,
) {
  // ...
}
```

SubPresenter functions are not supported.

## CircuitSerializable support

Annotate a concrete `Screen` or `PopResult` with `@CircuitSerializable(scope)` to contribute its serializer to `Set<CircuitSerializerRegistration>`:

```kotlin
@CircuitSerializable(AppScope::class)
data class DetailScreen(val itemId: Long) : Screen
```

`@CircuitSerializable` uses the default serializer generated by kotlinx-serialization. Add `@Serializable(with = CustomSerializer::class)` only when the type uses a custom serializer. Metro generates a public no-argument class equivalent to:

```kotlin
@Inject
@ContributesIntoSet(AppScope::class)
class DetailScreenCircuitSerializerRegistration : CircuitSerializerRegistration {
  override fun register(builder: PolymorphicModuleBuilder<CircuitSaveable>) {
    builder.subclass(DetailScreen::class, DetailScreen.serializer())
  }
}
```

Declare the registration multibinding in your application graph and provide the saver explicitly:

```kotlin
@BindingContainer
interface CircuitSerializationBindings {
  @Multibinds(allowEmpty = true)
  fun registrations(): Set<CircuitSerializerRegistration>
}

@DependencyGraph(
  AppScope::class,
  bindingContainers = [CircuitSerializationBindings::class],
)
interface AppGraph {
  val circuitSaver: CircuitSaver

  @Provides
  fun provideCircuitSaver(
    registrations: Set<CircuitSerializerRegistration>,
  ): CircuitSaver = SerializableCircuitSaver(registrations)
}
```

Metro does not generate the `CircuitSaver` provider. `SerializableCircuitSaver` combines the generated registrations against the `CircuitSaveable` base class. Android `Screen` and `PopResult` implementations must still satisfy Circuit's `Parcelable` requirement.

For expect/actual types, apply `@CircuitSerializable` with the same scope to the expect declaration and every actual declaration. Metro generates one registration from the expect declaration and skips actual declarations. It never falls back to generating from an actual declaration.

## Circuit-Provided Parameters

Some parameter types are provided by Circuit at runtime and should not be injected:

| Parameter Type                  | Available To   |
|---------------------------------|----------------|
| `Screen` (and subtypes)         | Presenter, UI  |
| `Navigator`                     | Presenter only |
| `CircuitUiState` (and subtypes) | UI only        |
| `Modifier`                      | UI only        |

All other parameter types are treated as injected dependencies and wrapped in `() -> T` on the generated factory's constructor.

Parameters already wrapped in `() -> T`, `Provider<T>`, `Lazy<T>`, or function types are passed through as-is without additional wrapping.

**`CircuitContext`** is intentionally excluded from the circuit-provided set. It is a factory-level concept and should not be accepted by presenters or UIs.

## Validation

The compiler plugin validates `@CircuitInject` and `@SubCircuitInject` usage for common usage errors.

## Notes

- **Top-level `@AssistedFactory` with `@CircuitInject` or `@SubCircuitInject`** is not supported — the factory must be nested inside the target class. This is enforced by the compiler.
- **`expect` declarations** with `@CircuitInject` or `@SubCircuitInject` are skipped. Only `actual` declarations are processed. You must annotate the `actual` declaration too. Kotlinc requires this symmetry as well.
- **`expect` declarations** with `@CircuitSerializable` own serialization registration generation. Apply the annotation with the same scope to the expect declaration and every actual declaration; Metro skips each actual declaration.
