// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A non-suspend @Provides cannot accept an unwrapped suspend binding as a parameter.
// The user must wrap the parameter in SuspendProvider<…> so the factory's Provider<T>
// field type matches the runtime SuspendProvider<T>.

@DependencyGraph
interface ExampleGraph {
  val <!METRO_ERROR!>value<!>: Int

  @Provides fun provideInt(<!METRO_ERROR!>dep: String<!>): Int = 1

  @Provides suspend fun provideString(): String = "hello"
}
