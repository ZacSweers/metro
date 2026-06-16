// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.trace.internal.testMetroTrace

object AppScope

@DependencyGraph(AppScope::class)
abstract class AppGraph {
  var count = 0

  abstract val intProvider: Provider<Int>

  @Provides @SingleIn(AppScope::class) fun provideInt(): Int = count++

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals(0, graph.intProvider())
    assertEvent(
      name = "Int",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "Int",
      kind = "Provided",
    )
    assertEquals(0, graph.intProvider())
    assertEquals(1, graph.count)
  }
  return "OK"
}
