// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph : <!GRAPH_CREATORS_ERROR!>ChildGraph.Factory<!> {
  @Provides
  fun provideString(): String = "parent"
}

@SingleIn(AppScope::class)
@GraphExtension
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}
