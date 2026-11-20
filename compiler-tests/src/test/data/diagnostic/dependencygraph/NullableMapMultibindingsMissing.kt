// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface ExampleGraph {
  @Multibinds
  val <!EMPTY_MULTIBINDING!>nullableInts<!>: Set<Int?>
  val ints: Set<Int>

  @Provides
  @IntoSet
  fun provideInt(): Int = 3
}
