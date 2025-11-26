@AssistedInject
class Taco(@Assisted val seasoning: String) {
  @AssistedFactory
  interface Factory {
    fun create(seasoning: String): Taco
  }
}

@DependencyGraph
interface AppGraph {
  @Named("qualified") val taco: Taco

  @Provides @Named("qualified")
  fun provideTaco(factory: Taco.Factory): Taco = factory.create("spicy")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("spicy", graph.taco.seasoning)
  return "OK"
}