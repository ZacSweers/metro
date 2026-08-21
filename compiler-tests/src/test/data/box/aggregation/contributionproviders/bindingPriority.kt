// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

interface Service
interface OtherService

@Inject
@ContributesBinding(AppScope::class, binding = binding<Service>(), priority = 1)
@ContributesBinding(AppScope::class, binding = binding<OtherService>())
@ContributesIntoSet(AppScope::class, binding = binding<Service>())
@SingleIn(AppScope::class)
class OriginalService : Service, OtherService

@Inject
@ContributesBinding(AppScope::class, priority = 10)
class ReplacementService : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val otherService: OtherService
  val services: Set<Service>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<ReplacementService>(graph.service)
  assertIs<OriginalService>(graph.otherService)
  assertIs<OriginalService>(graph.services.single())
  assertSame<Any>(graph.otherService, graph.services.single())
  return "OK"
}
