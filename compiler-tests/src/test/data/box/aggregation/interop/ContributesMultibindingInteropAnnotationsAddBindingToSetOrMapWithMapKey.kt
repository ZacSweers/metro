// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesMultibinding

interface ContributedInterface
interface SecondInterface

@ContributesMultibinding(AppScope::class, boundType = ContributedInterface::class)
@Inject class Impl : ContributedInterface, SecondInterface

@ContributesIntoSet(AppScope::class)
@Inject class AdditionalSetImpl : ContributedInterface

@MapKey annotation class MyKey(val key: Int)

@MyKey(1)
@ContributesMultibinding(AppScope::class, boundType = SecondInterface::class)
@Inject class MapImpl : ContributedInterface, SecondInterface

@MyKey(1)
@ContributesIntoMap(AppScope::class, priority = 10)
@Inject class PreferredMapImpl : SecondInterface

@MyKey(2)
@ContributesMultibinding(AppScope::class, boundType = SecondInterface::class)
@Inject class OtherMapImpl : SecondInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedSet: Set<ContributedInterface>
  val contributedMap: Map<Int, SecondInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedSet = graph.contributedSet
  assertEquals(setOf("Impl", "AdditionalSetImpl"), contributedSet.map { it::class.qualifiedName }.toSet())
  val contributedMap = graph.contributedMap
  assertEquals(setOf(1, 2), contributedMap.keys)
  assertEquals("PreferredMapImpl", contributedMap.getValue(1)::class.qualifiedName)
  assertEquals("OtherMapImpl", contributedMap.getValue(2)::class.qualifiedName)
  return "OK"
}
