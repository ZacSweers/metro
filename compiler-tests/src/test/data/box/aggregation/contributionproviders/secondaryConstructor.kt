@DependencyGraph(AppScope::class)
interface AppGraph {
  val accountManager: AccountManager
}

interface AccountManager

@ContributesBinding(AppScope::class)
class RealAccountManager : AccountManager {
  @Inject constructor()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.accountManager)
  return "OK"
}
