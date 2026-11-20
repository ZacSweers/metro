package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
@Named("1")
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterfaces1: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces1
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.single()::class)
  return "OK"
}
