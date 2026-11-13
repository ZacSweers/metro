// LANGUAGE: +ContextParameters

// A generic provider can take context parameters like any other provider.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Label(val value: String)

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  context(label: Label)
  fun <T> labeled(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("${label.value}(${element.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val strings: Encoder<List<String>>

  @Provides fun label(): Label = Label("labeled")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("labeled(string)", graph.strings.name)
  return "OK"
}
