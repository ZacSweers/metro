package test

import kotlin.reflect.KClass

interface ContributedInterface<T>

@ContributesIntoMap(
  AppScope::class,
  binding<@ClassKey ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Map<KClass<*>, ContributedInterface<String>>
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
