// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesMultibinding

interface ContributedInterface
interface SecondInterface

@ContributesMultibinding(AppScope::class, boundType = ContributedInterface::class)
@Inject class Impl : ContributedInterface, SecondInterface

@MapKey annotation class MyKey(val key: Int)

@MyKey(1)
@ContributesMultibinding(AppScope::class, boundType = SecondInterface::class)
@Inject class MapImpl : ContributedInterface, SecondInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedSet: Set<ContributedInterface>
  val contributedMap: Map<Int, SecondInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedSet = graph.contributedSet
  assertEquals(contributedSet.single()::class.qualifiedName, "Impl")
  val contributedMap = graph.contributedMap
  assertEquals(contributedMap.keys.single(), 1)
  assertEquals(contributedMap.values.single()::class.qualifiedName, "MapImpl")
  return "OK"
}
