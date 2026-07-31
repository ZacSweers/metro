// Regression test for https://github.com/ZacSweers/metro/issues/2587.
// ENABLE_SUSPEND_PROVIDERS

var suspendProviderObjectInitialized = false
var suspendLazyObjectInitialized = false

interface SuspendProviderValue

public object SuspendProviderBackedObject : SuspendProviderValue {
  init {
    suspendProviderObjectInitialized = true
  }
}

interface SuspendLazyValue

public object SuspendLazyBackedObject : SuspendLazyValue {
  init {
    suspendLazyObjectInitialized = true
  }
}

@BindingContainer
object Bindings {
  @Provides fun provideSuspendProviderValue(): SuspendProviderValue = SuspendProviderBackedObject

  @Provides fun provideSuspendLazyValue(): SuspendLazyValue = SuspendLazyBackedObject
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val providerValue: SuspendProvider<SuspendProviderValue>
  val lazyValue: SuspendLazy<SuspendLazyValue>
}

fun box(): String {
  assertFalse(suspendProviderObjectInitialized)
  assertFalse(suspendLazyObjectInitialized)

  val graph = createGraph<AppGraph>()
  assertFalse(suspendProviderObjectInitialized)
  assertFalse(suspendLazyObjectInitialized)

  val provider = graph.providerValue
  val lazy = graph.lazyValue
  assertFalse(suspendProviderObjectInitialized)
  assertFalse(suspendLazyObjectInitialized)
  assertFalse(lazy.isInitialized())

  return runBlocking {
    val providerValue = provider()
    assertTrue(suspendProviderObjectInitialized)
    assertFalse(suspendLazyObjectInitialized)
    assertSame(SuspendProviderBackedObject, providerValue)

    val lazyValue = lazy.await()
    assertTrue(suspendLazyObjectInitialized)
    assertTrue(lazy.isInitialized())
    assertSame(SuspendLazyBackedObject, lazyValue)

    "OK"
  }
}
