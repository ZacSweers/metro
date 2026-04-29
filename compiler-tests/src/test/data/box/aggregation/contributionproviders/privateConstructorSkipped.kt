// A class with a private @Inject constructor should not use the contribution provider path.
// It should fall back to the regular binds path.
interface Base {
  fun value(): String
}

@ContributesBinding(AppScope::class)
class Impl @Inject private constructor(val input: String) : Base {
  override fun value(): String = input
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val base: Base
  @Provides fun string(): String = "hello"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("hello", graph.base.value())
  return "OK"
}
