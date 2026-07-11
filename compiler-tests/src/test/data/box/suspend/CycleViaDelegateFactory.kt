// ENABLE_SUSPEND_PROVIDERS

// Tests cycle-breaking with suspend bindings: a cycle between two suspend @Provides functions
// where each wraps the other in SuspendProvider. The compiler must use SuspendDelegateFactory
// (rather than DelegateFactory) for the deferred binding so the field type matches the
// SuspendProvider<T> property type.

@DependencyGraph
interface ExampleGraph {
  val firstProvider: SuspendProvider<String>

  @Provides suspend fun provideFirst(second: SuspendProvider<Int>): String = "first"

  @Provides suspend fun provideSecond(first: SuspendProvider<String>): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.firstProvider)
  return "OK"
}
