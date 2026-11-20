// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(AppScope::class)
interface ParentGraph : <!GRAPH_CREATORS_ERROR!>ChildGraph.Factory<!> {
  @Provides
  fun provideString(): String = "parent"
}

@GraphExtension(AppScope::class)
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}
