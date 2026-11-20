// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface ExampleGraph {
  val foo: Foo

  @Inject
  class Foo(<!MISSING_BINDING!>val input: Int?<!>)
}
