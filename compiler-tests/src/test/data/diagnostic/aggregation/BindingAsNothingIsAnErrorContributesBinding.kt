// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

@ContributesBinding(AppScope::class, binding<<!AGGREGATION_ERROR!>Nothing<!>>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
