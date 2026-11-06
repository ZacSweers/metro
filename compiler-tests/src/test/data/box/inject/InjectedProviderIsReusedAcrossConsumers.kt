// Repeated consumer requests share their provider within each graph.

var barConstructions = 0

@Inject class Bar {
  init {
    barConstructions++
  }
}

@Inject class Foo(val bar: Bar)

@Inject class Consumer(val foo: Provider<Foo>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
}

fun box(): String {
  barConstructions = 0
  val graph = createGraph<AppGraph>()
  assertSame(graph.consumer.foo, graph.consumer.foo)
  assertEquals(0, barConstructions)
  val first = graph.consumer.foo()
  val second = graph.consumer.foo()
  assertNotSame(first, second)
  assertNotSame(first.bar, second.bar)
  assertEquals(2, barConstructions)
  assertNotSame(graph.consumer.foo, createGraph<AppGraph>().consumer.foo)
  return "OK"
}
