// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val <!PRIVATE_BINDING_ERROR!>text<!>: String

  @GraphPrivate @Provides fun provideString(): String = "hello"
}
