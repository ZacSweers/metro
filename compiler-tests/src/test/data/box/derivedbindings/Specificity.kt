// The most specific matching provider wins. Less specific ones still serve other keys.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Token

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val strings: Encoder<List<String>>
  val tokens: Encoder<List<Token>>
  val token: Encoder<Token>
  val string: Encoder<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("list(string)", graph.strings.name)
  assertEquals("list(anything)", graph.tokens.name)
  assertEquals("anything", graph.token.name)
  assertEquals("string", graph.string.name)
  return "OK"
}
