package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class, binding<@Named("1") ContributedInterface>())
@ContributesIntoSet(AppScope::class, binding<@Named("2") ContributedInterface>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("1") val contributedInterfaces1: Set<ContributedInterface>
  @Named("2") val contributedInterfaces2: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces1 = graph.contributedInterfaces1
  assertNotNull(contributedInterfaces1)
  assertEquals(1, contributedInterfaces1.size)
  assertEquals(Impl::class, contributedInterfaces1.first()::class)
  val contributedInterfaces2 = graph.contributedInterfaces2
  assertNotNull(contributedInterfaces2)
  assertEquals(1, contributedInterfaces2.size)
  assertEquals(Impl::class, contributedInterfaces2.first()::class)
  return "OK"
}
