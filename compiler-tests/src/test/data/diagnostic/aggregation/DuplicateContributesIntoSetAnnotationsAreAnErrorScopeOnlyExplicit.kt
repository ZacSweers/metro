// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesIntoSet(AppScope::class, binding<ContributedInterface>())<!>
<!AGGREGATION_ERROR!>@ContributesIntoSet(AppScope::class, binding<ContributedInterface>())<!>
@Inject
class Impl : ContributedInterface
