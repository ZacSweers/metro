// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/scopecollision/AppGraph/Impl

package parity.failures.scopecollision

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

object First {
  abstract class Scope private constructor()
}

object Second {
  abstract class Scope private constructor()
}

@SingleIn(First.Scope::class)
@Inject
class ScopedValue

@SingleIn(Second.Scope::class)
@DependencyGraph
interface <!INCOMPATIBLE_SCOPE!>AppGraph<!> {
  val value: ScopedValue
}

// METRO_JVM_ONLY
