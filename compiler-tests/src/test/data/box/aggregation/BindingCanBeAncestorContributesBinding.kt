package test

interface BaseContributedInterface

interface ContributedInterface : BaseContributedInterface

@ContributesBinding(AppScope::class, binding<BaseContributedInterface>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val base: BaseContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val base = graph.base
  assertNotNull(base)
  assertEquals(Impl::class, base::class)
  return "OK"
}
