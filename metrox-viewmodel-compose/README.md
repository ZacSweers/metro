# MetroX ViewModel Compose

Compose integration for MetroX ViewModel. This artifact provides Compose-specific utilities for injecting ViewModels.

> Should I use this?

Well, that's up to you! This artifact is mostly for projects coming from heavy use of more vanilla Android architecture components or `hiltViewModel()` use. Modern Android apps should use higher level architectures like Circuit*, Voyager, etc. that abstract away `ViewModel` management.

*Disclosure: I am one of the authors of Circuit, and I'm a big fan of it!

## Usage

[![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.metro/metrox-viewmodel-compose.svg)](https://central.sonatype.com/artifact/dev.zacsweers.metro/metrox-viewmodel-compose)

```kotlin
dependencies {
  implementation("dev.zacsweers.metro:metrox-viewmodel-compose:x.y.z")
}
```

This artifact depends on `metrox-viewmodel` transitively.

## Setup

### 1. Set up your graph

Set up your dependency graph and ViewModels for injection using [`metrox-viewmodel`](metrox-viewmodel.md#core-components).

### 2. Provide LocalMetroViewModelFactory

At the root of your Compose hierarchy, provide the factory via `CompositionLocalProvider`:

```kotlin
@Composable
fun App(metroVmf: MetroViewModelFactory) {
  CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
    // Your app content
  }
}
```

On Android, inject the factory into your Activity:

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

## Using ViewModels

### Standard ViewModels

Use `metroViewModel()` to retrieve injected ViewModels:

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel = metroViewModel()) {
  // ...
}
```

### Assisted ViewModels

For ViewModels with `ViewModelAssistedFactory`:

```kotlin
@Composable
fun DetailsScreen(
  data: String,
  viewModel: DetailsViewModel = assistedMetroViewModel()
) {
  // ...
}
```

### Manual Assisted ViewModels

For ViewModels with `ManualViewModelAssistedFactory`:

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
