// When a parent and a grandparent declare alpha-equivalent generic providers, a grandchild uses the
// nearer one. Ordinary providers for the same key follow the same rule.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@DependencyGraph
interface AppGraph {
  val list: Encoder<List<String>>
  val childFactory: ChildGraph.Factory

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("app list(${element.name})")
}

@GraphExtension
interface ChildGraph {
  val grandchildFactory: GrandchildGraph.Factory

  @Provides
  fun <E> childList(element: Encoder<E>): Encoder<List<E>> =
    NamedEncoder("child list(${element.name})")

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val list: Encoder<List<String>>

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}

fun box(): String {
  val app = createGraph<AppGraph>()
  val grandchild = app.childFactory.create().grandchildFactory.create()
  assertEquals("app list(string)", app.list.name)
  assertEquals("child list(string)", grandchild.list.name)
  return "OK"
}
