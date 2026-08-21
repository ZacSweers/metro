interface Service
interface OtherService

@Inject
@ContributesBinding(AppScope::class, binding = binding<Service>(), priority = 1)
@ContributesBinding(AppScope::class, binding = binding<OtherService>())
@ContributesIntoSet(AppScope::class, binding = binding<Service>())
@ContributesIntoMap(
  AppScope::class,
  binding = binding<@StringKey("original") Service>(),
)
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
  val serviceMap: Map<String, Service>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<ReplacementService>(graph.service)
  assertIs<OriginalService>(graph.otherService)
  assertIs<OriginalService>(graph.services.single())
  assertIs<OriginalService>(graph.serviceMap.getValue("original"))
  assertSame<Any>(graph.otherService, graph.services.single())
  assertSame<Any>(graph.otherService, graph.serviceMap.getValue("original"))
  return "OK"
}
