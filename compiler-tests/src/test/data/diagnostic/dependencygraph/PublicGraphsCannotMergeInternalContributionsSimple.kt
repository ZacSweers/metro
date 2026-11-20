// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface <!DEPENDENCY_GRAPH_ERROR!>ExampleGraph<!>

@ContributesTo(Unit::class)
internal interface ContributedInterface
