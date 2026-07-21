// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/cycle/AppGraph/Impl

package parity.failures.cycle

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject

@Inject class First(val second: Second)

@Inject class Second(val first: First)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>AppGraph<!> {
  val first: First
}

// METRO_JVM_ONLY
