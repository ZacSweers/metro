// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/suspend/cycle/AppGraph/Impl
// ENABLE_SUSPEND_PROVIDERS

package parity.suspend.cycle

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

@Inject class A(val b: B)

class B

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>AppGraph<!> {
  val a: A

  @Provides suspend fun provideB(a: A): B = B()
}

// METRO_JVM_ONLY
