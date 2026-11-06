@Inject class Value
@Inject class Consumer(
  val value: () -> Value,
  val secondValue: () -> Value,
  val metro: Provider<Value>,
  val lazy: Lazy<Value>,
)

@DependencyGraph
interface AppGraph {
  val consumer: Consumer
  val factory: Provider<Consumer>
  val value: () -> Value
  val metro: Provider<Value>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.value, graph.consumer.value)
  assertSame(graph.value, graph.factory().value)
  assertSame(graph.factory().value, graph.factory().value)
  val first = graph.factory()
  val second = graph.factory()
  assertSame(first.value, first.secondValue)
  assertSame(first.value, second.secondValue)
  assertSame(graph.metro, first.metro)
  assertSame(first.metro, second.metro)
  assertNotSame(first.lazy, second.lazy)
  assertSame(first.lazy.value, first.lazy.value)
  assertNotSame(first.lazy.value, second.lazy.value)
  assertNotSame(graph.value(), graph.value())
  return "OK"
}
