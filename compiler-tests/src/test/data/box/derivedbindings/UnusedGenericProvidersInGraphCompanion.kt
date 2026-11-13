// Generic providers in the graph's companion object don't change ordinary bindings.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@DependencyGraph
interface AppGraph {
  val stringEncoder: Encoder<String>

  companion object {
    @Provides fun string(): Encoder<String> = NamedEncoder("string")

    @Provides
    fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

    @Provides fun <T> empty(): Encoder<Collection<T>> = NamedEncoder("empty")
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string", graph.stringEncoder.name)
  return "OK"
}
