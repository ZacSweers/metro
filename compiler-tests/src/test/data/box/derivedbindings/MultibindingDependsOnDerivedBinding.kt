// Ordinary multibinding contributions can depend on derived bindings.
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

  @Provides @IntoSet fun stringList(encoder: Encoder<List<String>>): String = encoder.name

  @Provides
  @IntoMap
  @StringKey("ints")
  fun intList(encoder: Encoder<List<Int>>): String = encoder.name
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val names: Set<String>
  val namesByKey: Map<String, String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("list(string)"), graph.names)
  assertEquals(mapOf("ints" to "list(int)"), graph.namesByKey)
  return "OK"
}
