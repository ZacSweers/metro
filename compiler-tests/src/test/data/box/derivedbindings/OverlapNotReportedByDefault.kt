// Overlapping generic providers aren't reported at the default severity.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  @Provides fun <T> list(): Encoder<List<T>> = NamedEncoder("list")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val strings: Encoder<List<String>>
}

fun box(): String {
  assertEquals("list", createGraph<AppGraph>().strings.name)
  return "OK"
}
