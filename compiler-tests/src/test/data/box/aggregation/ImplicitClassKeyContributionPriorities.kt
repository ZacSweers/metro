import kotlin.reflect.KClass

interface Service

@ClassKey
@ContributesIntoMap(AppScope::class, priority = 100)
@Inject
class PreferredService : Service

@ClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class OtherService : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val services: Map<KClass<*>, Service>
}

fun box(): String {
  val services = createGraph<AppGraph>().services
  assertEquals(setOf(PreferredService::class, OtherService::class), services.keys)
  assertIs<PreferredService>(services.getValue(PreferredService::class))
  assertIs<OtherService>(services.getValue(OtherService::class))
  return "OK"
}
