// Regression test for https://github.com/ZacSweers/metro/issues/2587.
// Public object values are inlineable, but wrapping them in Provider or Lazy must not initialize
// the object until the wrapper is evaluated.

var providerObjectInitialized = false
var lazyObjectInitialized = false

interface ProviderValue

public object ProviderBackedObject : ProviderValue {
  init {
    providerObjectInitialized = true
  }
}

interface LazyValue

public object LazyBackedObject : LazyValue {
  init {
    lazyObjectInitialized = true
  }
}

@BindingContainer
object Bindings {
  @Provides fun provideProviderValue(): ProviderValue = ProviderBackedObject

  @Provides fun provideLazyValue(): LazyValue = LazyBackedObject
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val providerValue: Provider<ProviderValue>
  val lazyValue: Lazy<LazyValue>
}

fun box(): String {
  assertFalse(providerObjectInitialized)
  assertFalse(lazyObjectInitialized)

  val graph = createGraph<AppGraph>()
  assertFalse(providerObjectInitialized)
  assertFalse(lazyObjectInitialized)

  val provider = graph.providerValue
  assertFalse(providerObjectInitialized)
  assertFalse(lazyObjectInitialized)

  val lazy = graph.lazyValue
  assertFalse(providerObjectInitialized)
  assertFalse(lazyObjectInitialized)

  val providerValue = provider()
  assertTrue(providerObjectInitialized)
  assertFalse(lazyObjectInitialized)
  assertSame(ProviderBackedObject, providerValue)

  val lazyValue = lazy.value
  assertTrue(lazyObjectInitialized)
  assertSame(LazyBackedObject, lazyValue)

  return "OK"
}
