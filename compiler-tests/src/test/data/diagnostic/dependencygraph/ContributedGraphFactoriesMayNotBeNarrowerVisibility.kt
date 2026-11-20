// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(AppScope::class)
interface <!DEPENDENCY_GRAPH_ERROR!>ExampleGraph<!>

@GraphExtension(Unit::class)
internal interface ContributedInterface {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ContributedInterface
  }
}
