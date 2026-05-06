// GENERATE_CONTRIBUTION_PROVIDERS: true
// COMPILER_VERSION: 2.3
// Regression test: @ContributesTo-only types should not generate empty top-level
// holder classes (e.g. *Contributions).

@ContributesTo(AppScope::class)
interface ContributedInterface
