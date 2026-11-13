// Provider access to a reified provider stays lazy, and each Provider call runs it again.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Order

class Counter {
  var count = 0
}

@BindingContainer
object Encoders {
  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  inline fun <reified T> named(counter: Counter): Encoder<T> =
    NamedEncoder("${T::class.simpleName}#${++counter.count}")
}

@Inject
class Consumer(
  val provider: Provider<Encoder<Order>>,
  val lazy: Lazy<Encoder<Order>>,
  val function: () -> Encoder<Order>,
)

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val consumer: Consumer
  val provider: Provider<Encoder<String>>
  val function: () -> Encoder<Int>
  val lazy: Lazy<Encoder<Long>>
  val counter: Counter
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val consumer = graph.consumer
  val provider = graph.provider
  val function = graph.function
  val lazy = graph.lazy
  assertEquals(0, graph.counter.count)
  assertEquals("Order#1", consumer.provider().name)
  assertEquals("Order#2", consumer.provider().name)
  assertEquals("Order#3", consumer.lazy.value.name)
  assertEquals("Order#3", consumer.lazy.value.name)
  assertEquals("Order#4", consumer.function().name)
  assertEquals("Order#5", consumer.function().name)
  assertEquals("String#6", provider().name)
  assertEquals("String#7", provider().name)
  assertEquals("Int#8", function().name)
  assertEquals("Long#9", lazy.value.name)
  assertEquals("Long#9", lazy.value.name)
  return "OK"
}
