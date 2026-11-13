// Generic @Provides and @Binds declared on the graph itself don't change ordinary bindings. The
// graph implementation still has to implement the abstract generic @Binds.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Cache<T>

interface Store<T>

@Inject class LruCache<T> : Cache<T>, Store<T>

@DependencyGraph
interface AppGraph {
  val stringEncoder: Encoder<String>

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Binds fun <T> LruCache<T>.bindCache(): Cache<T>

  @Binds fun <T> bindStore(cache: LruCache<T>): Store<T>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string", graph.stringEncoder.name)
  return "OK"
}
