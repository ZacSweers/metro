// ENABLE_DERIVED_BINDINGS

// Providers can declare several type parameters, and one can appear more than once.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun int(): Encoder<Int> = NamedEncoder("int")

  @Provides
  fun <K, V> map(key: Encoder<K>, value: Encoder<V>): Encoder<Map<K, V>> =
    NamedEncoder("map(${key.name}, ${value.name})")

  @Provides
  fun <T> same(element: Encoder<T>): Encoder<Pair<T, T>> = NamedEncoder("same(${element.name})")

  @Provides
  fun <A, B> mixed(first: Encoder<A>, second: Encoder<B>): Encoder<Pair<A, B>> =
    NamedEncoder("mixed(${first.name}, ${second.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val map: Encoder<Map<String, Int>>
  val same: Encoder<Pair<String, String>>
  val mixed: Encoder<Pair<String, Int>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("map(string, int)", graph.map.name)
  // `same` is more specific than `mixed` for a pair of equal types.
  assertEquals("same(string)", graph.same.name)
  assertEquals("mixed(string, int)", graph.mixed.name)
  return "OK"
}
