// `Encoder<T?>` only serves nullable arguments. An unbounded `<T>` can bind a nullable argument.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

interface Wrapper<T> {
  val name: String
}

class NamedWrapper<T>(override val name: String) : Wrapper<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun int(): Encoder<Int> = NamedEncoder("int")

  @Provides
  fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = NamedEncoder("nullable(${inner.name})")

  @Provides
  fun <T> wrapped(element: Encoder<T>): Wrapper<T> = NamedWrapper("wrapped(${element.name})")
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val string: Encoder<String>
  val nullableString: Encoder<String?>
  val nullableInt: Encoder<Int?>
  val wrappedNullableString: Wrapper<String?>
  val wrappedString: Wrapper<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string", graph.string.name)
  assertEquals("nullable(string)", graph.nullableString.name)
  assertEquals("nullable(int)", graph.nullableInt.name)
  assertEquals("wrapped(nullable(string))", graph.wrappedNullableString.name)
  assertEquals("wrapped(string)", graph.wrappedString.name)
  return "OK"
}
