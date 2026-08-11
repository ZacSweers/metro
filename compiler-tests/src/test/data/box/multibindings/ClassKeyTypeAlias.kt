import kotlin.reflect.KClass

class ServiceKey

typealias ServiceKeyAlias = ServiceKey

class Service(val value: String)

@DependencyGraph
interface AppGraph {
  @Provides
  @IntoMap
  @ClassKey(ServiceKeyAlias::class)
  fun provideService(): Service = Service("aliased key")

  val services: Map<KClass<*>, Service>
}

fun box(): String {
  assertEquals("aliased key", createGraph<AppGraph>().services[ServiceKey::class]?.value)
  return "OK"
}
