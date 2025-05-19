// DONT_SORT_DECLARATIONS
@DependencyGraph
interface SelfCycleGraph {
  fun s(): S
}

@Suppress("MEMBERS_INJECT_WARNING")
@Inject
class S(val sProvider: Provider<S>) {
  @Inject lateinit var sLazy: Lazy<S>
}
