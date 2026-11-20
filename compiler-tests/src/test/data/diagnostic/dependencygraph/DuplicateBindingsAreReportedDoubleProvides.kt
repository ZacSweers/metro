// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!DUPLICATE_BINDING!>ExampleGraph<!> {
  val exampleClass: ExampleClass

  @Provides fun provideExampleClass1(): ExampleClass = ExampleClass()
  @Provides fun provideExampleClass2(): ExampleClass = ExampleClass()
}

class ExampleClass
