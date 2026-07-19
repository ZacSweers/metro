// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Cannot depend on a suspend binding via Lazy<T>.

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @Provides fun provideInt(<!SUSPEND_BINDING_WRAPPED_IN_LAZY!>dep: Lazy<String><!>): Int = 1

  @Provides suspend fun provideString(): String = "hello"
}
