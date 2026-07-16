// ENABLE_SUSPEND_PROVIDERS
// ENABLE_DAGGER_INTEROP

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A dagger.Lazy wrapping a suspend binding is rejected the same as Metro's Lazy.

import dagger.Lazy

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @Provides fun provideInt(<!METRO_ERROR!>dep: Lazy<String><!>): Int = 1

  @Provides suspend fun provideString(): String = "hello"
}
