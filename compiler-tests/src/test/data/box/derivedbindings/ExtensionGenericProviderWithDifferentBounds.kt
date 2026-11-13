// ENABLE_DERIVED_BINDINGS

// An extension's generic provider only hides a parent's when their bounds match too. With different
// bounds, the parent's provider still serves the keys the extension's provider can't.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@DependencyGraph
interface ParentGraph {
  val childFactory: ChildGraph.Factory

  @Provides fun <T> any(): Encoder<T> = NamedEncoder("parent any")

  @Provides fun <T : Number> numbers(): Encoder<List<T>> = NamedEncoder("parent numbers")
}

@GraphExtension
interface ChildGraph {
  // Only the parent's unbounded provider accepts `String`.
  val string: Encoder<String>
  // These bounds match, so the extension's provider hides the parent's.
  val ints: Encoder<List<Int>>

  @Provides fun <T : Number> childNumber(): Encoder<T> = NamedEncoder("child number")

  @Provides fun <E : Number> childNumbers(): Encoder<List<E>> = NamedEncoder("child numbers")

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childFactory.create()
  assertEquals("parent any", child.string.name)
  assertEquals("child numbers", child.ints.name)
  return "OK"
}
