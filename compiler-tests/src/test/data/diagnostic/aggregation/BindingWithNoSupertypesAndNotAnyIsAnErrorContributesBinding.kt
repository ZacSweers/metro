// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@ContributesBinding(AppScope::class, binding<<!AGGREGATION_ERROR!>Impl<!>>())
@Inject
class Impl

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
