// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Non-suspend accessor cannot directly return a suspend binding.
// Must be either suspend or use SuspendProvider<T>.

@DependencyGraph
interface ExampleGraph {
  val <!METRO_ERROR!>value<!>: String

  @Provides suspend fun provideString(): String = "hello"
}
