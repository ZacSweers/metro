package test

interface ContributedInterface<T>

@ContributesIntoSet(
  AppScope::class,
  binding<ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Set<ContributedInterface<String>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.first()::class)
  return "OK"
}
