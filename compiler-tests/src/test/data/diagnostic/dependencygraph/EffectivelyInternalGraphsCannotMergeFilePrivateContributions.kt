// RENDER_DIAGNOSTICS_FULL_TEXT

package test

internal class Parent {
  @DependencyGraph(Unit::class)
  interface ExampleGraph
}

@ContributesTo(Unit::class)
<!PRIVATE_CONTRIBUTION_ERROR!>private<!> interface ContributedInterface
