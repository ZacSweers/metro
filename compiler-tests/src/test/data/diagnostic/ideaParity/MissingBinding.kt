// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/missing/AppGraph/Impl

package parity.failures.missing

import dev.zacsweers.metro.DependencyGraph

interface Missing

@DependencyGraph
interface AppGraph {
  val <!MISSING_BINDING!>missing<!>: Missing
}

// METRO_JVM_ONLY
