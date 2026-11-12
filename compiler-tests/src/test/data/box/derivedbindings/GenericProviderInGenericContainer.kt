// ENABLE_DERIVED_BINDINGS

// A generic container included with concrete type arguments can declare generic providers. The
// container's arguments come first, then the provider's own.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Holder<T>(val name: String)

interface Cache<T>

class PairCache<A, T> : Cache<Pair<A, T>>

@BindingContainer
class TypedBindings<A>(private val label: String) {
  @Provides fun encoder(): Encoder<A> = NamedEncoder(label)

  @Provides fun <B> holder(): Holder<B> = Holder("holder")

  @Provides fun <B> pair(a: Encoder<A>, b: Holder<B>): Pair<Encoder<A>, Holder<B>> = Pair(a, b)

  @Provides
  fun <B : A> bounded(a: Encoder<A>): Encoder<Map<A, B>> = NamedEncoder("bounded(${a.name})")

  // Private providers are called through newInstance with both kinds of type arguments.
  @Provides
  private fun <B> hidden(b: Holder<B>): Encoder<List<B>> = NamedEncoder("hidden(${b.name}, $label)")

  @Provides fun <T> pairCache(): PairCache<A, T> = PairCache()

  @Binds fun <T> PairCache<A, T>.bindCache(): Cache<Pair<A, T>> = this
}

@DependencyGraph
interface AppGraph {
  val pair: Pair<Encoder<String>, Holder<Int>>
  val bounded: Encoder<Map<String, String>>
  val hidden: Encoder<List<Long>>
  val cache: Cache<Pair<String, Int>>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: TypedBindings<String>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(TypedBindings("typed"))
  assertEquals("typed", graph.pair.first.name)
  assertEquals("holder", graph.pair.second.name)
  assertEquals("bounded(typed)", graph.bounded.name)
  assertEquals("hidden(holder, typed)", graph.hidden.name)
  assertTrue(graph.cache is PairCache<*, *>)
  return "OK"
}
