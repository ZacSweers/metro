// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>ExampleGraph<!> {

  val value: Int

  @Provides
  fun provideInt(value: Int): Int = value
}
