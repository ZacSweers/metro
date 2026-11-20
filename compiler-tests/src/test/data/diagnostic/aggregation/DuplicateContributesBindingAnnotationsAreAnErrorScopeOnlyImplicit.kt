// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
