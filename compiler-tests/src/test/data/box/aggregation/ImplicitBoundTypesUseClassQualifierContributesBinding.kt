package test

interface ContributedInterface

@ContributesBinding(AppScope::class)
@Named("1")
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterface1: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface1 = graph.contributedInterface1
  assertNotNull(contributedInterface1)
  assertEquals(Impl::class, contributedInterface1::class)
  return "OK"
}
