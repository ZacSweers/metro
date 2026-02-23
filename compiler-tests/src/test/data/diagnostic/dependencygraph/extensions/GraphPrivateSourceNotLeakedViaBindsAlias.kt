// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Verifies that a @Binds alias of a @GraphPrivate binding exposes the result type
// but does *not* leak the private source type to child graphs.

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @SingleIn(AppScope::class) @<!OPT_IN_USAGE!>GraphPrivate<!> @Provides fun provideString(): String = "hello"
  @Binds fun bind(value: String): CharSequence

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  // CharSequence is accessible via the published @Binds alias
  val text: CharSequence
  // String is still @GraphPrivate and should NOT be accessible
  val <!METRO_ERROR!>directString<!>: String
}
