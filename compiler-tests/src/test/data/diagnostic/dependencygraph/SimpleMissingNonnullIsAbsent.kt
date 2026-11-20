// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface ExampleGraph {
  val <!MISSING_BINDING!>int<!>: Int
  val nullable: Int?

  @Provides
  fun provideNullableInt(): Int? = 1
}
