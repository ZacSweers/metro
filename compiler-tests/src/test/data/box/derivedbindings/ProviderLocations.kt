// ENABLE_DERIVED_BINDINGS

// Generic providers resolve from a private container function, a companion object, and the graph
// itself.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  // Private providers are called through their factory's newInstance helper.
  @Provides
  private fun <T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("list(${element.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val list: Encoder<List<String>>
  val set: Encoder<Set<String>>
  val pair: Encoder<Pair<String, List<String>>>

  @Provides
  fun <A, B> pair(first: Encoder<A>, second: Encoder<B>): Encoder<Pair<A, B>> =
    NamedEncoder("pair(${first.name}, ${second.name})")

  companion object {
    @Provides
    fun <T> set(element: Encoder<T>): Encoder<Set<T>> = NamedEncoder("set(${element.name})")
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("list(string)", graph.list.name)
  assertEquals("set(string)", graph.set.name)
  assertEquals("pair(string, list(string))", graph.pair.name)
  return "OK"
}
