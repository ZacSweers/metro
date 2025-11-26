# Compose ViewModels Sample

This sample demonstrates how to use Metro for ViewModel injection in a multi-module Compose Multiplatform app with Compose Navigation.

## Module Structure

```
compose-viewmodels/
├── app/            # Multiplatform app module
├── core/           # Shared ViewModel infrastructure
├── screen-home/    # Home screen with standard ViewModel injection
└── screen-details/ # Details screen with assisted ViewModel injection
```

## Running

- **Android**: `./gradlew -p samples :compose-viewmodels:app:installDebug`
- **Desktop**: `./gradlew -p samples :compose-viewmodels:app:run`

## Key Concepts

### Standard ViewModel Injection

ViewModels are contributed to a multibinding map using `@ContributesIntoMap` and `@ViewModelKey`:

```kotlin
@Inject
@ViewModelKey(HomeViewModel::class)
@ContributesIntoMap(ViewModelScope::class)
class HomeViewModel : ViewModel() {
  // ...
}
```

Then injected via Compose:

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel = metroViewModel()) {
  // ...
}
```

### Assisted ViewModel Injection

For ViewModels that need runtime parameters, use assisted injection:

```kotlin
@AssistedInject
class DetailsViewModel(@Assisted val data: String) : ViewModel() {
  // ...

  @AssistedFactory
  @AssistedFactoryKey(Factory::class)
  @ContributesIntoMap(ViewModelScope::class)
  fun interface Factory : ViewModelAssistedFactory {
    fun create(@Assisted data: String): DetailsViewModel
  }
}
```

Then injected via Compose:

```kotlin
@Composable
fun DetailsScreen(
  data: String,
  viewModel: DetailsViewModel =
    assistedMetroViewModel<DetailsViewModel, DetailsViewModel.Factory> { create(data) },
) {
  // ...
}
```

### Graph Structure

- `AppGraph` - App-scoped graph that provides the `ViewModelGraphProvider`
- `ViewModelGraph` - ViewModel-scoped graph that contains the multibindings for ViewModels
- `ViewModelGraphProvider` - Factory for creating `ViewModelGraph` instances, exposed via `LocalViewModelGraphProvider` CompositionLocal
