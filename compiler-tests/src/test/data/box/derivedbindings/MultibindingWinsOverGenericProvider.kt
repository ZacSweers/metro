// A multibinding for a key wins over a generic provider that matches the same key. The generic
// provider still supplies the keys that have no multibinding.
class FallbackSet<T> : AbstractSet<T>() {
  override val size: Int
    get() = 0

  override fun iterator(): Iterator<T> = emptyList<T>().iterator()
}

@BindingContainer
interface Bindings {
  @Multibinds(allowEmpty = true) fun longs(): Set<Long>

  companion object {
    @Provides fun <T> fallback(): Set<T> = FallbackSet()

    @Provides @IntoSet fun first(): String = "a"

    @Provides @IntoSet fun second(): String = "b"
  }
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val strings: Set<String>
  val longs: Set<Long>
  val ints: Set<Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("a", "b"), graph.strings)
  // The declared multibinding is empty, and it still wins.
  assertTrue(graph.longs.isEmpty())
  assertFalse(graph.longs is FallbackSet<*>)
  assertTrue(graph.ints is FallbackSet<*>)
  return "OK"
}
