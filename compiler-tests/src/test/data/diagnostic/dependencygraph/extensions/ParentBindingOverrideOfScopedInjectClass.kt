// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// ParentGraph owns Foo's scope, so it keeps the one Foo instance. A child binding for Foo overrides
// that instance, like it would override a scoped @Provides.

@SingleIn(AppScope::class) @Inject open class Foo

@Inject class ChildFoo : Foo()

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val foo: Foo

  fun childGraphFactory(): ChildGraph.Factory
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
