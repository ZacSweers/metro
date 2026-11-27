// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/qualifiedrequest/AppGraph/Impl

package parity.failures.qualifiedrequest

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Chosen

@Inject class Foo

@DependencyGraph
interface AppGraph {
  @Chosen val <!MISSING_BINDING!>foo<!>: Foo
}

// METRO_JVM_ONLY
