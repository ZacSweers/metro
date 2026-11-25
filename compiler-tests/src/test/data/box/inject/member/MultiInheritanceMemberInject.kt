// https://github.com/ZacSweers/metro/issues/649

@Inject class Foo

@Inject class Bar

@HasMemberInjections
open class Parent {
  @Inject lateinit var foo: Foo
}

class Child : Parent() {
  @Inject lateinit var bar: Bar
}

@DependencyGraph(AppScope::class)
interface TestGraph {
  fun inject(target: Child)
}

fun box(): String {
  val child = Child()
  createGraph<TestGraph>().inject(child)
  assertIs<Foo>(child.foo)
  assertIs<Bar>(child.bar)
  return "OK"
}
