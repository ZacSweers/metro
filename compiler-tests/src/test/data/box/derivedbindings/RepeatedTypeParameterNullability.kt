// A return type can use a type parameter as both `T` and `T?`. The `T` position decides whether
// `T` is nullable, so `Both<T, T?>` provides `Both<String, String?>` and `Both<String?, String?>`.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Both<A, B>(val name: String)

@BindingContainer
object Bindings {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun nullableString(): Encoder<String?> = NamedEncoder("nullable string")

  @Provides fun <T> both(element: Encoder<T>): Both<T, T?> = Both("both(${element.name})")

  @Provides fun <T> flipped(element: Encoder<T>): Both<T?, T> = Both("flipped(${element.name})")
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val both: Both<String, String?>
  val flipped: Both<String?, String>
  // `both` and `flipped` both match this key with `T = String?`, so the graph declares an exact
  // binding for it.
  val bothNullable: Both<String?, String?>

  @Provides
  fun bothNullable(element: Encoder<String?>): Both<String?, String?> =
    Both("exact(${element.name})")
}

@BindingContainer
object OnlyBoth {
  @Provides fun nullableString(): Encoder<String?> = NamedEncoder("nullable string")

  @Provides fun <T> both(element: Encoder<T>): Both<T, T?> = Both("both(${element.name})")
}

@DependencyGraph(bindingContainers = [OnlyBoth::class])
interface NullableGraph {
  val bothNullable: Both<String?, String?>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("both(string)", graph.both.name)
  assertEquals("flipped(string)", graph.flipped.name)
  assertEquals("exact(nullable string)", graph.bothNullable.name)

  // `T = String?`, so the dependency is `Encoder<String?>`.
  val nullableGraph = createGraph<NullableGraph>()
  assertEquals("both(nullable string)", nullableGraph.bothNullable.name)
  return "OK"
}
