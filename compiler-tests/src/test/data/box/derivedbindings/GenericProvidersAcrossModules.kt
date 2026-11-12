// ENABLE_DERIVED_BINDINGS

// MODULE: lib
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Cache<T>

@Inject class LruCache<T> : Cache<T>

interface Store<T>

@Inject class DiskStore<T> : Store<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides
  fun <T : Comparable<T>> sorted(element: Encoder<T>): Encoder<Set<T>> =
    NamedEncoder("sorted(${element.name})")

  @Provides
  private fun <K, V> map(key: Encoder<K>, value: Encoder<V>): Encoder<Map<K, V>> =
    NamedEncoder("map(${key.name}, ${value.name})")
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>

  @Binds fun <T> DiskStore<T>.bindStore(): Store<T>
}

// MODULE: main(lib)
@DependencyGraph(bindingContainers = [Encoders::class, CacheBindings::class])
interface AppGraph {
  val strings: Encoder<List<String>>
  val sorted: Encoder<Set<String>>
  val map: Encoder<Map<String, List<String>>>
  val cache: Cache<Int>
  val store: Store<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("list(string)", graph.strings.name)
  assertEquals("sorted(string)", graph.sorted.name)
  assertEquals("map(string, list(string))", graph.map.name)
  assertTrue(graph.cache is LruCache<*>)
  assertTrue(graph.store is DiskStore<*>)
  return "OK"
}
