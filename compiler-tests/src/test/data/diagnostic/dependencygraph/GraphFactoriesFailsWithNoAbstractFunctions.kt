// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  interface <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>Factory<!> {
    fun create(): ExampleGraph {
      TODO()
    }
  }
}
