// ENABLE_SUSPEND_PROVIDERS
// ENABLE_DAGGER_INTEROP

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A javax.inject.Provider wrapping a suspend binding is rejected the same as Metro's Provider.

import javax.inject.Provider

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @Provides fun provideInt(<!SUSPEND_BINDING_WRAPPED_IN_PROVIDER!>dep: Provider<String><!>): Int = 1

  @Provides suspend fun provideString(): String = "hello"
}
