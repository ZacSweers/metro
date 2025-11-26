# MetroX ViewModel

Compose ViewModel integration for Metro. This artifact provides utilities for injecting ViewModels in Compose
applications using Metro's dependency injection.

## Usage

[![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.metro/metrox-viewmodel.svg)](https://central.sonatype.com/artifact/dev.zacsweers.metro/metrox-viewmodel)

```kotlin
dependencies {
  implementation("dev.zacsweers.metro:metrox-viewmodel:x.y.z")
}
```

There are two primary approaches to using this library:

1. **Standard Usage**: Uses `metroViewModel()` and `assistedMetroViewModel()` functions that work entirely within the
   native ViewModel APIs via `ViewModelProvider.Factory`.
2. **Advanced Usage**: Uses `ManualViewModelAssistedFactory` for cases requiring more control over ViewModel creation.

## Standard Usage

This is the recommended approach for most use cases. It integrates natively with Compose's `viewModel()` function and
the standard `ViewModelProvider.Factory` API.

### 1. Set up your graph with ViewModelGraph

Create a graph interface that extends `ViewModelGraph`:

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph
```

`ViewModelGraph` includes map multibindings for the common ViewModel providers and provides a `metroViewModelFactory`
property that you'll use to create ViewModels.

### 2. Contribute ViewModels to the graph

Use the `@ViewModelKey` annotation with `@ContributesIntoMap` to contribute ViewModels via multibinding:

```kotlin
@Inject
@ViewModelKey(HomeViewModel::class)
@ContributesIntoMap(AppScope::class)
class HomeViewModel : ViewModel() {
  // ...
}
```

### 3. Provide the LocalMetroViewModelFactory in Compose

At the root of your Compose hierarchy, provide the factory via `CompositionLocalProvider`:

```kotlin
@Composable
fun App(metroVmf: MetroViewModelFactory) {
  CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
    // Your app content
  }
}
```

On Android, you can inject the factory into your Activity:

```kotlin
@Inject
class MainActivity(private val metroVmf: MetroViewModelFactory) : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
        App()
      }
    }
  }
}
```

### 4. Use ViewModels in Composables

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel = metroViewModel()) {
  // ...
}
```

## Assisted Injection (Standard Route)

For ViewModels that require runtime parameters, use assisted injection with `ViewModelAssistedFactory`. This approach
uses `CreationExtras` to pass parameters, integrating with the native ViewModel creation APIs.

### 1. Create an assisted ViewModel with a factory

```kotlin
@AssistedInject
class DetailsViewModel(@Assisted val data: String) : ViewModel() {
  // ...

  @AssistedFactory
  @ViewModelAssistedFactoryKey(Factory::class)
  @ContributesIntoMap(ViewModelScope::class, binding<ViewModelAssistedFactory<*>>())
  fun interface Factory : ViewModelAssistedFactory<DetailsViewModel> {
    fun create(@Assisted data: String): DetailsViewModel
  }
}
```

The factory must extend `ViewModelAssistedFactory<VM>`, which provides integration with `CreationExtras`.

### 2. Use in Composables

```kotlin
@Composable
fun DetailsScreen(
  data: String,
  viewModel: DetailsViewModel = assistedMetroViewModel()
) {
  // ...
}
```

## Advanced Route: Manual Assisted Injection

For cases requiring more control over ViewModel creation (such as custom factory patterns or non-Compose contexts), you
can use `ManualViewModelAssistedFactory`.

### 1. Define a manual assisted factory

```kotlin
@AssistedInject
class CustomViewModel @AssistedInject constructor(@Assisted val param1: String, @Assisted val param2: Int) :
  ViewModel() {
  // ...
  @AssistedFactory
  @ManualViewModelAssistedFactoryKey(CustomViewModel.Factory::class)
  @ContributesIntoMap(AppScope::class, binding<ManualViewModelAssistedFactory<*>>())
  interface Factory : ManualViewModelAssistedFactory<CustomViewModel> {
    fun create(param1: String, param2: Int): CustomViewModel
  }
}
```

Unlike `ViewModelAssistedFactory`, `ManualViewModelAssistedFactory` doesn't receive `CreationExtras` automatically - you
have full control over what parameters are passed.

### 2. Use in Composables

```kotlin
@Composable
fun CustomScreen(
  viewModel: CustomViewModel = assistedMetroViewModel<CustomViewModel, CustomViewModel.Factory> {
    create("param1", 42)
  }
) {
  // ...
}
```

## Android Framework Integrations

```kotlin
// Activity
@Inject
class ExampleActivity(private val viewModelFactory: MyViewModelFactory) : ComponentActivity() {
  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = viewModelFactory
}

// Fragment
@Inject
class ExampleFragment(private val viewModelFactory: MyViewModelFactory) : Fragment() {
  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = viewModelFactory
}
```
