// A recursive type makes a cycle through a derived binding. Deferred dependencies break the cycle.
data class Category(val name: String, val children: List<Category>)

interface Encoder<T> {
  fun encode(value: T): String
}

class ListEncoder<T>(private val element: Encoder<T>) : Encoder<List<T>> {
  override fun encode(value: List<T>): String =
    value.joinToString(prefix = "[", postfix = "]") { element.encode(it) }
}

@Inject
class CategoryEncoder(
  private val provider: Provider<Encoder<List<Category>>>,
  private val lazy: Lazy<Encoder<List<Category>>>,
  private val function: () -> Encoder<List<Category>>,
) : Encoder<Category> {
  override fun encode(value: Category): String = value.name + provider().encode(value.children)

  fun encodeLazily(value: List<Category>): String = lazy.value.encode(value)

  fun encodeWithFunction(value: List<Category>): String = function().encode(value)
}

@DependencyGraph
interface AppGraph {
  val encoder: Encoder<Category>
  val categoryEncoder: CategoryEncoder

  @Binds fun bind(impl: CategoryEncoder): Encoder<Category>

  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = ListEncoder(element)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val leaf = Category("leaf", emptyList())
  val root = Category("root", listOf(Category("a", listOf(leaf)), Category("b", emptyList())))
  assertEquals("root[a[leaf[]], b[]]", graph.encoder.encode(root))
  assertEquals("[a[leaf[]]]", graph.categoryEncoder.encodeLazily(listOf(root.children[0])))
  assertEquals("[b[]]", graph.categoryEncoder.encodeWithFunction(listOf(root.children[1])))
  return "OK"
}
