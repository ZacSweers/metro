// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RENDER_DIAGNOSTICS_FULL_TEXT

// The NON_EXPOSED_IMPL_TYPE warning should fire for qualified injections too — qualifying the
// injection still doesn't make the impl class a binding on the graph.

interface Repository

@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl : Repository

@Inject class Consumer(@Named("alt") repo: <!NON_EXPOSED_IMPL_TYPE!>RepositoryImpl<!>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Named("alt")
  val <!MISSING_BINDING!>repositoryImpl<!>: <!NON_EXPOSED_IMPL_TYPE!>RepositoryImpl<!>
}
