package test

import kotlin.reflect.KClass

interface ContributedInterface
interface AnotherInterface

@ContributesIntoMap(
  AppScope::class,
  binding<@ClassKey @Named("hello") ContributedInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("hello")
  val contributedInterfaces: Map<KClass<*>, ContributedInterface>
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
