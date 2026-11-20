// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

<!AGGREGATION_ERROR!>@ContributesIntoMap(AppScope::class, binding<@ClassKey @Named("1") ContributedInterface>())<!>
<!AGGREGATION_ERROR!>@ContributesIntoMap(AppScope::class, binding<@ClassKey @Named("1") ContributedInterface>())<!>
@Inject
class Impl : ContributedInterface
