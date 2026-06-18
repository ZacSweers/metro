// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

interface Service {
  fun value(): String
}

class RealService @Inject constructor() : Service {
  override fun value(): String = "real"
}

@DependencyGraph
interface AppGraph {
  val service: Service

  @Binds fun bindService(realService: RealService): Service

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals("real", graph.service.value())
    assertEvent(
      name = "Service",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "Service",
      kind = "Accessor",
    )
    assertEvent(
      name = "RealService",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "RealService",
      kind = "ConstructorInjected",
    )
  }
  return "OK"
}
