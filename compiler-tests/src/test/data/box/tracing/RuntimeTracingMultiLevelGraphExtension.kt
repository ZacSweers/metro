// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.trace.internal.testMetroTrace

@Scope annotation class AppScope

@Scope annotation class ChildScope

@Scope annotation class GrandchildScope

@DependencyGraph(AppScope::class)
interface AppGraph {
  fun createChild(): ChildGraph

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun createGrandchild(): GrandchildGraph
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val int: Int

  @SingleIn(GrandchildScope::class) @Provides fun provideInt(): Int = 5
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    val child = graph.createChild()
    val grandchild = child.createGrandchild()
    assertEquals(5, grandchild.int)
    assertEvent(
      name = "ChildGraph",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "ChildGraph",
      kind = "Accessor",
    )
    assertEvent(
      name = "GrandchildGraph",
      graph = "ChildGraph",
      path = "AppGraph/ChildGraph",
      binding = "GrandchildGraph",
      kind = "Accessor",
    )
    assertEvent(
      name = "Int",
      graph = "GrandchildGraph",
      path = "AppGraph/ChildGraph/GrandchildGraph",
      binding = "Int",
      kind = "Accessor",
    )
    assertEvent(
      name = "Int",
      graph = "GrandchildGraph",
      path = "AppGraph/ChildGraph/GrandchildGraph",
      binding = "Int",
      kind = "Provided",
    )
  }
  return "OK"
}
