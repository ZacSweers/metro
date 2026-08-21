interface OtherScope
interface Service

@Inject
@ContributesBinding(OtherScope::class, priority = 1)
class OtherScopeService : Service

@Inject
@ContributesBinding(AppScope::class, priority = 10)
class AppScopeService : Service

@DependencyGraph(AppScope::class, additionalScopes = [OtherScope::class])
interface CombinedGraph {
  val service: Service
}

@DependencyGraph(OtherScope::class)
interface OtherGraph {
  val service: Service
}

fun box(): String {
  assertIs<AppScopeService>(createGraph<CombinedGraph>().service)
  assertIs<OtherScopeService>(createGraph<OtherGraph>().service)
  return "OK"
}
