package test

interface ContributedInterface

@ContributesBinding(AppScope::class)
@ContributesBinding(AppScope::class, binding<@Named("2") ContributedInterface>())
@Named("1")
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterface1: ContributedInterface
  @Named("2") val contributedInterface2: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface1 = graph.contributedInterface1
  assertNotNull(contributedInterface1)
  assertEquals(Impl::class, contributedInterface1::class)
  val contributedInterface2 = graph.contributedInterface2
  assertNotNull(contributedInterface2)
  assertEquals(Impl::class, contributedInterface2::class)
  return "OK"
}
