// Every consumer of Provider<Foo> should get the same provider instead of a new factory.

@Inject class Bar

@Inject class Foo(val bar: Bar)

@Inject class Consumer(val foo: Provider<Foo>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.consumer.foo, graph.consumer.foo)
  return "OK"
}
