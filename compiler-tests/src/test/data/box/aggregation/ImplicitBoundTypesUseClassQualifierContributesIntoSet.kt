package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class)
@Named("1")
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterfaces1: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces1 = graph.contributedInterfaces1
  assertNotNull(contributedInterfaces1)
  assertEquals(1, contributedInterfaces1.size)
  assertEquals(Impl::class, contributedInterfaces1.first()::class)
  return "OK"
}
