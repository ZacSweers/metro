// ENABLE_DERIVED_BINDINGS

// A qualified generic provider only serves qualified keys, and an unqualified one only unqualified keys.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides
  @Named("pretty")
  fun <T> prettyList(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("pretty(${element.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val plain: Encoder<List<String>>
  @Named("pretty") val pretty: Encoder<List<String>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("list(string)", graph.plain.name)
  assertEquals("pretty(string)", graph.pretty.name)
  return "OK"
}
