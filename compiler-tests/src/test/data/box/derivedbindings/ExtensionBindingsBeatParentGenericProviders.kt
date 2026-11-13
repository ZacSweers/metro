// A graph extension's own bindings win over its parent's generic providers. An exact binding wins.
// A more specific generic provider wins. An alpha-equivalent generic provider shadows the parent's,
// like an extension's own provider shadows a parent provider for the same key.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@DependencyGraph
interface ParentGraph {
  val pair: Encoder<Pair<String, String>>
  val list: Encoder<List<String>>
  val childFactory: ChildGraph.Factory

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> any(): Encoder<T> = NamedEncoder("parent any")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("parent list(${element.name})")
}

@GraphExtension
interface ChildGraph {
  val int: Encoder<Int>
  val pair: Encoder<Pair<String, String>>
  val list: Encoder<List<String>>
  val long: Encoder<Long>

  @Provides fun int(): Encoder<Int> = NamedEncoder("child int")

  @Provides fun <T> pair(): Encoder<Pair<T, T>> = NamedEncoder("child pair")

  @Provides
  fun <E> childList(element: Encoder<E>): Encoder<List<E>> =
    NamedEncoder("child list(${element.name})")

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childFactory.create()
  assertEquals("parent any", parent.pair.name)
  assertEquals("parent list(string)", parent.list.name)
  assertEquals("child int", child.int.name)
  assertEquals("child pair", child.pair.name)
  assertEquals("child list(string)", child.list.name)
  assertEquals("parent any", child.long.name)
  return "OK"
}
