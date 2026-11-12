// ENABLE_DERIVED_BINDINGS

// Substituting a generic container's type arguments must leave its generic providers' own type
// parameters alone. The container's ordinary providers still resolve with the concrete arguments.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Cache<T>

class LruCache<T>

class PairCache<A, T> : Cache<Pair<A, T>>

@BindingContainer
class TypedBindings<A>(private val label: String) {
  @Provides fun encoder(): Encoder<A> = NamedEncoder(label)

  @Provides
  fun <B : A> pair(a: Encoder<A>, b: Encoder<B>): Encoder<Map<A, B>> =
    NamedEncoder("pair(${a.name}, ${b.name})")

  @Provides fun <A> shadowed(a: Encoder<A>): Encoder<List<A>> = NamedEncoder("list(${a.name})")

  @Provides fun <T> lru(): LruCache<T> = LruCache()

  @Binds fun <T> PairCache<A, T>.bindCache(): Cache<Pair<A, T>> = this
}

@DependencyGraph
interface AppGraph {
  val intEncoder: Encoder<Int>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: TypedBindings<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(TypedBindings("int"))
  assertEquals("int", graph.intEncoder.name)
  return "OK"
}
