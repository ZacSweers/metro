// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RENDER_DIAGNOSTICS_FULL_TEXT
// With `generateContributionProviders`, naming a hidden contribution as a graph `excludes`
// target is misleading because the impl was never on the graph. Require `@ExposeImplBinding`
// on the target so the impl is actually a graph binding.

interface ChangelogRepository

@ContributesBinding(AppScope::class)
@Inject
class StubChangelogRepository : ChangelogRepository

interface OtherRepository

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class StubOtherRepository : OtherRepository

@DependencyGraph(
  scope = AppScope::class,
  excludes =
    [
      <!REPLACES_OR_EXCLUDES_TARGET_NOT_EXPOSED!>StubChangelogRepository::class<!>,
      // Sanity: an exposed target should not error.
      StubOtherRepository::class,
    ],
)
interface AppGraph
