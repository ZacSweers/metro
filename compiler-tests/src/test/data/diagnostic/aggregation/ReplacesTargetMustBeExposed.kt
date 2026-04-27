// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RENDER_DIAGNOSTICS_FULL_TEXT
// With `generateContributionProviders`, naming a hidden contribution as a `replaces` target
// is misleading because the impl was never on the graph. Require `@ExposeImplBinding` on the
// target so the impl is actually a graph binding.

interface ChangelogRepository

@ContributesBinding(AppScope::class)
@Inject
class StubChangelogRepository : ChangelogRepository

@ContributesBinding(
  AppScope::class,
  replaces = [<!REPLACES_OR_EXCLUDES_TARGET_NOT_EXPOSED!>StubChangelogRepository::class<!>],
)
@Inject
class RealChangelogRepository : ChangelogRepository

// Sanity: when the target is exposed via @ExposeImplBinding, replacing it is fine.
interface OtherRepository

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class StubOtherRepository : OtherRepository

@ContributesBinding(AppScope::class, replaces = [StubOtherRepository::class])
@Inject
class RealOtherRepository : OtherRepository
