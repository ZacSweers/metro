// CONTRIBUTES_AS_INJECT
import kotlin.reflect.KClass

interface Base

@ContributesBinding(AppScope::class)
class Impl1 : Base

@ContributesIntoSet(AppScope::class)
class Impl2 : Base

@ClassKey(Impl3::class)
@ContributesIntoMap(AppScope::class)
class Impl3 : Base

@ClassKey(Impl4::class)
@ContributesIntoMap(AppScope::class)
object Impl4 : Base

@DependencyGraph(AppScope::class)
interface AppGraph {
  val base: Base
  val baseSet: Set<Base>
  val baseMap: Map<KClass<*>, Base>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.base is Impl1)
  assertTrue(graph.baseSet.single() is Impl2)
  assertTrue(graph.baseMap.size == 2)
  assertNotNull(graph.baseMap[Impl3::class])
  assertNotNull(graph.baseMap[Impl4::class])
  return "OK"
}