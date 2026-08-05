// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARALLEL_THREADS: 4

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @SingleIn(AppScope::class) @GraphPrivate @Provides fun provideString(): String = "hello"

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  fun createFirstGrandchild(): FirstGrandchildGraph

  fun createSecondGrandchild(): SecondGrandchildGraph
}

@GraphExtension
interface FirstGrandchildGraph {
  val <!MISSING_BINDING!>text<!>: String
}

@GraphExtension
interface SecondGrandchildGraph {
  val <!MISSING_BINDING!>text<!>: String
}
