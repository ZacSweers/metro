import kotlin.reflect.KClass

interface DirectRouteKey

interface DirectRouteScreen<T : DirectRouteKey>

class DirectHomeKey : DirectRouteKey

@ContributesIntoMap(AppScope::class)
@Inject
class DirectHomeScreen : DirectRouteScreen<@ClassKey DirectHomeKey>

@DependencyGraph(AppScope::class)
interface DirectRouteGraph {
  val screens: Map<KClass<*>, DirectRouteScreen<DirectHomeKey>>
}

fun box(): String {
  val graph = createGraph<DirectRouteGraph>()
  assertEquals(1, graph.screens.size)
  assertIs<DirectHomeScreen>(graph.screens.getValue(DirectHomeKey::class))
  return "OK"
}
