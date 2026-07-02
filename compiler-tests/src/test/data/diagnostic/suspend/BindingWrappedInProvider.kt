// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Cannot depend on a suspend binding via Provider<T>. Must use SuspendProvider<T>.

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @Provides fun provideInt(<!METRO_ERROR!>dep: Provider<String><!>): Int = 1

  @Provides suspend fun provideString(): String = "hello"
}
