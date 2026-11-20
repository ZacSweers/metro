package test

import kotlin.reflect.KClass

interface ContributedInterface

@ContributesIntoMap(AppScope::class)
@Named("1")
@ClassKey
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces1 = graph.contributedInterfaces1
  assertNotNull(contributedInterfaces1)
  assertEquals(1, contributedInterfaces1.size)
  assertEquals(Impl::class, contributedInterfaces1.entries.first().key)
  assertEquals(Impl::class, contributedInterfaces1.entries.first().value::class)
  return "OK"
}
