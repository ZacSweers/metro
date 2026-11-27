// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// The parent only offers Foo through its scoped @Inject constructor. An explicit child binding beats
// constructor injection, like it does in a single graph, so it isn't an override.

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

  @Binds val ChildFoo.bind: Foo

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
