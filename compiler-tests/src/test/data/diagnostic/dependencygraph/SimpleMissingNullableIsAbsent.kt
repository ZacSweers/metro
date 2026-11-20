// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface ExampleGraph {
  val int: Int
  val <!MISSING_BINDING!>nullable<!>: Int?

  @Provides
  fun provideInt(): Int = 0
}
