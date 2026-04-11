// GENERATE_CONTRIBUTION_PROVIDERS
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RENDER_DIAGNOSTICS_FULL_TEXT

interface Repository

@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl : Repository

interface Repository2

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl2 : Repository2

@Inject
class Consumer(repo: <!NON_EXPOSED_IMPL_TYPE!>RepositoryImpl<!>, repo2isOk: RepositoryImpl2)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!MISSING_BINDING!>repositoryImpl<!>: <!NON_EXPOSED_IMPL_TYPE!>RepositoryImpl<!>
  val repositoryImpl2: RepositoryImpl2
}
