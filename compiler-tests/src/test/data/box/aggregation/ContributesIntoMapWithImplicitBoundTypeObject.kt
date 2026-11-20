package test

import kotlin.reflect.KClass

interface ContributedInterface

@ClassKey
@ContributesIntoMap(AppScope::class)
object Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Map<KClass<*>, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertTrue(contributedInterfaces.isNotEmpty())
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.entries.first().key)
  assertEquals(Impl::class, contributedInterfaces.entries.first().value::class)
  return "OK"
}
