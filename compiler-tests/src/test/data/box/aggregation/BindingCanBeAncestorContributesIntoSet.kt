package test

interface BaseContributedInterface

interface ContributedInterface : BaseContributedInterface

@ContributesIntoSet(AppScope::class, binding<BaseContributedInterface>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val bases: Set<BaseContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val bases = graph.bases
  assertNotNull(bases)
  assertEquals(1, bases.size)
  assertEquals(Impl::class, bases.first()::class)
  return "OK"
}
