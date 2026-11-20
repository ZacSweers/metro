// RENDER_DIAGNOSTICS_FULL_TEXT

package test

<!AGGREGATION_ERROR!>@ContributesTo(AppScope::class)<!>
<!AGGREGATION_ERROR!>@ContributesTo(AppScope::class)<!>
interface ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
