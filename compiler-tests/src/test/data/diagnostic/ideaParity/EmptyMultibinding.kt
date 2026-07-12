// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/empty/AppGraph/Impl

package parity.failures.empty

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds

interface Element

@BindingContainer
interface Declarations {
  @Multibinds <!EMPTY_MULTIBINDING!>fun elements(): Set<Element><!>
}

@DependencyGraph(bindingContainers = [Declarations::class])
interface AppGraph {
  val elements: Set<Element>
}

// METRO_JVM_ONLY
