// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!SUSPICIOUS_UNUSED_MULTIBINDING!>ExampleGraph<!> {
  @Multibinds val <!EMPTY_MULTIBINDING!>strings<!>: Map<String, String>

  @StringKey("Element")
  @IntoMap
  @Provides
  fun provideCharSequence(): CharSequence = "Hello, world!"
}
