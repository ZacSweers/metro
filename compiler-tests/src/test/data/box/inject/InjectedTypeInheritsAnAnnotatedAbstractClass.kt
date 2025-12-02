// Regression test for https://github.com/ZacSweers/metro/pull/1454
@DependencyGraph
interface AppGraph {
  val foo: FooImpl
}

@Inject
class FooImpl: Foo()

@AmazingApi
abstract class Foo {
  val value = "foo"
}

annotation class AmazingApi

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertSame(appGraph.foo.value, "foo")
  return "OK"
}