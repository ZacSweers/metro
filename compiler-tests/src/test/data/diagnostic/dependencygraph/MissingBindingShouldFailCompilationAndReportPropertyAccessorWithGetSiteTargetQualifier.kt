// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {

  @get:Named("hello")
  val <!MISSING_BINDING!>text<!>: String
}
