// ENABLE_DERIVED_BINDINGS

// A reified encoder is the fallback. The exact UserEncoder wins for Encoder<User>, and the list
// provider wins for Encoder<List<Order>> while its element encoder comes from the reified one.
fun interface Encoder<T> {
  fun encode(value: T): String
}

data class User(val name: String)

data class Order(val id: Int)

@BindingContainer
object JsonEncoders {
  @Provides
  inline fun <reified T> serializable(): Encoder<T> = Encoder { "${T::class.simpleName}($it)" }

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
    values.joinToString(",", "[", "]") { element.encode(it) }
  }
}

@ContributesBinding(AppScope::class)
@Inject
class UserEncoder : Encoder<User> {
  override fun encode(value: User): String = "user:${value.name}"
}

@DependencyGraph(AppScope::class, bindingContainers = [JsonEncoders::class])
interface AppGraph {
  val user: Encoder<User>
  val order: Encoder<Order>
  val orders: Encoder<List<Order>>
  val users: Encoder<List<User>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.user is UserEncoder)
  assertEquals("user:Ada", graph.user.encode(User("Ada")))
  assertEquals("Order(Order(id=1))", graph.order.encode(Order(1)))
  assertEquals(
    "[Order(Order(id=1)),Order(Order(id=2))]",
    graph.orders.encode(listOf(Order(1), Order(2))),
  )
  assertEquals("[user:Ada]", graph.users.encode(listOf(User("Ada"))))
  return "OK"
}
