import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
interface GenericScopedGraph<T> {
  val scopedValue: Int

  @SingleIn(AppScope::class) @Provides fun provideScopedValue(): Int = 7
}

fun box(): String {
  val graph = createGraph<GenericScopedGraph<Int>>()
  assertEquals(7, graph.scopedValue)
  return "OK"
}
