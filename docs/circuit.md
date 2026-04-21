# Circuit Integration

Metro includes built-in support for [Circuit](https://slackhq.github.io/circuit/), a Compose-first architecture for building kotlin apps. This integration generates `Presenter.Factory` and `Ui.Factory` implementations from `@CircuitInject`-annotated classes and functions, similar to Circuit's existing KSP code generator but running entirely within Metro's compiler plugin. These factories then contribute into `Set<Presenter.Factory>` and `Set<Presenter.Factory>` multibindings.

## Setup

Enable Circuit codegen in your Gradle build:

```kotlin
metro {
  enableCircuitCodegen.set(true)
}
```

This requires the Circuit runtime libraries on your classpath. The `circuit-runtime-presenter` and `circuit-runtime-ui` artifacts are optional — you can use presenter-only or UI-only modules. This will also add the `circuit-codegen-annotations` artifact to your implementation classpath.

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

The compiler plugin validates `@CircuitInject` usage for common usage errors.

## Notes

- **Top-level `@AssistedFactory` with `@CircuitInject`** is not supported — the factory must be nested inside the target `Presenter`/`Ui` class. This is enforced by the compiler.
- **`expect` declarations** with `@CircuitInject` are skipped. Only `actual` declarations are processed. You must annotate the `actual` declaration (too). kotlinc requires this symmetry as well.
