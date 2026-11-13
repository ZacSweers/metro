interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object EncoderBindings {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  inline fun <reified T> array(element: Encoder<T>): Encoder<Array<T>> =
    NamedEncoder("array(${T::class.simpleName})")
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
