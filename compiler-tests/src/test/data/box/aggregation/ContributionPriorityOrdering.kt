interface Service
interface NegativeService

@Inject @ContributesBinding(AppScope::class) class DefaultService : Service

@Inject
@ContributesBinding(AppScope::class, priority = -10)
class NegativePriorityService : Service

@Inject
@ContributesBinding(AppScope::class, priority = 10)
class HighPriorityService : Service

@Inject
@Named("qualified")
@ContributesBinding(AppScope::class, priority = Int.MAX_VALUE)
class QualifiedService : Service

@Inject
@ContributesBinding(AppScope::class)
class DefaultNegativeService : NegativeService

@Inject
@ContributesBinding(AppScope::class, priority = -100)
class WinningNegativeService : NegativeService

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val negativeService: NegativeService
  @Named("qualified") val qualifiedService: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<HighPriorityService>(graph.service)
  assertIs<WinningNegativeService>(graph.negativeService)
  assertIs<QualifiedService>(graph.qualifiedService)
  return "OK"
}
