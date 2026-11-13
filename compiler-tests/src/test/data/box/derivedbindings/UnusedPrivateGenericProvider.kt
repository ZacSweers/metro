// A private generic provider gets a factory whose helper calls it with its type arguments.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object EncoderBindings {
  @Provides private fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  private fun <T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("list(${element.name})")
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
