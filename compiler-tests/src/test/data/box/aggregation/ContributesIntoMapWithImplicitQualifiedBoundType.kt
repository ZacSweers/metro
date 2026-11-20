package test

import kotlin.reflect.KClass

interface ContributedInterface

@ClassKey
@Named("named")
@ContributesIntoMap(AppScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterfaces: Map<KClass<*>, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.entries.first().key)
  assertEquals(Impl::class, contributedInterfaces.entries.first().value::class)
  return "OK"
}
