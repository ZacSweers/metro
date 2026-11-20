// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
abstract class ExampleGraph() {

  abstract fun exampleClass(): ExampleClass
}

@Inject
class ExampleClass(@param:Named("hello") private <!MISSING_BINDING!>val text: String<!>)
