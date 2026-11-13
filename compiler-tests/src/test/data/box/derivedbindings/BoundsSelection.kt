// ENABLE_DERIVED_BINDINGS

// Bounds pick between providers that share a raw return type. A bound can mention its own type
// parameter, like `T : Comparable<T>`, or another type parameter, like `B : A`.
interface Formatter<T> {
  val name: String
}

class NamedFormatter<T>(override val name: String) : Formatter<T>

class Version(val value: Int) : Comparable<Version> {
  override fun compareTo(other: Version): Int = value.compareTo(other.value)
}

interface Converter<A, B> {
  val name: String
}

class NamedConverter<A, B>(override val name: String) : Converter<A, B>

@BindingContainer
object Formatters {
  @Provides fun <T : Number> number(): Formatter<List<T>> = NamedFormatter("number")

  @Provides fun <T : CharSequence> text(): Formatter<List<T>> = NamedFormatter("text")

  @Provides fun <T : Comparable<T>> sorted(): Formatter<Set<T>> = NamedFormatter("sorted")

  @Provides
  fun <A, B : A> upcast(): Converter<B, A> = NamedConverter("upcast")
}

@DependencyGraph(bindingContainers = [Formatters::class])
interface AppGraph {
  val ints: Formatter<List<Int>>
  val strings: Formatter<List<String>>
  val versions: Formatter<Set<Version>>
  val sortedStrings: Formatter<Set<String>>
  val intToNumber: Converter<Int, Number>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("number", graph.ints.name)
  assertEquals("text", graph.strings.name)
  assertEquals("sorted", graph.versions.name)
  assertEquals("sorted", graph.sortedStrings.name)
  assertEquals("upcast", graph.intToNumber.name)
  return "OK"
}
