// Generic providers that no graph requests don't change how ordinary bindings resolve.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object EncoderBindings {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides fun <T> empty(): Encoder<Collection<T>> = NamedEncoder("empty")

  @Provides
  fun <T : Comparable<T>> sorted(element: Encoder<T>): Encoder<Set<T>> =
    NamedEncoder("sorted(${element.name})")
}

@DependencyGraph(bindingContainers = [EncoderBindings::class])
interface AppGraph {
  val stringEncoder: Encoder<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string", graph.stringEncoder.name)
  return "OK"
}
