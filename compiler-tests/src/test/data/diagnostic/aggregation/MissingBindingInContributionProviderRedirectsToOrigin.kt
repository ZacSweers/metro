// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Regression: with `generateContributionProviders`, a missing binding requested through a
// generated `*Contributions` provider should be reported at the user-authored origin class
// (`StubChangelogRepository` here) rather than the synthetic generated provider, which has no
// real source location.

interface ChangelogRepository

class ChangelogRepositoryImpl : ChangelogRepository

@ContributesBinding(AppScope::class)
@Inject
class <!MISSING_BINDING!>StubChangelogRepository<!>(realImpl: ChangelogRepositoryImpl) :
  ChangelogRepository

@DependencyGraph(AppScope::class)
interface AppGraph {
  val repo: ChangelogRepository
}
