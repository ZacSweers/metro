// `Encoder<T>` and `Encoder<T?>` both match `Encoder<String?>`. `Encoder<T?>` is more specific, so
// it wins. Only `Encoder<T>` matches `Encoder<String>`.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> plain(): Encoder<T> = NamedEncoder("plain")

  @Provides fun <T : Any> nullable(): Encoder<T?> = NamedEncoder("nullable")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val string: Encoder<String>
  val nullableString: Encoder<String?>
  val nullableStrings: Encoder<List<String?>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("plain", graph.string.name)
  assertEquals("nullable", graph.nullableString.name)
  // The list itself isn't nullable, so only `plain` matches.
  assertEquals("plain", graph.nullableStrings.name)
  return "OK"
}
