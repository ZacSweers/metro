// ENABLE_RUNTIME_TRACING
// RENDER_DIAGNOSTICS_FULL_TEXT

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun <!METRO_TRACE_ERROR!>create<!>(@Provides string: String): AppGraph
  }
}
