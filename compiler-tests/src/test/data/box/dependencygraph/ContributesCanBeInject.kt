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

@ClassKey(Impl5::class)
@ContributesIntoMap(AppScope::class)
class Impl5(val defaultValue: Int) : Base {
  // Ensure that inject constructors are still picked up even though the class is implicitly annotated
  @Inject constructor() : this(3)
}

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
  assertEquals(3, graph.baseMap.size)
  assertNotNull(graph.baseMap[Impl3::class])
  assertNotNull(graph.baseMap[Impl4::class])
  val impl5 = graph.baseMap.getValue(Impl5::class) as Impl5
  assertEquals(3, impl5.defaultValue)
  return "OK"
}