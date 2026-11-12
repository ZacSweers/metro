// ENABLE_DERIVED_BINDINGS
// ENABLE_SUSPEND_PROVIDERS

// A suspend reified provider resolves through suspend accessors and suspend providers.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Order

class Counter {
  var count = 0
}

@Inject class Consumer(val orders: Encoder<Order>)

@BindingContainer
object Encoders {
  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  suspend inline fun <reified T> named(counter: Counter): Encoder<T> =
    NamedEncoder("${T::class.simpleName}#${++counter.count}")

  @Provides
  inline fun <reified T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("list(${T::class.simpleName}, ${element.name})")
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val counter: Counter

  suspend fun consumer(): Consumer

  suspend fun string(): Encoder<String>

  val intProvider: suspend () -> Encoder<Int>

  val listProvider: suspend () -> Encoder<List<Long>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    assertEquals("Order#1", graph.consumer().orders.name)
    assertEquals("String#2", graph.string().name)
    val ints = graph.intProvider
    assertEquals(2, graph.counter.count)
    assertEquals("Int#3", ints().name)
    assertEquals("Int#4", ints().name)
    assertEquals("list(Long, Long#5)", graph.listProvider().name)
    "OK"
  }
}
