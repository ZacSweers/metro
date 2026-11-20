// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!DUPLICATE_BINDING!>ExampleGraph<!> {
  val exampleClass: ExampleClass

  @Binds fun Impl1.provideExampleClass1(): ExampleClass
  @Binds fun Impl2.provideExampleClass2(): ExampleClass
}

interface ExampleClass
@Inject class Impl1 : ExampleClass
@Inject class Impl2 : ExampleClass
