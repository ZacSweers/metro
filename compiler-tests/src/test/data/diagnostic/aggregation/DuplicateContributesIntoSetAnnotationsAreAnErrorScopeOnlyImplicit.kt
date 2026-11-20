// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesIntoSet(AppScope::class)<!>
<!AGGREGATION_ERROR!>@ContributesIntoSet(AppScope::class)<!>
@Inject
class Impl : ContributedInterface
