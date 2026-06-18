// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

class Target {
  @Inject lateinit var string: String
}

@DependencyGraph
interface AppGraph {
  fun inject(target: Target)

  @Provides fun provideString(): String = "injected"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    val target = Target()
    graph.inject(target)
    assertEquals("injected", target.string)
    assertEvent(
      name = "Target",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Target",
      kind = "Member Injector",
    )
    assertEvent(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )
  }
  return "OK"
}
