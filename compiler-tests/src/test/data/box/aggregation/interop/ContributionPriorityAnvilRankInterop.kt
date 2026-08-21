// WITH_ANVIL

interface Service

@Inject
@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 5)
class RankedService : Service

@Inject
@ContributesBinding(AppScope::class, priority = 10)
class PrioritizedService : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertIs<PrioritizedService>(createGraph<AppGraph>().service)
  return "OK"
}
