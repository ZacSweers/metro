// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Foo comes from another module, so it has no source here. ParentGraph still owns its scope, and
// the child's binding overrides it.

// MODULE: lib
@SingleIn(AppScope::class) @Inject open class Foo

@Inject class ChildFoo : Foo()

// MODULE: main(lib)
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
