// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface GraphWithAbstractClass {

  fun example() {
    @DependencyGraph.Factory
    abstract class <!METRO_DECLARATION_ERROR!>Factory<!> {
      fun create(): GraphWithAbstractClass {
        error("noop")
      }
    }
  }
}
