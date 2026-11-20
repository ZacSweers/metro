// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface CharSequenceGraph {

  fun value(): CharSequence

  @Provides
  fun provideValue(string: String): CharSequence = string

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes graph: <!GRAPH_CREATORS_ERROR!>CharSequenceGraph<!>): CharSequenceGraph
  }
}
