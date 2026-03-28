// GENERATE_CONTRIBUTION_PROVIDERS
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

interface Base

@ContributesBinding(AppScope::class)
@Inject
internal class Impl(val input: String) : Base
