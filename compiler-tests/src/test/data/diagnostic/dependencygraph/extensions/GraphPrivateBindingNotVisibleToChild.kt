// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @SingleIn(AppScope::class) @<!OPT_IN_USAGE!>GraphPrivate<!> @Provides fun provideString(): String = "hello"

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val <!METRO_ERROR!>text<!>: String
}
