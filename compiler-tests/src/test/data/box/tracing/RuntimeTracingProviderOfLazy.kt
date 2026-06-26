// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface AppGraph {
  val lazyStringProvider: Provider<Lazy<String>>

  @Provides fun provideString(): String = "lazy"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    val lazyString = graph.lazyStringProvider()
    assertEquals("lazy", lazyString.value)
    assertInstant(
      name = "AppGraph.lazyStringProvider",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "lazyStringProvider",
      type = "String",
      contextualType = "Provider<Lazy<String>>",
      kind = "Accessor",
    )
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )
  }
  return "OK"
}
