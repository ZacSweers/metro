// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {

  val value: String
  val <!MISSING_BINDING!>value2<!>: CharSequence

  @Provides
  fun provideValue(): String = "Hello, world!"
}

// Each exposed type starts with an empty binding stack.
