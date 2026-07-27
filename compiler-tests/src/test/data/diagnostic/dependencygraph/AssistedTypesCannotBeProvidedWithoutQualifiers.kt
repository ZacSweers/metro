// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedInject
class Taco(@Assisted val seasoning: String) {
  @AssistedFactory
  interface Factory {
    fun create(seasoning: String): Taco
  }
}

@DependencyGraph
interface AppGraph {
  val taco: <!INVALID_ASSISTED_BINDING!>Taco<!>

  @Provides
  fun provideTaco(factory: Taco.Factory): Taco = factory.create("spicy")
}
