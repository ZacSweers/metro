// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// ParentGraph takes Foo as a graph input, which wins over Foo's @Inject constructor there. The
// child overrides that input. Metro reports it once, against the input.

@SingleIn(AppScope::class) @Inject open class Foo

@Inject class ChildFoo : Foo()

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val foo: Foo

  fun childGraphFactory(): ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides foo: Foo): ParentGraph
  }
}

@GraphExtension
interface ChildGraph {
  val foo: Foo

  @Binds <!PARENT_BINDING_OVERRIDE_ERROR!>val ChildFoo.bind: Foo<!>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
