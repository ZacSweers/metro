// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val <!PRIVATE_BINDING_ERROR!>text<!>: String

  @<!OPT_IN_USAGE!>GraphPrivate<!> @Provides fun provideString(): String = "hello"
}
