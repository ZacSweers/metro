// ENABLE_DERIVED_BINDINGS

// Derived bindings work behind Provider, Lazy, and function providers.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Counter {
  var count = 0
}

@Inject
class Consumer(
  val provider: Provider<Encoder<List<String>>>,
  val lazy: Lazy<Encoder<List<String>>>,
  val function: () -> Encoder<List<String>>,
)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
  val provider: Provider<Encoder<List<Int>>>
  val counter: Counter

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun int(): Encoder<Int> = NamedEncoder("int")

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  fun <T> list(element: Encoder<T>, counter: Counter): Encoder<List<T>> =
    NamedEncoder("list(${element.name})#${++counter.count}")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val consumer = graph.consumer
  assertEquals(0, graph.counter.count)
  assertEquals("list(string)#1", consumer.provider().name)
  assertEquals("list(string)#2", consumer.provider().name)
  assertEquals("list(string)#3", consumer.lazy.value.name)
  assertEquals("list(string)#3", consumer.lazy.value.name)
  assertEquals("list(string)#4", consumer.function().name)
  assertEquals("list(int)#5", graph.provider().name)
  return "OK"
}
