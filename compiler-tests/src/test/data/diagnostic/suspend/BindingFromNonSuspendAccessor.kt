// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Non-suspend accessor cannot directly return a suspend binding.
// Must be either suspend or return `suspend () -> T`.

@DependencyGraph
interface ExampleGraph {
  val <!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>value<!>: String

  @Provides suspend fun provideString(): String = "hello"
}
