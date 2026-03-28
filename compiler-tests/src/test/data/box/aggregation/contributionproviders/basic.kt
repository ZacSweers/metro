// MODULE: common
interface Base {
  fun value(): String
}

// MODULE: lib(common)
@ContributesBinding(AppScope::class)
@Inject
class Impl(val input: String) : Base {
  override fun value(): String = input
}

// MODULE: main(lib, common)
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
