interface Service

@Inject
@ContributesBinding(AppScope::class, priority = Int.MAX_VALUE)
class ReplacedService : Service

@Inject
@ContributesBinding(
  AppScope::class,
  replaces = [ReplacedService::class],
  priority = Int.MIN_VALUE,
)
class ReplacementService : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertIs<ReplacementService>(createGraph<AppGraph>().service)
  return "OK"
}
