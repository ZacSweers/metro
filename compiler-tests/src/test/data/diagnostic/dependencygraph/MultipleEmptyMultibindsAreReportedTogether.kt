// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  @Multibinds val <!EMPTY_MULTIBINDING!>ints<!>: Set<Int>
  @Multibinds val <!EMPTY_MULTIBINDING!>strings<!>: Set<String>
  @Multibinds val <!EMPTY_MULTIBINDING!>stringsAndInts<!>: Map<String, Int>
}
