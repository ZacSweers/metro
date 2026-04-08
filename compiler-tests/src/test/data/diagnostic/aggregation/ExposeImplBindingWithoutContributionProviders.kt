// RENDER_DIAGNOSTICS_FULL_TEXT
// Warn when @ExposeImplBinding is used but generateContributionProviders is not enabled.

interface Repository

<!EXPOSE_IMPL_TYPE_WITHOUT_CONTRIBUTION_PROVIDERS!>@ExposeImplBinding<!>
@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl : Repository
