// One graph requests a qualified @AssistedFactory with its qualifier and with none.

@Qualifier annotation class Chosen

@AssistedInject
class Taco(@Assisted val seasoning: String) {
  @Chosen
  @AssistedFactory
  fun interface Factory {
    fun create(seasoning: String): Taco
  }
}

@DependencyGraph
interface AppGraph {
  val factory: Taco.Factory

  @Chosen val chosenFactory: Taco.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("mild", graph.factory.create("mild").seasoning)
  assertEquals("hot", graph.chosenFactory.create("hot").seasoning)
  return "OK"
}
