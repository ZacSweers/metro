// A scoped reified provider gives one instance per key and separate instances for separate keys.
class Cache<T>(val name: String, val id: Int)

class Counter {
  var count = 0
}

class Order

@BindingContainer
object Caches {
  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  @SingleIn(AppScope::class)
  inline fun <reified T> cache(counter: Counter): Cache<T> =
    Cache(T::class.simpleName!!, ++counter.count)
}

@Inject class Consumer(val orders: Cache<Order>, val provider: Provider<Cache<Order>>)

@DependencyGraph(AppScope::class, bindingContainers = [Caches::class])
interface AppGraph {
  val counter: Counter
  val orders: Cache<Order>
  val ordersAgain: Cache<Order>
  val orderProvider: Provider<Cache<Order>>
  val strings: Cache<String>
  val stringFunction: () -> Cache<String>
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val stringFunction = graph.stringFunction
  assertEquals(0, graph.counter.count)
  val orders = graph.orders
  assertEquals("Order", orders.name)
  assertSame(orders, graph.ordersAgain)
  assertSame(orders, graph.orderProvider())
  assertSame(orders, graph.consumer.orders)
  assertSame(orders, graph.consumer.provider())
  val strings = stringFunction()
  assertEquals("String", strings.name)
  assertSame(strings, graph.strings)
  assertNotSame<Any>(orders, strings)
  assertEquals(2, graph.counter.count)
  return "OK"
}
