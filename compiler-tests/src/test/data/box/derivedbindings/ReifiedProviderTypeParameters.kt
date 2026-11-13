// Reified providers can mix reified and plain type parameters and can declare bounds.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Entity {
  val id: Int
}

class Order(override val id: Int) : Entity

class Tag(val value: String)

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun int(): Encoder<Int> = NamedEncoder("int")

  // Only K is reified. V's encoder comes from the graph.
  @Provides
  inline fun <reified K, V> map(value: Encoder<V>): Encoder<Map<K, V>> =
    NamedEncoder("map(${K::class.simpleName}, ${value.name})")

  @Provides
  inline fun <reified T : Entity> entity(): Encoder<T> = NamedEncoder("entity(${T::class.simpleName})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val map: Encoder<Map<Tag, String>>
  val mapProvider: Provider<Encoder<Map<Int, Int>>>
  val order: Encoder<Order>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("map(Tag, string)", graph.map.name)
  assertEquals("map(Int, int)", graph.mapProvider().name)
  assertEquals("entity(Order)", graph.order.name)
  return "OK"
}
