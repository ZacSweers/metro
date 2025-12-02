# MetroX ViewModel

ViewModel integration for Metro. This artifact provides core utilities for injecting ViewModels using Metro's dependency injection.

For Compose-specific APIs (`LocalMetroViewModelFactory`, `metroViewModel()`, etc.), see the [`metrox-viewmodel-compose`](../metrox-viewmodel-compose.md) artifact.

> Should I use this?

Well, that's up to you! This artifact is mostly for projects coming from heavy use of more vanilla Android architecture components or `hiltViewModel()` use. Modern Android apps should use higher level architectures like Circuit*, Voyager, etc. that abstract away `ViewModel` management.

*Disclosure: I am one of the authors of Circuit, and I'm a big fan of it!

## Usage

[![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.metro/metrox-viewmodel.svg)](https://central.sonatype.com/artifact/dev.zacsweers.metro/metrox-viewmodel)

```kotlin
dependencies {
  implementation("dev.zacsweers.metro:metrox-viewmodel:x.y.z")
}
```

## Core Components

### ViewModelGraph

Create a graph interface that extends `ViewModelGraph` to get multibindings for ViewModel providers:

```kotlin
@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph
```

`ViewModelGraph` includes map multibindings for:
- `viewModelProviders` - Standard ViewModel providers
- `assistedFactoryProviders` - Assisted ViewModel factory providers
- `manualAssistedFactoryProviders` - Manual assisted factory providers

It also provides a `metroViewModelFactory` property for creating ViewModels.

### MetroViewModelFactory

A `ViewModelProvider.Factory` implementation that uses injected maps to create ViewModels. Subclass it to provide your own bindings:

```kotlin
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class MyViewModelFactory(
  override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>,
  override val assistedFactoryProviders: Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>,
  override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>,
) : MetroViewModelFactory()
```

### Contributing ViewModels

Use `@ViewModelKey` with `@ContributesIntoMap` to contribute ViewModels:

```kotlin
@Inject
@ViewModelKey(HomeViewModel::class)
@ContributesIntoMap(AppScope::class)
class HomeViewModel : ViewModel() {
  // ...
}
```

### Assisted ViewModel Creation

For ViewModels requiring runtime parameters and only using `CreationParams` can use `ViewModelAssistedFactory`:

```kotlin
@AssistedInject
class DetailsViewModel(@Assisted val id: String) : ViewModel() {
  // ...

  @AssistedFactory
  @ViewModelAssistedFactoryKey(Factory::class)
  @ContributesIntoMap(AppScope::class)
  fun interface Factory : ViewModelAssistedFactory {
    override fun create(params: CreationParams): DetailsViewModel {
      return create(params.get<String>(KEY_ID))
    }

    fun create(@Assisted id: String): DetailsViewModel
  }
}
```

### Manual Assisted Injection

For full control over ViewModel creation, use `ManualViewModelAssistedFactory`:

```kotlin
@AssistedInject
class CustomViewModel(@Assisted val param1: String, @Assisted val param2: Int) : ViewModel() {
  // ...

  @AssistedFactory
  @ManualViewModelAssistedFactoryKey(Factory::class)
  @ContributesIntoMap(AppScope::class)
  interface Factory : ManualViewModelAssistedFactory {
    fun create(param1: String, param2: Int): CustomViewModel
  }
}
```

## Android Framework Integration

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
