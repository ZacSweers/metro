package test

interface ContributedInterface
interface AnotherInterface

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  return "OK"
}
