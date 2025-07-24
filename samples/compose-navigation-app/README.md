# Compose Navigation Sample

This is an Android sample app that demonstrates using Metro to inject `ViewModel` including
when used with Compose Navigation.

## ViewModel

The sample demonstrates how to handle Metro DI injection of a ViewModel.

```kotlin
@Composable
fun CounterScreen(onNavigate: (Any) -> Unit) {
  val viewModel = metroViewModel<CounterViewModel>()
}
```

```kotlin
@ContributesIntoMap(ViewModelScope::class)
@ViewModelKey(CounterViewModel::class)
@Inject
class CounterViewModel(savedStateHandle: SavedStateHandle, viewModelCounter: AtomicInt) :
  ViewModel() {
}
```

It uses a `ViewModelProvider.Factory` that is exposed via the Activity.

```kotlin
@Inject
class MainActivity(private val viewModelFactory: ViewModelProvider.Factory) : ComponentActivity() {
    
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = viewModelFactory
}
```

```kotlin
@ContributesBinding(AppScope::class)
@Inject
class MetroViewModelFactory(val appGraph: AppGraph) : ViewModelProvider.Factory {
```

And also supports AssistedInjection.

```kotlin
@Composable
fun AssistedCounterScreen(onNavigate: (Any) -> Unit) {
    val viewModel = metroViewModel<AssistedCounterViewModel> { assistedCounterFactory.create(10) }
}
```

with registration of the Factory in the `ViewModelGraph`

```kotlin
@Inject
class AssistedCounterViewModel(
  @Assisted val initialValue: Int,
  savedStateHandle: SavedStateHandle,
  viewModelCounter: AtomicInt,
) : ViewModel() {

  @AssistedFactory
  fun interface Factory {
    fun create(initialValue: Int): AssistedCounterViewModel
  }
}
```

## Android AppComponentFactory

The `MetroAppComponentFactory` demonstrates how to handle Metro DI injection of any of Activity, BroadcastReceiver,
Service or ContentProvider.

```kotlin
class MetroAppComponentFactory : AppComponentFactory() {
    override fun instantiateApplicationCompat(cl: ClassLoader, className: String): Application {
        val app = super.instantiateApplicationCompat(cl, className)
        activityProviders = (app as MetroApp).appGraph.activityProviders
        return app
    }
    
    override fun instantiateActivityCompat(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        return getInstance(cl, className, activityProviders)
            ?: super.instantiateActivityCompat(cl, className, intent)
    }

    override fun instantiateReceiverCompat(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): BroadcastReceiver = TODO("Not currently used")

    override fun instantiateServiceCompat(cl: ClassLoader, className: String, intent: Intent?): Service =
        TODO("Not currently used")

    override fun instantiateProviderCompat(cl: ClassLoader, className: String): ContentProvider =
        TODO("Not currently used")
}
```

It must be registered in the `AndroidManifest.xml`.

```xml
  <application
    android:name=".MetroApp"
    android:appComponentFactory="dev.zacsweers.metro.sample.androidviewmodel.components.MetroAppComponentFactory" />
```

