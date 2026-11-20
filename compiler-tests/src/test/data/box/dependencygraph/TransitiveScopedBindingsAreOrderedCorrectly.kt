package test

interface ContributedInterface

@Inject
@SingleIn(AppScope::class)
class Impl1 : ContributedInterface

@Inject
@SingleIn(AppScope::class)
class Impl2(val contributedInterface: ContributedInterface)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
  val impl1: Impl1
  val impl2: Impl2

  @Binds val Impl1.bind: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Impl1 is correctly scoped and bound
  val impl1 = graph.impl1
  val contributed = graph.contributedInterface
  assertEquals(Impl1::class, impl1::class)
  assertSame(contributed, impl1)
  // Impl2 correctly uses the bound type
  val impl2 = graph.impl2
  val impl1FromImpl2 = impl2.contributedInterface
  assertSame(impl1, impl1FromImpl2)
  assertSame(contributed, impl1FromImpl2)
  // Calling again also respects scoping
  assertSame(impl2, graph.impl2)
  return "OK"
}
