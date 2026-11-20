package test

interface ContributedInterface

@Named("named")
@ContributesIntoSet(AppScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterfaces: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.first()::class)
  return "OK"
}
