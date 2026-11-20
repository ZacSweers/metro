package test

interface ContributedInterface
interface AnotherInterface

@ContributesIntoSet(
  AppScope::class,
  binding<@Named("hello") ContributedInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("hello")
  val contributedInterfaces: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.first()::class)
  return "OK"
}
