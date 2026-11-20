package test

interface ContributedInterface<T>

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface<String>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  return "OK"
}
