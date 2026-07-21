// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package parity.failures.duplicate

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val value: String

  @Provides fun first(): String = "first"

  @Provides fun second(): String = "second"
}

// METRO_JVM_ONLY
