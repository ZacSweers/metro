// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface <!DEPENDENCY_GRAPH_ERROR!>ExampleGraph<!>

private class Parent {
  @ContributesTo(Unit::class)
  interface <!PRIVATE_CONTRIBUTION_ERROR!>ContributedInterface<!>
}
