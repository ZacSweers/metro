// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
internal interface ExampleGraph

@ContributesTo(Unit::class)
<!PRIVATE_CONTRIBUTION_ERROR!>private<!> interface ContributedInterface
