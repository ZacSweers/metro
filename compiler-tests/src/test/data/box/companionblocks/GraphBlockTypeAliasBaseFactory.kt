// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

interface BaseFactory<G> {
  fun create(@Provides value: String): G
}

typealias AliasedBaseFactory<G> = BaseFactory<G>

@DependencyGraph
interface AppGraph {
  val value: String

  @DependencyGraph.Factory fun interface Factory : AliasedBaseFactory<AppGraph>
}

fun box(): String {
  assertEquals("x", AppGraph.create("x").value)
  return "OK"
}
