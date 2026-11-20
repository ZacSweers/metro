// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!INCOMPATIBLE_SCOPE!>ExampleGraph<!> {

  val value: String

  @SingleIn(AppScope::class)
  @Provides
  fun provideValue(): String = "Hello, world!"
}
