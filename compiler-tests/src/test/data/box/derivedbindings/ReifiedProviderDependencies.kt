// ENABLE_DERIVED_BINDINGS

// A reified provider's parameters come from the graph, including another derived binding.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Order

class Prefix(val value: String)

@BindingContainer
object Encoders {
  @Provides fun prefix(): Prefix = Prefix("json")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides
  inline fun <reified T> named(prefix: Prefix): Encoder<T> =
    NamedEncoder("${prefix.value}:${T::class.simpleName}")

  // The list encoder comes from the generic list provider and the reified one.
  @Provides
  inline fun <reified T> withList(
    prefix: Prefix,
    list: Encoder<List<T>>,
    lazyList: Lazy<Encoder<List<T>>>,
  ): Encoder<Array<T>> =
    NamedEncoder("${prefix.value}:array(${T::class.simpleName}, ${list.name}, ${lazyList.value.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val orders: Encoder<Array<Order>>
  val orderList: Encoder<List<Order>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("list(json:Order)", graph.orderList.name)
  assertEquals(
    "json:array(Order, list(json:Order), list(json:Order))",
    graph.orders.name,
  )
  return "OK"
}
