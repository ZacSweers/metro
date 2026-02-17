// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// https://github.com/ZacSweers/metro/issues/1816

@DependencyGraph
interface FooComponent {
  val value: Int

  @Provides fun provideFoo(<!METRO_ERROR!>bar: Map<String, Provider<String>><!>): Int = 3

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides bar: Map<String, String>): FooComponent
  }
}
