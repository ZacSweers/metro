// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// CONTRIBUTES_AS_INJECT

// Private constructors retain the regular binds path when contribution providers are enabled.
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
