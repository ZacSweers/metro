// Multiple @Binds declarations with implicit class keys should resolve to each parameter type.
// https://github.com/ZacSweers/metro/issues/2155
import kotlin.reflect.KClass

interface Base

@Inject class ImplA : Base

@Inject class ImplB : Base

@ContributesTo(AppScope::class)
interface BaseProviders {
  @Binds @IntoMap @ClassKey fun bindImplA(impl: ImplA): Base

  @Binds @IntoMap @ClassKey fun bindImplB(impl: ImplB): Base
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  fun getBaseMap(): Map<KClass<*>, Base>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val map = graph.getBaseMap()
  if (map.size != 2) return "FAIL: expected 2 entries, got ${map.size}"
  if (map[ImplA::class] !is ImplA) return "FAIL: ImplA not found"
  if (map[ImplB::class] !is ImplB) return "FAIL: ImplB not found"
  return "OK"
}
