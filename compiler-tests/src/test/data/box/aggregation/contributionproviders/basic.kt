interface Base {
  fun value(): String
}

@ContributesBinding(AppScope::class)
@Inject
class Impl(val input: String) : Base {
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
