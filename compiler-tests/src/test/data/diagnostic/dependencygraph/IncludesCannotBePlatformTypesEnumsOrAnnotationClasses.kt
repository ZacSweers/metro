// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  annotation class AnnotationClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes <!GRAPH_CREATORS_ERROR!>value<!>: SomeEnum,
      @Includes <!GRAPH_CREATORS_ERROR!>value2<!>: Int,
      @Includes <!GRAPH_CREATORS_ERROR!>value3<!>: AnnotationClass,
    ): ExampleGraph
  }
}

enum class SomeEnum { VALUE1 }
