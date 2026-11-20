// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesIntoMap(AppScope::class, binding<@ClassKey ContributedInterface>())<!>
<!AGGREGATION_ERROR!>@ContributesIntoMap(AppScope::class)<!>
@ClassKey
@Inject
class Impl : ContributedInterface
