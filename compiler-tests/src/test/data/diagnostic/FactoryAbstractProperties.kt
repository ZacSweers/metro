// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedInject class Item(@Assisted val text: String)

@AssistedFactory
interface <!METRO_DECLARATION_ERROR!>ItemFactory<!> {
  val extra: String
  fun create(text: String): Item
}

interface Base {
  val extra: String
}

@DependencyGraph
interface Graph {
  @DependencyGraph.Factory
  interface <!METRO_DECLARATION_ERROR!>Factory<!> : Base {
    fun create(): Graph
  }
}

@AssistedFactory
interface ValidFactory : Base {
  override val extra: String get() = "ok"
  fun create(text: String): Item
}
