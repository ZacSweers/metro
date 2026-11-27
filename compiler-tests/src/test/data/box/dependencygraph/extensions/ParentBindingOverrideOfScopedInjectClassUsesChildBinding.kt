// PARENT_BINDING_OVERRIDE_SEVERITY: WARN

// Under WARN the child's binding for a class scoped to the parent still wins in the child. The
// parent keeps its own instance.

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

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childGraphFactory().create()
  assertTrue(child.foo is ChildFoo)
  assertFalse(parent.foo is ChildFoo)
  assertSame(parent.foo, parent.foo)
  return "OK"
}
