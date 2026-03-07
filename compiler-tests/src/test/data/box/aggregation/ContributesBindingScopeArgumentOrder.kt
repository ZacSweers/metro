interface ContributedInterface

@ContributesBinding(binding = binding<ContributedInterface>(), scope = AppScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.qualifiedName, "Impl")
  return "OK"
}
