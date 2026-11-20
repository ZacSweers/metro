// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  val <!MISSING_BINDING!>int<!>: Int

  @Provides @IntoMap @StringKey("hello") fun provideInt(): Int = 0
}
