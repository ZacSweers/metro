package test

interface ContributedInterface
interface AnotherInterface

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface>()
)
@ContributesBinding(
  AppScope::class,
  binding<AnotherInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
  val anotherInterface: AnotherInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  val anotherInterface = graph.anotherInterface
  assertNotNull(anotherInterface)
  assertEquals(Impl::class, anotherInterface::class)
  return "OK"
}
