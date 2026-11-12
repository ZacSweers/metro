// ENABLE_DERIVED_BINDINGS

// MODULE: lib
// Another module reads the generic providers' full signatures from their factories and mirrors.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Cache<T>

interface Store<T>

class LruCache<T> : Cache<T>, Store<T>

@BindingContainer
object EncoderBindings {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides
  fun <T : Comparable<T>> sorted(element: Encoder<T>): Encoder<Set<T>> =
    NamedEncoder("sorted(${element.name})")

  @Provides
  private fun <T> hidden(element: Encoder<T>): Encoder<Array<T>> =
    NamedEncoder("hidden(${element.name})")
}

@BindingContainer
class TypedBindings<A>(private val label: String) {
  @Provides fun encoder(): Encoder<A> = NamedEncoder(label)

  @Provides
  fun <B : A> pair(a: Encoder<A>, b: Encoder<B>): Encoder<Map<A, B>> =
    NamedEncoder("pair(${a.name}, ${b.name})")
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> LruCache<T>.bindCache(): Cache<T>

  @Binds private fun <T> LruCache<T>.bindStore(): Store<T> = this
}

// MODULE: main(lib)
@DependencyGraph(bindingContainers = [EncoderBindings::class, CacheBindings::class])
interface AppGraph {
  val stringEncoder: Encoder<String>
  val intEncoder: Encoder<Int>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: TypedBindings<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(TypedBindings("int"))
  assertEquals("string", graph.stringEncoder.name)
  assertEquals("int", graph.intEncoder.name)
  return "OK"
}
