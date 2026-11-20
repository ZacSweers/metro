package test

import kotlin.reflect.KClass

interface BaseContributedInterface

interface ContributedInterface : BaseContributedInterface

@ContributesIntoMap(AppScope::class, binding<@ClassKey BaseContributedInterface>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val bases: Map<KClass<*>, BaseContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val bases = graph.bases
  assertNotNull(bases)
  assertEquals(1, bases.size)
  assertEquals(Impl::class, bases.entries.first().key)
  assertEquals(Impl::class, bases.entries.first().value::class)
  return "OK"
}
