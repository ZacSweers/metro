// https://github.com/ZacSweers/metro/issues/649

@Inject class Foo

@Inject class Bar

@Inject class Baz

@HasMemberInjections
open class Parent {
  @Inject lateinit var foo: Foo
}

@HasMemberInjections
open class Child : Parent() {
  @Inject lateinit var bar: Bar
}

@HasMemberInjections
open class Grandchild : Child()

class GreatGrandchild : Grandchild() {
  @Inject lateinit var baz: Baz
}

@DependencyGraph
interface AppGraph {
  fun inject(target: GreatGrandchild)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val child = GreatGrandchild()
  graph.inject(child)
  assertNotNull(child.baz)
  assertNotNull(child.bar)
  assertNotNull(child.foo)
  return "OK"
}
