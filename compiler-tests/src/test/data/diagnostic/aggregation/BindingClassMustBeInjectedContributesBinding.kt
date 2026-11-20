// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
