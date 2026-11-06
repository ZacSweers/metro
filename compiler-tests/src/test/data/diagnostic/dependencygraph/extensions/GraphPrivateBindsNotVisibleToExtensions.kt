// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

class Registry

@DependencyGraph(AppScope::class)
interface ParentGraph {
  @Provides @SingleIn(AppScope::class) @Named("real") fun real(): Registry = Registry()

  @Binds @GraphPrivate fun plain(@Named("real") real: Registry): Registry

  @Named("real") val realRegistry: Registry

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val <!MISSING_BINDING!>plain<!>: Registry

  fun createGrandchild(): GrandchildGraph
}

@GraphExtension
interface GrandchildGraph {
  val <!MISSING_BINDING!>plain<!>: Registry
}
