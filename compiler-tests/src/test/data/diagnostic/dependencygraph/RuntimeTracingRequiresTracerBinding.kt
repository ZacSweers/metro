// ENABLE_RUNTIME_TRACING
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph
interface <!METRO_TRACE_ERROR!>AppGraph<!> {
  val string: String

  @Provides fun provideString(): String = "string"
}
