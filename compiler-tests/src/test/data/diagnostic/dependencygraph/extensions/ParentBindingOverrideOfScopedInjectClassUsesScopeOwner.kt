// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Foo is scoped to AppGraph, two levels up. The report names the graph that owns the scope, even
// though no ancestor requests Foo.

abstract class ChildScope private constructor()

@SingleIn(AppScope::class) @Inject open class Foo

@Inject class GrandchildFoo : Foo()

@DependencyGraph(AppScope::class)
interface AppGraph {
  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun grandchildGraphFactory(): GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val foo: Foo

  @Binds <!PARENT_BINDING_OVERRIDE_ERROR!>val GrandchildFoo.bind: Foo<!>

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}
