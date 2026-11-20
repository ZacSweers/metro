// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  fun inject(exampleClass: ExampleClass)
}
class ExampleClass {
  @Inject lateinit var <!MISSING_BINDING!>value<!>: String
}
