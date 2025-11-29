// https://github.com/ZacSweers/metro/issues/1172

@Inject class Foo

@HasMemberInjections
open class Parent {
  @Inject lateinit var foo1: Foo
}

class Child : Parent() {
  @Inject lateinit var foo2: Foo
}

@DependencyGraph
interface AppGraph {
  fun inject(child: Child)
  fun inject(parent: Parent)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val parent = Parent()
  graph.inject(parent)
  assertNotNull(parent.foo1)
  val child = Child()
  graph.inject(child)
  assertNotNull(child.foo1)
  assertNotNull(child.foo2)
  return "OK"
}
