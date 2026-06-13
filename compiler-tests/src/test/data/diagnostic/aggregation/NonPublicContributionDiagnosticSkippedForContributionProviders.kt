// RENDER_DIAGNOSTICS_FULL_TEXT
// GENERATE_CONTRIBUTION_PROVIDERS: true
// NON_PUBLIC_CONTRIBUTION_SEVERITY: ERROR

interface BindingType
interface SetItem

@Inject
@ContributesBinding(AppScope::class)
internal class InternalBindingContribution : BindingType

@Inject
@ContributesIntoSet(AppScope::class)
internal class InternalSetContribution : SetItem

@Inject
@ExposeImplBinding
@ContributesBinding(AppScope::class)
<!NON_PUBLIC_CONTRIBUTION_ERROR!>internal<!> class InternalExposedImplContribution :
  BindingType

@ContributesTo(AppScope::class)
<!NON_PUBLIC_CONTRIBUTION_ERROR!>internal<!> interface InternalContributedGraph
