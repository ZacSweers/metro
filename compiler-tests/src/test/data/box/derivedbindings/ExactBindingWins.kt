// ENABLE_DERIVED_BINDINGS

// An ordinary binding for the exact key wins over a matching generic provider.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun int(): Encoder<Int> = NamedEncoder("int")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides fun stringList(): Encoder<List<String>> = NamedEncoder("exact")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val strings: Encoder<List<String>>
  val ints: Encoder<List<Int>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("exact", graph.strings.name)
  assertEquals("list(int)", graph.ints.name)
  return "OK"
}
