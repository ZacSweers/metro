// GENERATE_CONTRIBUTION_PROVIDERS: true
// ENABLE_DERIVED_BINDINGS
// RENDER_DIAGNOSTICS_FULL_TEXT

interface Cache<T>

@ContributesBinding(AppScope::class)
@Inject
class LruCache<T> : Cache<T>

interface Store<T>

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class DiskStore<T> : Store<T>

@Inject
class Consumer(cache: <!NON_EXPOSED_IMPL_TYPE!>LruCache<String><!>, store: DiskStore<String>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val cache: Cache<String>
  val <!MISSING_BINDING!>cacheImpl<!>: <!NON_EXPOSED_IMPL_TYPE!>LruCache<String><!>
  val store: Store<String>
  val storeImpl: DiskStore<String>
}
