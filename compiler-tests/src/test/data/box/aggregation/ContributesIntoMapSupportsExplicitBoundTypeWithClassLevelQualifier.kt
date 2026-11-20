package test

import kotlin.reflect.KClass

interface ContributedInterface

@ContributesIntoMap(AppScope::class, binding<@ClassKey ContributedInterface>())
@Named("1")
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces1
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.entries.single().value::class)
  return "OK"
}
