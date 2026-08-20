// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/emptysuppressed/AppGraph/Impl

package parity.failures.emptysuppressed

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds

interface Element

interface Unbound

@BindingContainer
interface Declarations {
  @Multibinds fun elements(): Set<Element>
}

@DependencyGraph(bindingContainers = [Declarations::class])
interface AppGraph {
  val elements: Set<Element>
  val <!MISSING_BINDING!>unbound<!>: Unbound
}

// METRO_JVM_ONLY
