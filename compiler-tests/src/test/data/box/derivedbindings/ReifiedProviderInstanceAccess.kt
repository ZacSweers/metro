// ENABLE_DERIVED_BINDINGS

// A reified provider requested as an instance sees each key's own type argument.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Order

class Invoice

@BindingContainer
object Encoders {
  @Provides inline fun <reified T> named(): Encoder<T> = NamedEncoder("${T::class.simpleName}")
}

@Inject class Consumer(val order: Encoder<Order>, val invoice: Encoder<Invoice>)

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val order: Encoder<Order>
  val invoice: Encoder<Invoice>
  val string: Encoder<String>
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Order", graph.order.name)
  assertEquals("Invoice", graph.invoice.name)
  assertEquals("String", graph.string.name)
  assertEquals("Order", graph.consumer.order.name)
  assertEquals("Invoice", graph.consumer.invoice.name)
  return "OK"
}
