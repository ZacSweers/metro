// RENDER_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
@DependencyGraph
interface ParentGraph {
  val value: Int

  @Provides
  fun provideInt(): Int = 3
}

// MODULE: main(lib)
@DependencyGraph
interface ChildGraph : <!DEPENDENCY_GRAPH_ERROR!>ParentGraph<!>
