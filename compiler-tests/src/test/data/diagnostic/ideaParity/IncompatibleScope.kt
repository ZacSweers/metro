// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/scope/AppGraph/Impl

package parity.failures.scope

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

abstract class BindingScope private constructor()

@SingleIn(BindingScope::class)
@Inject
class ScopedValue

@DependencyGraph
interface <!INCOMPATIBLE_SCOPE!>AppGraph<!> {
  val value: ScopedValue
}

// METRO_JVM_ONLY
